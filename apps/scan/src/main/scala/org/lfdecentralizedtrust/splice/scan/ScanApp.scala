// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.server.Directives.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import org.lfdecentralizedtrust.splice.admin.api.TraceContextDirectives.withTraceContext
import org.lfdecentralizedtrust.splice.admin.http.{AdminRoutes, HttpErrorHandler}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round as roundCodegen
import org.lfdecentralizedtrust.splice.config.SharedSpliceAppParameters
import org.lfdecentralizedtrust.splice.environment.{
  BaseLedgerConnection,
  DarResources,
  Node,
  PackageVersionSupport,
  ParticipantAdminConnection,
  RetryFor,
  SequencerAdminConnection,
  SpliceLedgerClient,
}
import org.lfdecentralizedtrust.splice.http.v0.scan.ScanResource
import org.lfdecentralizedtrust.tokenstandard.metadata.v1.Resource as TokenStandardMetadataResource
import org.lfdecentralizedtrust.tokenstandard.transferinstruction.v1.Resource as TokenStandardTransferInstructionResource
import org.lfdecentralizedtrust.tokenstandard.allocation.v1.Resource as TokenStandardAllocationResource
import org.lfdecentralizedtrust.tokenstandard.allocationinstruction.v1.Resource as TokenStandardAllocationInstructionResource
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.admin.http.{
  HttpScanHandler,
  HttpTokenStandardAllocationHandler,
  HttpTokenStandardAllocationInstructionHandler,
  HttpTokenStandardMetadataHandler,
  HttpTokenStandardTransferInstructionHandler,
}
import org.lfdecentralizedtrust.splice.scan.automation.ScanAutomationService
import org.lfdecentralizedtrust.splice.scan.config.ScanAppBackendConfig
import org.lfdecentralizedtrust.splice.scan.metrics.ScanAppMetrics
import org.lfdecentralizedtrust.splice.scan.store.{AcsSnapshotStore, ScanStore}
import org.lfdecentralizedtrust.splice.scan.store.db.{
  ScanAggregatesReader,
  ScanAggregatesReaderContext,
}
import org.lfdecentralizedtrust.splice.scan.dso.DsoAnsResolver
import org.lfdecentralizedtrust.splice.store.PageLimit
import org.lfdecentralizedtrust.splice.util.HasHealth
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.LifeCycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{TraceContext, TracerProvider}
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.http.cors.scaladsl.CorsDirectives.cors
import org.apache.pekko.http.cors.scaladsl.settings.CorsSettings

import scala.concurrent.{ExecutionContextExecutor, Future}
import org.apache.pekko.stream.Materializer

/** Class representing a Scan app instance.
  *
  * Modelled after Canton's ParticipantNode class.
  */
class ScanApp(
    override val name: InstanceName,
    val config: ScanAppBackendConfig,
    val amuletAppParameters: SharedSpliceAppParameters,
    storage: Storage,
    override protected val clock: Clock,
    val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    futureSupervisor: FutureSupervisor,
    nodeMetrics: ScanAppMetrics,
    adminRoutes: AdminRoutes,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
    tracer: Tracer,
) extends Node[ScanApp.State, Unit](
      config.svUser,
      config.participantClient,
      amuletAppParameters,
      loggerFactory,
      tracerProvider,
      futureSupervisor,
      nodeMetrics,
    ) {

  override def packagesForJsonDecoding =
    super.packagesForJsonDecoding ++ DarResources.amuletNameService.all ++ DarResources.dsoGovernance.all

  override def preInitializeAfterLedgerConnection(
      connection: BaseLedgerConnection,
      ledgerClient: SpliceLedgerClient,
  )(implicit traceContext: TraceContext) = Future.unit

  override def initialize(
      ledgerClient: SpliceLedgerClient,
      // The primary party in scan as that points to the SV party
      serviceUserPrimaryParty: PartyId,
      preInitializeState: Unit,
  )(implicit tc: TraceContext): Future[ScanApp.State] = {
    val appInitConnection = ledgerClient
      .readOnlyConnection(
        this.getClass.getSimpleName,
        loggerFactory,
      )
    val bftSequencersWithAdminConnections = config.bftSequencers.map(bftSequencer =>
      new SequencerAdminConnection(
        bftSequencer.sequencerAdminClient,
        amuletAppParameters.loggingConfig.api,
        loggerFactory,
        nodeMetrics.grpcClientMetrics,
        retryProvider,
      ) -> bftSequencer
    )
    for {
      dsoParty <- appInitStep("Get DSO party from user metadata") {
        appInitConnection.getDsoPartyFromUserMetadata(config.svUser)
      }
      scanAggregatesReaderContext = new ScanAggregatesReaderContext(
        clock,
        ledgerClient,
        amuletAppParameters.upgradesConfig,
        loggerFactory,
        retryProvider,
        ec,
        Materializer(ac),
        httpClient,
        templateDecoder,
      )
      participantAdminConnection = new ParticipantAdminConnection(
        config.participantClient.adminApi,
        amuletAppParameters.loggingConfig.api,
        loggerFactory,
        nodeMetrics.grpcClientMetrics,
        retryProvider,
      )
      participantId <- appInitStep("Get participant id") {
        participantAdminConnection.getParticipantId()
      }
      migrationInfo <- appInitStep(s"Get domain migration info from ${config.svUser}") {
        DomainMigrationInfo.loadFromUserMetadata(
          appInitConnection,
          config.svUser,
        )
      }
      svName <- appInitStep(s"Get SV name from ${config.svUser}") {
        appInitConnection.getSvNameFromUserMetadata(config.svUser)
      }
      _ = if (config.domainMigrationId != migrationInfo.currentMigrationId) {
        throw Status.INVALID_ARGUMENT
          .withDescription(
            s"Migration id ${migrationInfo.currentMigrationId} from the the SV user metadata does not match the configured migration id ${config.domainMigrationId} in the scan app. Please check if the scan app is configured with the correct migration id"
          )
          .asRuntimeException()
      }
      store = ScanStore(
        key = ScanStore.Key(dsoParty = dsoParty),
        storage,
        isFirstSv = config.isFirstSv,
        loggerFactory,
        retryProvider,
        { store =>
          ScanAggregatesReader(store, scanAggregatesReaderContext)
        },
        migrationInfo,
        participantId,
        config.cache,
        config.updateHistoryBackfillImportUpdatesEnabled,
        nodeMetrics.dbScanStore,
      )
      acsSnapshotStore = AcsSnapshotStore(
        storage,
        store.updateHistory,
        migrationInfo.currentMigrationId,
        loggerFactory,
      )
      sequencerAdminConnection = new SequencerAdminConnection(
        config.sequencerAdminClient,
        amuletAppParameters.loggingConfig.api,
        loggerFactory,
        nodeMetrics.grpcClientMetrics,
        retryProvider,
      )
      automation = new ScanAutomationService(
        config,
        clock,
        ledgerClient,
        retryProvider,
        loggerFactory,
        store,
        storage,
        acsSnapshotStore,
        config.ingestFromParticipantBegin,
        config.ingestUpdateHistoryFromParticipantBegin,
        serviceUserPrimaryParty,
        svName,
        amuletAppParameters.upgradesConfig,
      )
      _ <- appInitStep("Wait until there is an OpenMiningRound contract") {
        retryProvider.waitUntil(
          RetryFor.WaitingOnInitDependency,
          "wait_open_mining",
          "there is an OpenMiningRound contract",
          store.multiDomainAcsStore
            .listContracts(roundCodegen.OpenMiningRound.COMPANION, limit = PageLimit.tryCreate(1))
            .map { cs =>
              if (cs.isEmpty) {
                throw Status.NOT_FOUND
                  .withDescription("OpenMiningRound contract")
                  .asRuntimeException()
              }
            },
          logger,
        )
      }
      dsoAnsResolver = new DsoAnsResolver(
        dsoParty,
        config.spliceInstanceNames.nameServiceNameAcronym.toLowerCase(),
      )
      synchronizerId <- appInitStep("Get synchronizer id") {
        retryProvider.getValueWithRetries(
          RetryFor.WaitingOnInitDependency,
          "amulet synchronizer id",
          "amulet rules synchronizer id",
          store.getAmuletRulesWithState().map {
            _.state.fold(
              identity,
              throw Status.FAILED_PRECONDITION
                .withDescription("Amulet rules in fllight")
                .asRuntimeException(),
            )
          },
          logger,
        )
      }
      packageVersionSupport = PackageVersionSupport.createPackageVersionSupport(
        synchronizerId,
        appInitConnection,
      )
      scanHandler = new HttpScanHandler(
        serviceUserPrimaryParty,
        config.svUser,
        config.spliceInstanceNames,
        participantAdminConnection,
        sequencerAdminConnection,
        store,
        acsSnapshotStore,
        dsoAnsResolver,
        config.miningRoundsCacheTimeToLiveOverride,
        config.enableForcedAcsSnapshots,
        clock,
        loggerFactory,
        packageVersionSupport,
        bftSequencersWithAdminConnections,
      )

      tokenStandardTransferInstructionHandler = new HttpTokenStandardTransferInstructionHandler(
        store,
        clock,
        loggerFactory,
      )
      tokenStandardAllocationHandler = new HttpTokenStandardAllocationHandler(
        store,
        clock,
        loggerFactory,
      )

      tokenStandardMetadataHandler = new HttpTokenStandardMetadataHandler(
        store,
        config.spliceInstanceNames,
        loggerFactory,
      )

      tokenStandardAllocationInstructionHandler = new HttpTokenStandardAllocationInstructionHandler(
        store,
        clock,
        loggerFactory,
      )
      route = cors(
        CorsSettings(ac).withExposedHeaders(Seq("traceparent"))
      ) {
        withTraceContext { traceContext =>
          {
            def buildRouteForOperation(operation: String, httpService: String) = {
              nodeMetrics.httpServerMetrics
                .withMetrics(httpService)(operation)
                .tflatMap { _ =>
                  val httpErrorHandler = new HttpErrorHandler(loggerFactory)
                  val base = httpErrorHandler.directive(traceContext).tflatMap { _ =>
                    provide(traceContext)
                  }
                  (httpService, config.parameters.customTimeouts.get(operation)) match {
                    // custom HTTP timeouts
                    case ("scan", Some(customTimeout)) =>
                      withRequestTimeout(
                        customTimeout.duration,
                        httpErrorHandler.timeoutHandler(customTimeout.duration, _),
                      ).tflatMap { _ =>
                        base
                      }
                    case _ =>
                      base
                  }
                }
            }

            requestLogger(traceContext) {
              concat(
                ScanResource.routes(
                  scanHandler,
                  buildRouteForOperation(_, "scan"),
                ),
                TokenStandardTransferInstructionResource.routes(
                  tokenStandardTransferInstructionHandler,
                  buildRouteForOperation(_, "token_standard_transfer_instruction"),
                ),
                TokenStandardAllocationInstructionResource.routes(
                  tokenStandardAllocationInstructionHandler,
                  buildRouteForOperation(_, "token_standard_allocation_instruction"),
                ),
                TokenStandardMetadataResource.routes(
                  tokenStandardMetadataHandler,
                  buildRouteForOperation(_, "token_standard_metadata"),
                ),
                TokenStandardAllocationResource.routes(
                  tokenStandardAllocationHandler,
                  buildRouteForOperation(_, "token_standard_allocation"),
                ),
              )
            }
          }
        }
      }
      _ = adminRoutes.updateRoute(route)
    } yield {
      ScanApp.State(
        participantAdminConnection,
        sequencerAdminConnection,
        storage,
        store,
        automation,
        loggerFactory.getTracedLogger(ScanApp.State.getClass),
        timeouts,
        bftSequencersWithAdminConnections.map(_._1),
      )
    }
  }
  override lazy val ports = Map("admin" -> config.adminApi.port)

  protected[this] override def automationServices(st: ScanApp.State) =
    Seq(st.automation)
}

object ScanApp {

  case class State(
      participantAdminConnection: ParticipantAdminConnection,
      sequencerAdminConnection: SequencerAdminConnection,
      storage: Storage,
      store: ScanStore,
      automation: ScanAutomationService,
      logger: TracedLogger,
      timeouts: ProcessingTimeout,
      bftSequencersAdminConnections: Seq[SequencerAdminConnection],
  ) extends AutoCloseable
      with HasHealth {
    override def isHealthy: Boolean = storage.isActive && automation.isHealthy

    override def close(): Unit = {
      LifeCycle.close(bftSequencersAdminConnections*)(logger)
      LifeCycle.close(
        automation,
        store,
        storage,
        sequencerAdminConnection,
        participantAdminConnection,
      )(logger)
    }
  }
}
