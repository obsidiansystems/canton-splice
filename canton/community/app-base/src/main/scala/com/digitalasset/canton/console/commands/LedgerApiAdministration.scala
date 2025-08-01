// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.console.commands

import cats.syntax.foldable.*
import cats.syntax.functorFilter.*
import cats.syntax.traverse.*
import com.daml.jwt.{AuthServiceJWTCodec, Jwt, JwtDecoder, StandardJWTPayload}
import com.daml.ledger.api.v2.admin.command_inspection_service.CommandState
import com.daml.ledger.api.v2.admin.package_management_service.PackageDetails
import com.daml.ledger.api.v2.admin.party_management_service.PartyDetails as ProtoPartyDetails
import com.daml.ledger.api.v2.commands.{Command, DisclosedContract, PrefetchContractKey}
import com.daml.ledger.api.v2.completion.Completion
import com.daml.ledger.api.v2.event.CreatedEvent
import com.daml.ledger.api.v2.event_query_service.GetEventsByContractIdResponse
import com.daml.ledger.api.v2.interactive.interactive_submission_service.{
  ExecuteSubmissionResponse as ExecuteResponseProto,
  GetPreferredPackagesResponse,
  HashingSchemeVersion,
  PackagePreference,
  PrepareSubmissionResponse as PrepareResponseProto,
  PreparedTransaction,
}
import com.daml.ledger.api.v2.reassignment.Reassignment as ReassignmentProto
import com.daml.ledger.api.v2.state_service.{
  ActiveContract,
  GetActiveContractsResponse,
  GetConnectedSynchronizersResponse,
}
import com.daml.ledger.api.v2.topology_transaction.TopologyTransaction as TopoplogyTransactionProto
import com.daml.ledger.api.v2.transaction.{
  Transaction as ApiTransaction,
  TransactionTree as TransactionTreeProto,
}
import com.daml.ledger.api.v2.transaction_filter.CumulativeFilter.IdentifierFilter
import com.daml.ledger.api.v2.transaction_filter.{
  CumulativeFilter,
  EventFormat,
  Filters,
  ParticipantAuthorizationTopologyFormat,
  TemplateFilter,
  TopologyFormat,
  TransactionFilter as TransactionFilterProto,
  UpdateFormat,
}
import com.daml.ledger.javaapi as javab
import com.daml.ledger.javaapi.data.{
  GetUpdateTreesResponse,
  GetUpdatesResponse,
  Reassignment,
  TopologyTransaction,
  Transaction,
  TransactionFilter,
  TransactionTree,
}
import com.daml.ledger.api.v2.value.Identifier
import com.daml.metrics.api.MetricsContext
import com.daml.scalautil.Statement.discard
import com.digitalasset.canton.admin.api.client.commands.LedgerApiCommands
import com.digitalasset.canton.admin.api.client.commands.LedgerApiCommands.UpdateService.*
import com.digitalasset.canton.admin.api.client.commands.LedgerApiTypeWrappers.{
  WrappedContractEntry,
  WrappedIncompleteAssigned,
  WrappedIncompleteUnassigned,
}
import com.digitalasset.canton.admin.api.client.data.*
import com.digitalasset.canton.config.ConsoleCommandTimeout
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.console.{
  AdminCommandRunner,
  ConsoleEnvironment,
  ConsoleMacros,
  FeatureFlag,
  FeatureFlagFilter,
  Help,
  Helpful,
  LedgerApiCommandRunner,
  LocalParticipantReference,
  ParticipantReference,
  RemoteParticipantReference,
}
import com.digitalasset.canton.crypto.Signature
import com.digitalasset.canton.data.{CantonTimestamp, DeduplicationPeriod}
import com.digitalasset.canton.ledger.api.util.TransactionTreeOps.TransactionTreeOps
import com.digitalasset.canton.ledger.api.{IdentityProviderConfig, IdentityProviderId, JwksUrl}
import com.digitalasset.canton.ledger.client.services.admin.IdentityProviderConfigClient
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.networking.grpc.{GrpcError, RecordingStreamObserver}
import com.digitalasset.canton.participant.ledger.api.client.JavaDecodeUtil
import com.digitalasset.canton.platform.apiserver.execution.CommandStatus
import com.digitalasset.canton.protocol.LfContractId
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.NoTracing
import com.digitalasset.canton.{LfPackageId, LfPackageName, LfPartyId, config}
import com.digitalasset.daml.lf.data.Ref
import com.google.protobuf.field_mask.FieldMask
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext
import scala.util.chaining.scalaUtilChainingOps
import scala.util.{Failure, Success, Try}

trait BaseLedgerApiAdministration extends NoTracing with StreamingCommandHelper {
  thisAdministration: LedgerApiCommandRunner & NamedLogging & FeatureFlagFilter =>

  implicit protected[canton] lazy val executionContext: ExecutionContext =
    consoleEnvironment.environment.executionContext

  implicit val consoleEnvironment: ConsoleEnvironment

  protected val name: String

  protected lazy val userId: String = token
    .flatMap(encodedToken => JwtDecoder.decode(Jwt(encodedToken)).toOption)
    .flatMap(decodedToken => AuthServiceJWTCodec.readFromString(decodedToken.payload).toOption)
    .map { case s: StandardJWTPayload =>
      s.userId
    }
    .getOrElse(LedgerApiCommands.defaultUserId)

  private val eventFormatAllParties: Option[EventFormat] = Some(
    EventFormat(
      filtersByParty = Map.empty,
      filtersForAnyParty = Some(Filters(Nil)),
      verbose = true,
    )
  )

  def optionallyAwait[Tx](
      tx: Tx,
      txId: String,
      txSynchronizerId: String,
      optTimeout: Option[config.NonNegativeDuration],
  ): Tx
  def timeouts: ConsoleCommandTimeout = consoleEnvironment.commandTimeouts
  protected def defaultLimit: PositiveInt =
    consoleEnvironment.environment.config.parameters.console.defaultLimit

  @Help.Summary("Group of commands that access the ledger-api V2", FeatureFlag.Testing)
  @Help.Group("Ledger Api")
  object ledger_api extends Helpful {

    @Help.Summary("Read from update stream", FeatureFlag.Testing)
    @Help.Group("Updates")
    object updates extends Helpful {

      @Help.Summary("Get update trees", FeatureFlag.Testing)
      @Help.Description(
        """This function connects to the update tree stream for the given parties and collects update trees
          |until either `completeAfter` update trees have been received or `timeout` has elapsed.
          |The returned update trees can be filtered to be between the given offsets (default: no filtering).
          |If the participant has been pruned via `pruning.prune` and if `beginOffset` is lower than the pruning offset,
          |this command fails with a `NOT_FOUND` error.
          |If the beginOffset is zero then the participant begin is taken as beginning offset.
          |If the endOffset is None then a continuous stream is returned."""
      )
      def trees(
          partyIds: Set[PartyId],
          completeAfter: PositiveInt,
          beginOffsetExclusive: Long = 0L,
          endOffsetInclusive: Option[Long] = None,
          verbose: Boolean = true,
          timeout: config.NonNegativeDuration = timeouts.ledgerCommand,
          resultFilter: UpdateTreeWrapper => Boolean = _ => true,
      ): Seq[UpdateTreeWrapper] =
        trees_with_tx_filter(
          filter = TransactionFilterProto(partyIds.map(_.toLf -> Filters(Nil)).toMap, None),
          completeAfter = completeAfter,
          beginOffsetExclusive = beginOffsetExclusive,
          endOffsetInclusive = endOffsetInclusive,
          verbose = verbose,
          timeout = timeout,
          resultFilter = resultFilter,
        )

      @Help.Summary("Get update trees", FeatureFlag.Testing)
      @Help.Description(
        """This function connects to the update tree stream for the transaction filter and collects update trees
          |until either `completeAfter` update trees have been received or `timeout` has elapsed.
          |The returned update trees can be filtered to be between the given offsets (default: no filtering).
          |If the participant has been pruned via `pruning.prune` and if `beginOffset` is lower than the pruning offset,
          |this command fails with a `NOT_FOUND` error.
          |NOTE: As opposed to the flat transaction streams, the transaction filter provided for transaction trees DO NOT
          | filter the events in the tree, but decide instead the event payloads projection rules.
          | (e.g. whether to include in the CreatedEvent the created event blob).
          |If the beginOffset is zero then the participant begin is taken as beginning offset.
          |If the endOffset is None then a continuous stream is returned."""
      )
      def trees_with_tx_filter(
          filter: TransactionFilterProto,
          completeAfter: PositiveInt,
          beginOffsetExclusive: Long = 0L,
          endOffsetInclusive: Option[Long] = None,
          verbose: Boolean = true,
          timeout: config.NonNegativeDuration = timeouts.ledgerCommand,
          resultFilter: UpdateTreeWrapper => Boolean = _ => true,
      ): Seq[UpdateTreeWrapper] = check(FeatureFlag.Testing)({
        val observer = new RecordingStreamObserver[UpdateTreeWrapper](completeAfter, resultFilter)
        mkResult(
          subscribe_trees(observer, filter, beginOffsetExclusive, endOffsetInclusive, verbose),
          "getUpdateTrees",
          observer,
          timeout,
        )
      })

      @Help.Summary("Subscribe to the update tree stream", FeatureFlag.Testing)
      @Help.Description(
        """This function connects to the update tree stream and passes update trees to `observer` until
          |the stream is completed.
          |Only update trees for parties in `filter.filterByParty.keys` will be returned.
          |Use `filter = TransactionFilter(Map(myParty.toLf -> Filters()))` to return all trees for `myParty: PartyId`.
          |The returned updates can be filtered to be between the given offsets (default: no filtering).
          |If the participant has been pruned via `pruning.prune` and if `beginOffset` is lower than the pruning offset,
          |this command fails with a `NOT_FOUND` error.
          |If the beginOffset is zero then the participant begin is taken as beginning offset.
          |If the endOffset is None then a continuous stream is returned."""
      )
      def subscribe_trees(
          observer: StreamObserver[UpdateTreeWrapper],
          filter: TransactionFilterProto,
          beginOffsetExclusive: Long = 0L,
          endOffsetInclusive: Option[Long] = None,
          verbose: Boolean = true,
      ): AutoCloseable =
        check(FeatureFlag.Testing)(
          consoleEnvironment.run {
            ledgerApiCommand(
              LedgerApiCommands.UpdateService.SubscribeTrees(
                observer,
                beginOffsetExclusive,
                endOffsetInclusive,
                filter,
                verbose,
              )
            )
          }
        )

      @Help.Summary("Get flat updates", FeatureFlag.Testing)
      @Help.Description(
        """This function connects to the flat update stream for the given parties and collects updates
          |until either `completeAfter` flat updates have been received or `timeout` has elapsed.
          |The returned updates can be filtered to be between the given offsets (default: no filtering).
          |If the participant has been pruned via `pruning.prune` and if `beginOffset` is lower than the pruning offset,
          |this command fails with a `NOT_FOUND` error. If you need to specify filtering conditions for template IDs and
          |including create event blobs for explicit disclosure, consider using `flat_with_tx_filter`.
          |If the beginOffset is zero then the participant begin is taken as beginning offset.
          |If the endOffset is None then a continuous stream is returned."""
      )
      def flat(
          partyIds: Set[PartyId],
          completeAfter: PositiveInt,
          beginOffsetExclusive: Long = 0L,
          endOffsetInclusive: Option[Long] = None,
          verbose: Boolean = true,
          timeout: config.NonNegativeDuration = timeouts.ledgerCommand,
          resultFilter: UpdateWrapper => Boolean = _ => true,
          synchronizerFilter: Option[SynchronizerId] = None,
      ): Seq[UpdateWrapper] = check(FeatureFlag.Testing)({

        val resultFilterWithSynchronizer = synchronizerFilter match {
          case Some(synchronizerId) =>
            (update: UpdateWrapper) =>
              resultFilter(update) && update.synchronizerId == synchronizerId.toProtoPrimitive
          case None => resultFilter
        }

        val observer =
          new RecordingStreamObserver[UpdateWrapper](completeAfter, resultFilterWithSynchronizer)

        val filter = TransactionFilterProto(partyIds.map(_.toLf -> Filters(Nil)).toMap, None)
        mkResult(
          subscribe_flat(observer, filter, beginOffsetExclusive, endOffsetInclusive, verbose),
          "getUpdates",
          observer,
          timeout,
        )
      })

      @Help.Summary("Get reassignments", FeatureFlag.Testing)
      @Help.Description(
        """This function connects to the update stream for the given parties and template ids and collects reassignment
          |events (assigned and unassigned) until either `completeAfter` updates have been received or `timeout` has
          |elapsed.
          |If the party ids set is empty then the reassignments for all the parties will be fetched.
          |If the template ids collection is empty then the reassignments for all the template ids will be fetched.
          |The returned updates can be filtered to be between the given offsets (default: no filtering).
          |If the participant has been pruned via `pruning.prune` and if `beginOffset` is lower than the pruning offset,
          |this command fails with a `NOT_FOUND` error.
          |If the beginOffset is zero then the participant begin is taken as beginning offset.
          |If the endOffset is None then a continuous stream is returned."""
      )
      def reassignments(
          partyIds: Set[PartyId],
          filterTemplates: Seq[TemplateId],
          completeAfter: PositiveInt,
          beginOffsetExclusive: Long = 0L,
          endOffsetInclusive: Option[Long] = None,
          timeout: config.NonNegativeDuration = timeouts.ledgerCommand,
          resultFilter: UpdateWrapper => Boolean = _ => true,
          synchronizerFilter: Option[SynchronizerId] = None,
      ): Seq[ReassignmentWrapper] = check(FeatureFlag.Testing)({

        val resultFilterWithSynchronizer = synchronizerFilter match {
          case Some(synchronizerId) =>
            (update: UpdateWrapper) =>
              update match {
                case _: ReassignmentWrapper =>
                  resultFilter(update) && update.synchronizerId == synchronizerId.toProtoPrimitive

                case _ => false
              }
          case None => resultFilter
        }

        val observer =
          new RecordingStreamObserver[UpdateWrapper](completeAfter, resultFilterWithSynchronizer)

        val filters: Filters = Filters(
          filterTemplates.map(templateId =>
            CumulativeFilter(
              IdentifierFilter.TemplateFilter(
                TemplateFilter(Some(templateId.toIdentifier), includeCreatedEventBlob = false)
              )
            )
          )
        )

        val updateFormat = UpdateFormat(
          includeReassignments =
            if (partyIds.isEmpty)
              Some(
                EventFormat(
                  filtersByParty = Map.empty,
                  filtersForAnyParty = Some(filters),
                  verbose = false,
                )
              )
            else
              Some(
                EventFormat(
                  filtersByParty = partyIds.map(_.toLf -> filters).toMap,
                  filtersForAnyParty = None,
                  verbose = false,
                )
              ),
          includeTransactions = None,
          includeTopologyEvents = None,
        )

        mkResult(
          subscribe_updates(
            observer = observer,
            updateFormat = updateFormat,
            beginOffsetExclusive = beginOffsetExclusive,
            endOffsetInclusive = endOffsetInclusive,
          ),
          "getUpdates",
          observer,
          timeout,
        ).collect { case reassignment: ReassignmentWrapper => reassignment }
      })

      @Help.Summary("Get topology transactions", FeatureFlag.Testing)
      @Help.Description(
        """This function connects to the update stream for the given parties and collects topology transaction
          |events until either `completeAfter` updates have been received or `timeout` has elapsed.
          |If the party ids seq is empty then the topology transactions for all the parties will be fetched.
          |The returned updates can be filtered to be between the given offsets (default: no filtering).
          |If the participant has been pruned via `pruning.prune` and if `beginOffset` is lower than the pruning offset,
          |this command fails with a `NOT_FOUND` error.
          |If the beginOffset is zero then the participant begin is taken as beginning offset.
          |If the endOffset is None then a continuous stream is returned."""
      )
      def topology_transactions(
          completeAfter: PositiveInt,
          partyIds: Seq[PartyId] = Seq.empty,
          beginOffsetExclusive: Long = 0L,
          endOffsetInclusive: Option[Long] = None,
          timeout: config.NonNegativeDuration = timeouts.ledgerCommand,
          resultFilter: UpdateWrapper => Boolean = _ => true,
          synchronizerFilter: Option[SynchronizerId] = None,
      ): Seq[TopologyTransactionWrapper] = check(FeatureFlag.Testing)({

        val resultFilterWithSynchronizer = synchronizerFilter match {
          case Some(synchronizerId) =>
            (update: UpdateWrapper) =>
              update match {
                case _: TopologyTransactionWrapper =>
                  resultFilter(update) && update.synchronizerId == synchronizerId.toProtoPrimitive

                case _ => false
              }

          case None => resultFilter
        }

        val observer =
          new RecordingStreamObserver[UpdateWrapper](completeAfter, resultFilterWithSynchronizer)
        val updateFormat = UpdateFormat(
          includeTransactions = None,
          includeReassignments = None,
          includeTopologyEvents = Some(
            TopologyFormat(
              includeParticipantAuthorizationEvents = Some(
                ParticipantAuthorizationTopologyFormat(
                  parties = partyIds.map(_.toLf)
                )
              )
            )
          ),
        )

        mkResult(
          subscribe_updates(
            observer = observer,
            updateFormat = updateFormat,
            beginOffsetExclusive = beginOffsetExclusive,
            endOffsetInclusive = endOffsetInclusive,
          ),
          "getUpdates",
          observer,
          timeout,
        ).collect { case wrapper: TopologyTransactionWrapper => wrapper }
      })

      @Help.Summary("Get flat updates", FeatureFlag.Testing)
      @Help.Description(
        """This function connects to the flat update stream for the given transaction filter and collects updates
          |until either `completeAfter` transactions have been received or `timeout` has elapsed.
          |The returned transactions can be filtered to be between the given offsets (default: no filtering).
          |If the participant has been pruned via `pruning.prune` and if `beginOffset` is lower than the pruning offset,
          |this command fails with a `NOT_FOUND` error. If you only need to filter by a set of parties, consider using
          |`flat` instead.
          |If the beginOffset is zero then the participant begin is taken as beginning offset.
          |If the endOffset is None then a continuous stream is returned."""
      )
      def flat_with_tx_filter(
          filter: TransactionFilterProto,
          completeAfter: PositiveInt,
          beginOffsetExclusive: Long = 0L,
          endOffsetInclusive: Option[Long] = None,
          verbose: Boolean = true,
          timeout: config.NonNegativeDuration = timeouts.ledgerCommand,
          resultFilter: UpdateWrapper => Boolean = _ => true,
      ): Seq[UpdateWrapper] = check(FeatureFlag.Testing)({
        val observer = new RecordingStreamObserver[UpdateWrapper](completeAfter, resultFilter)
        mkResult(
          subscribe_flat(observer, filter, beginOffsetExclusive, endOffsetInclusive, verbose),
          "getUpdates",
          observer,
          timeout,
        )
      })

      @Help.Summary("Subscribe to the flat update stream", FeatureFlag.Testing)
      @Help.Description("""This function connects to the flat update stream and passes updates to `observer` until
          |the stream is completed.
          |Only updates for parties in `filter.filterByParty.keys` will be returned.
          |Use `filter = TransactionFilter(Map(myParty.toLf -> Filters()))` to return all updates for `myParty: PartyId`.
          |The returned updates can be filtered to be between the given offsets (default: no filtering).
          |If the participant has been pruned via `pruning.prune` and if `beginOffset` is lower than the pruning offset,
          |this command fails with a `NOT_FOUND` error.
          |If the beginOffset is zero then the participant begin is taken as beginning offset.
          |If the endOffset is None then a continuous stream is returned.""")
      def subscribe_flat(
          observer: StreamObserver[UpdateWrapper],
          filter: TransactionFilterProto,
          beginOffsetExclusive: Long = 0L,
          endOffsetInclusive: Option[Long] = None,
          verbose: Boolean = true,
      ): AutoCloseable =
        check(FeatureFlag.Testing)(
          consoleEnvironment.run {
            ledgerApiCommand(
              LedgerApiCommands.UpdateService.SubscribeFlat(
                observer,
                beginOffsetExclusive,
                endOffsetInclusive,
                filter,
                verbose,
              )
            )
          }
        )

      @Help.Summary("Subscribe to the update stream", FeatureFlag.Testing)
      @Help.Description("""This function connects to the update stream and passes updates to `observer` until the stream
          |is completed.
          |The updates as described in the update format will be returned.
          |Use `EventFormat(Map(myParty.toLf -> Filters()))` to return transactions or reassignments for
          |`myParty: PartyId`.
          |The returned updates can be filtered to be between the given offsets (default: no filtering).
          |If the participant has been pruned via `pruning.prune` and if `beginOffset` is lower than the pruning offset,
          |this command fails with a `NOT_FOUND` error.
          |If the beginOffset is zero then the participant begin is taken as beginning offset.
          |If the endOffset is None then a continuous stream is returned.""")
      def subscribe_updates(
          observer: StreamObserver[UpdateWrapper],
          updateFormat: UpdateFormat,
          beginOffsetExclusive: Long = 0L,
          endOffsetInclusive: Option[Long] = None,
      ): AutoCloseable =
        check(FeatureFlag.Testing)(
          consoleEnvironment.run {
            ledgerApiCommand(
              LedgerApiCommands.UpdateService.SubscribeUpdates(
                observer = observer,
                beginExclusive = beginOffsetExclusive,
                endInclusive = endOffsetInclusive,
                updateFormat = updateFormat,
              )
            )
          }
        )

      @Help.Summary("Starts measuring throughput at the update service", FeatureFlag.Testing)
      @Help.Description(
        """This function will subscribe on behalf of `parties` to the update tree stream and
          |notify various metrics:
          |The metric `<name>.<metricSuffix>` counts the number of update trees emitted.
          |The metric `<name>.<metricSuffix>-tx-node-count` tracks the number of events emitted as part of update trees.
          |The metric `<name>.<metricSuffix>-tx-size` tracks the number of bytes emitted as part of update trees.
          |
          |To stop measuring, you need to close the returned `AutoCloseable`.
          |Use the `onUpdate` parameter to register a callback that is called on every update tree."""
      )
      def start_measuring(
          parties: Set[PartyId],
          metricName: String,
          onUpdate: UpdateTreeWrapper => Unit = _ => (),
      )(implicit consoleEnvironment: ConsoleEnvironment): AutoCloseable =
        check(FeatureFlag.Testing) {

          val observer: StreamObserver[UpdateTreeWrapper] = new StreamObserver[UpdateTreeWrapper] {

            implicit val metricsContext: MetricsContext =
              MetricsContext("measurement" -> metricName)

            val consoleMetrics = consoleEnvironment.environment.metricsRegistry
              .forParticipant(name)
              .consoleThroughput

            override def onNext(tree: UpdateTreeWrapper): Unit = {
              val (s, serializedSize) = tree match {
                case TransactionTreeWrapper(transactionTree) =>
                  transactionTree.rootNodeIds().size.toLong -> transactionTree.serializedSize
                case reassignmentWrapper: ReassignmentWrapper =>
                  1L -> reassignmentWrapper.reassignment.serializedSize
              }
              consoleMetrics.metric.mark(s)
              consoleMetrics.nodeCount.update(s)
              consoleMetrics.transactionSize.update(serializedSize)
              onUpdate(tree)
            }

            override def onError(t: Throwable): Unit = t match {
              case t: StatusRuntimeException =>
                val err = GrpcError("start_measuring", name, t)
                err match {
                  case gaveUp: GrpcError.GrpcClientGaveUp if gaveUp.isClientCancellation =>
                    logger.info(s"Client cancelled measuring throughput (metric: $metricName).")
                  case _ =>
                    logger.warn(
                      s"An error occurred while measuring throughput (metric: $metricName). Stop measuring. $err"
                    )
                }
              case _: Throwable =>
                logger.warn(
                  s"An exception occurred while measuring throughput (metric: $metricName). Stop measuring.",
                  t,
                )
            }

            override def onCompleted(): Unit =
              logger.info(s"Stop measuring throughput (metric: $metricName).")
          }

          val filterParty = TransactionFilterProto(parties.map(_.toLf -> Filters(Nil)).toMap, None)

          logger.info(s"Start measuring throughput (metric: $metricName).")
          subscribe_trees(
            observer,
            filterParty,
            state.end(),
            verbose = false,
          )
        }

      @Help.Summary("Get a (tree) transaction by its ID", FeatureFlag.Testing)
      @Help.Description(
        """Get a transaction tree from the update stream by its ID. Returns None if the transaction is not (yet)
          |known at the participant or if the transaction has been pruned via `pruning.prune`."""
      )
      def by_id(parties: Set[PartyId], id: String): Option[TransactionTreeProto] =
        check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.UpdateService.GetTransactionById(parties.map(_.toLf), id)(
              consoleEnvironment.environment.executionContext
            )
          )
        })

      @Help.Summary("Get a transaction tree by its offset", FeatureFlag.Testing)
      @Help.Description(
        """Get a transaction tree from the update stream by its offset. Returns None if the transaction is not (yet)
          |known at the participant or if the transaction has been pruned via `pruning.prune`."""
      )
      def by_offset(parties: Set[PartyId], offset: Long): Option[TransactionTreeProto] =
        check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.UpdateService.GetTransactionByOffset(parties.map(_.toLf), offset)(
              consoleEnvironment.environment.executionContext
            )
          )
        })

      @Help.Summary("Get an update by its ID", FeatureFlag.Testing)
      @Help.Description(
        """Get an update by its ID. Returns None if the update is not (yet) known at the participant or all the events
          |of the update are filtered due to the update format or if the update has been pruned via `pruning.prune`."""
      )
      def update_by_id(id: String, updateFormat: UpdateFormat): Option[UpdateWrapper] =
        check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.UpdateService.GetUpdateById(id, updateFormat)(
              consoleEnvironment.environment.executionContext
            )
          )
        })

      @Help.Summary("Get an update by its offset", FeatureFlag.Testing)
      @Help.Description(
        """Get an update by its offset. Returns None if the update is not (yet) known at the participant or all the
          |events of the update are filtered due to the update format or if the update has been pruned via
          |`pruning.prune`."""
      )
      def update_by_offset(
          offset: Long,
          updateFormat: UpdateFormat,
      ): Option[UpdateWrapper] =
        check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.UpdateService.GetUpdateByOffset(offset, updateFormat)(
              consoleEnvironment.environment.executionContext
            )
          )
        })

    }

    @Help.Summary("Interactive submission", FeatureFlag.Testing)
    @Help.Group("Interactive Submission")
    object interactive_submission extends Helpful {

      @Help.Summary(
        """Prepare a transaction for interactive submission
           Note that the hash in the response is provided for convenience. Callers should re-compute the hash
           of the transactions (and possibly compare it to the provided one) before signing it.
          """
      )
      @Help.Description(
        """Prepare a transaction for interactive submission.
          |Similar to submit, except instead of submitting the transaction to the network,
          |a serialized version of the transaction will be returned, along with a hash.
          |This allows non-hosted parties to sign the hash with they private key before submitting it via the
          |execute command. If you wish to directly submit a command instead without the external signing step,
          |use submit instead."""
      )
      def prepare(
          actAs: Seq[PartyId],
          commands: Seq[Command],
          synchronizerId: Option[SynchronizerId] = None,
          commandId: String = UUID.randomUUID().toString,
          minLedgerTimeAbs: Option[Instant] = None,
          readAs: Seq[PartyId] = Seq.empty,
          disclosedContracts: Seq[DisclosedContract] = Seq.empty,
          userId: String = userId,
          userPackageSelectionPreference: Seq[LfPackageId] = Seq.empty,
          verboseHashing: Boolean = false,
          prefetchContractKeys: Seq[PrefetchContractKey] = Seq.empty,
      ): PrepareResponseProto =
        consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.InteractiveSubmissionService.PrepareCommand(
              actAs.map(_.toLf),
              readAs.map(_.toLf),
              commands,
              commandId,
              minLedgerTimeAbs,
              disclosedContracts,
              synchronizerId,
              userId,
              userPackageSelectionPreference,
              verboseHashing,
              prefetchContractKeys,
            )
          )
        }

      @Help.Summary(
        "Execute a prepared submission"
      )
      @Help.Description(
        """
          preparedTransaction: the prepared transaction bytestring, typically obtained from the preparedTransaction field of the [[prepare]] response.
          transactionSignatures: the signatures of the hash of the transaction. The hash is typically obtained from the preparedTransactionHash field of the [[prepare]] response.
            Note however that the caller should re-compute the hash and ensure it matches the one provided in [[prepare]], to be certain they're signing a hash that correctly represents
            the transaction they want to submit.
          """
      )
      def execute(
          preparedTransaction: PreparedTransaction,
          transactionSignatures: Map[PartyId, Seq[Signature]],
          submissionId: String,
          hashingSchemeVersion: HashingSchemeVersion,
          userId: String = userId,
          deduplicationPeriod: Option[DeduplicationPeriod] = None,
          minLedgerTimeAbs: Option[Instant] = None,
      ): ExecuteResponseProto =
        consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.InteractiveSubmissionService.ExecuteCommand(
              preparedTransaction,
              transactionSignatures,
              submissionId = submissionId,
              userId = userId,
              deduplicationPeriod = deduplicationPeriod,
              minLedgerTimeAbs = minLedgerTimeAbs,
              hashingSchemeVersion = hashingSchemeVersion,
            )
          )
        }

      @Help.Summary("Get the preferred package version for constructing a command submission")
      @Help.Description(
        """A preferred package is the highest-versioned package for a provided package-name
           that is vetted by all the participants hosting the provided parties.
           Ledger API clients should use this endpoint for constructing command submissions
           that are compatible with the provided preferred package, by making informed decisions on:
             - which are the compatible packages that can be used to create contracts
             - which contract or exercise choice argument version can be used in the command
             - which choices can be executed on a template or interface of a contract
           parties: The parties whose vetting state should be considered when computing the preferred package
           packageName: The package name for which the preferred package is requested
           synchronizerId: The synchronizer whose topology state to use for resolving this query.
                           If not specified. the topology state of all the synchronizers the participant is connected to will be used.
           vettingValidAt: The timestamp at which the package vetting validity should be computed
                           If not provided, the participant's current clock time is used.
          """
      )
      def preferred_package_version(
          parties: Set[PartyId],
          packageName: LfPackageName,
          synchronizerId: Option[SynchronizerId] = None,
          vettingValidAt: Option[CantonTimestamp] = None,
      ): Option[PackagePreference] =
        consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.InteractiveSubmissionService
              .PreferredPackageVersion(
                parties.map(_.toLf),
                packageName,
                synchronizerId,
                vettingValidAt,
              )
          )
        }

      @Help.Summary("Get the preferred packages for constructing a command submission")
      @Help.Description(
        """A preferred package is the highest-versioned package for a provided package-name
           that is vetted by all the participants hosting the provided parties.
           Ledger API clients should use this endpoint for constructing command submissions
           that are compatible with the provided preferred package, by making informed decisions on:
             - which are the compatible packages that can be used to create contracts
             - which contract or exercise choice argument version can be used in the command
             - which choices can be executed on a template or interface of a contract

           Generally it is enough to provide the requirements for the command's root package-names.
           Additional package-name requirements can be provided when additional informees need to use
           package dependencies of the command's root packages.

           parties: The parties whose vetting state should be considered when computing the preferred package
           packageName: The package name for which the preferred package is requested
           synchronizerId: The synchronizer whose topology state to use for resolving this query.
                           If not specified. the topology state of all the synchronizers the participant is connected to will be used.
           vettingValidAt: The timestamp at which the package vetting validity should be computed
                           If not provided, the participant's current clock time is used.
          """
      )
      def preferred_packages(
          packageVettingRequirements: Map[LfPackageName, Set[PartyId]],
          synchronizerId: Option[SynchronizerId] = None,
          vettingValidAt: Option[CantonTimestamp] = None,
      ): GetPreferredPackagesResponse =
        consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.InteractiveSubmissionService
              .PreferredPackages(
                packageVettingRequirements.view.mapValues(_.map(_.toLf)).toMap,
                synchronizerId,
                vettingValidAt,
              )
          )
        }
    }

    @Help.Summary("Submit commands", FeatureFlag.Testing)
    @Help.Group("Command Submission")
    object commands extends Helpful {

      @Help.Summary(
        "Submit command and wait for the resulting transaction, returning the transaction tree or failing otherwise"
      )
      @Help.Description(
        """Submits a command on behalf of the `actAs` parties, waits for the resulting transaction to commit and returns it.
          | If the timeout is set, it also waits for the transaction to appear at all other configured
          | participants who were involved in the transaction. The call blocks until the transaction commits or fails;
          | the timeout only specifies how long to wait at the other participants.
          | Fails if the transaction doesn't commit, or if it doesn't become visible to the involved participants in
          | the allotted time.
          | Note that if the optTimeout is set and the involved parties are concurrently enabled/disabled or their
          | participants are connected/disconnected, the command may currently result in spurious timeouts or may
          | return before the transaction appears at all the involved participants."""
      )
      def submit(
          actAs: Seq[PartyId],
          commands: Seq[Command],
          synchronizerId: Option[SynchronizerId] = None,
          workflowId: String = "",
          commandId: String = "",
          optTimeout: Option[config.NonNegativeDuration] = Some(timeouts.ledgerCommand),
          deduplicationPeriod: Option[DeduplicationPeriod] = None,
          submissionId: String = "",
          minLedgerTimeAbs: Option[Instant] = None,
          readAs: Seq[PartyId] = Seq.empty,
          disclosedContracts: Seq[DisclosedContract] = Seq.empty,
          userId: String = userId,
          userPackageSelectionPreference: Seq[LfPackageId] = Seq.empty,
      ): TransactionTreeProto = {
        val tx = consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.CommandService.SubmitAndWaitTransactionTree(
              actAs.map(_.toLf),
              readAs.map(_.toLf),
              commands,
              workflowId,
              commandId,
              deduplicationPeriod,
              submissionId,
              minLedgerTimeAbs,
              disclosedContracts,
              synchronizerId,
              userId,
              userPackageSelectionPreference,
            )
          )
        }
        optionallyAwait(tx, tx.updateId, tx.synchronizerId, optTimeout)
      }

      @Help.Summary(
        "Submit command and wait for the resulting transaction, returning the flattened transaction or failing otherwise"
      )
      @Help.Description(
        """Submits a command on behalf of the `actAs` parties, waits for the resulting transaction to commit, and returns the "flattened" transaction.
          | If the timeout is set, it also waits for the transaction to appear at all other configured
          | participants who were involved in the transaction. The call blocks until the transaction commits or fails;
          | the timeout only specifies how long to wait at the other participants.
          | Fails if the transaction doesn't commit, or if it doesn't become visible to the involved participants in
          | the allotted time.
          | Note that if the optTimeout is set and the involved parties are concurrently enabled/disabled or their
          | participants are connected/disconnected, the command may currently result in spurious timeouts or may
          | return before the transaction appears at all the involved participants."""
      )
      def submit_flat(
          actAs: Seq[PartyId],
          commands: Seq[Command],
          synchronizerId: Option[SynchronizerId] = None,
          workflowId: String = "",
          commandId: String = "",
          optTimeout: Option[config.NonNegativeDuration] = Some(timeouts.ledgerCommand),
          deduplicationPeriod: Option[DeduplicationPeriod] = None,
          submissionId: String = "",
          minLedgerTimeAbs: Option[Instant] = None,
          readAs: Seq[PartyId] = Seq.empty,
          disclosedContracts: Seq[DisclosedContract] = Seq.empty,
          userId: String = userId,
          userPackageSelectionPreference: Seq[LfPackageId] = Seq.empty,
      ): ApiTransaction = {
        val tx = consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.CommandService.SubmitAndWaitTransaction(
              actAs.map(_.toLf),
              readAs.map(_.toLf),
              commands,
              workflowId,
              commandId,
              deduplicationPeriod,
              submissionId,
              minLedgerTimeAbs,
              disclosedContracts,
              synchronizerId,
              userId,
              userPackageSelectionPreference,
            )
          )
        }
        optionallyAwait(tx, tx.updateId, tx.synchronizerId, optTimeout)
      }

      @Help.Summary("Submit command asynchronously", FeatureFlag.Testing)
      @Help.Description(
        """Provides access to the command submission service of the Ledger API.
          |See https://docs.daml.com/app-dev/services.html for documentation of the parameters."""
      )
      def submit_async(
          actAs: Seq[PartyId],
          commands: Seq[Command],
          synchronizerId: Option[SynchronizerId] = None,
          workflowId: String = "",
          commandId: String = "",
          deduplicationPeriod: Option[DeduplicationPeriod] = None,
          submissionId: String = "",
          minLedgerTimeAbs: Option[Instant] = None,
          readAs: Seq[PartyId] = Seq.empty,
          disclosedContracts: Seq[DisclosedContract] = Seq.empty,
          userId: String = userId,
          userPackageSelectionPreference: Seq[LfPackageId] = Seq.empty,
      ): Unit = check(FeatureFlag.Testing) {
        consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.CommandSubmissionService.Submit(
              actAs.map(_.toLf),
              readAs.map(_.toLf),
              commands,
              workflowId,
              commandId,
              deduplicationPeriod,
              submissionId,
              minLedgerTimeAbs,
              disclosedContracts,
              synchronizerId,
              userId,
              userPackageSelectionPreference,
            )
          )
        }
      }

      @Help.Summary("Investigate successful and failed commands", FeatureFlag.Testing)
      @Help.Description(
        """Find the status of commands. Note that only recent commands which are kept in memory will be returned."""
      )
      def status(
          commandIdPrefix: String = "",
          state: CommandState = CommandState.COMMAND_STATE_UNSPECIFIED,
          limit: PositiveInt = PositiveInt.tryCreate(10),
      ): Seq[CommandStatus] = check(FeatureFlag.Preview) {
        consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.CommandInspectionService.GetCommandStatus(
              commandIdPrefix = commandIdPrefix,
              state = state,
              limit = limit.unwrap,
            )
          )
        }
      }

      @Help.Summary("Investigate failed commands", FeatureFlag.Testing)
      @Help.Description(
        """Same as status(..., state = CommandState.Failed)."""
      )
      def failed(commandId: String = "", limit: PositiveInt = PositiveInt.tryCreate(10)): Seq[
        CommandStatus
      ] = check(FeatureFlag.Preview) {
        status(commandId, CommandState.COMMAND_STATE_FAILED, limit)
      }

      @Help.Summary(
        "Submit assign command and wait for the resulting reassignment, returning the reassignment or failing otherwise",
        FeatureFlag.Testing,
      )
      @Help.Description(
        """Submits an assignment command on behalf of `submitter` party, waits for the resulting assignment to commit, and returns the reassignment.
          | If waitForParticipants is set, it also waits for the reassignment(s) to appear at all other configured
          | participants who were involved in the assignment. The call blocks until the assignment commits or fails.
          | Fails if the assignment doesn't commit, or if it doesn't become visible to the involved participants in time.
          | Timout specifies the time how long to wait until the reassignment appears in the update stream for the submitting and all the specified participants.
          | The unassignId should be the one returned by the corresponding submit_unassign command."""
      )
      def submit_assign(
          submitter: PartyId,
          unassignId: String,
          source: SynchronizerId,
          target: SynchronizerId,
          workflowId: String = "",
          userId: String = userId,
          submissionId: String = UUID.randomUUID().toString,
          eventFormat: Option[EventFormat] = eventFormatAllParties,
          waitForParticipants: Map[ParticipantReference, PartyId] = Map.empty,
          timeout: config.NonNegativeDuration = timeouts.ledgerCommand,
      ): AssignedWrapper =
        submitReassignments(submitter, waitForParticipants, timeout)(commandId =>
          consoleEnvironment.run(
            ledgerApiCommand(
              LedgerApiCommands.CommandService.SubmitAndWaitAssign(
                submitter = submitter.toLf,
                unassignId = unassignId,
                source = source,
                target = target,
                workflowId = workflowId,
                userId = userId,
                commandId = commandId,
                submissionId = submissionId,
                eventFormat = eventFormat,
              )
            )
          )
        ) match {
          case assigned: AssignedWrapper => assigned
          case invalid =>
            throw new IllegalStateException(s"AssignedWrapper expected, but got: $invalid")
        }

      @Help.Summary(
        "Submit unassign command and wait for the resulting reassignment, returning the reassignment or failing otherwise",
        FeatureFlag.Testing,
      )
      @Help.Description(
        """Submits an unassignment command on behalf of `submitter` party, waits for the resulting unassignment to commit, and returns the reassignment.
          | If waitForParticipants is set, it also waits for the reassignment(s) to appear at all other configured
          | participants who were involved in the unassignment. The call blocks until the unassignment commits or fails.
          | Fails if the unassignment doesn't commit, or if it doesn't become visible to the involved participants in time.
          | Timout specifies the time how long to wait until the reassignment appears in the update stream for the submitting and all the specified participants."""
      )
      def submit_unassign(
          submitter: PartyId,
          contractIds: Seq[LfContractId],
          source: SynchronizerId,
          target: SynchronizerId,
          workflowId: String = "",
          userId: String = userId,
          submissionId: String = UUID.randomUUID().toString,
          eventFormat: Option[EventFormat] = eventFormatAllParties,
          waitForParticipants: Map[ParticipantReference, PartyId] = Map.empty,
          timeout: config.NonNegativeDuration = timeouts.ledgerCommand,
      ): UnassignedWrapper =
        submitReassignments(submitter, waitForParticipants, timeout)(commandId =>
          consoleEnvironment.run(
            ledgerApiCommand(
              LedgerApiCommands.CommandService.SubmitAndWaitUnassign(
                submitter = submitter.toLf,
                contractIds = contractIds,
                source = source,
                target = target,
                workflowId = workflowId,
                userId = userId,
                commandId = commandId,
                submissionId = submissionId,
                eventFormat = eventFormat,
              )
            )
          )
        ) match {
          case unassigned: UnassignedWrapper => unassigned
          case invalid =>
            throw new IllegalStateException(s"UnassignedWrapper expected, but got: $invalid")
        }

      @Help.Summary(
        "Combines `submit_unassign` and `submit_assign` in a single macro",
        FeatureFlag.Testing,
      )
      @Help.Description(
        """See `submit_unassign` and `submit_assign` for the parameters."""
      )
      def submit_reassign(
          submitter: PartyId,
          contractIds: Seq[LfContractId],
          source: SynchronizerId,
          target: SynchronizerId,
          workflowId: String = "",
          userId: String = userId,
          submissionId: String = UUID.randomUUID().toString,
          eventFormat: Option[EventFormat] = eventFormatAllParties,
          waitForParticipants: Map[ParticipantReference, PartyId] = Map.empty,
          timeout: config.NonNegativeDuration = timeouts.ledgerCommand,
      ): (UnassignedWrapper, AssignedWrapper) = {
        val unassigned = submit_unassign(
          submitter,
          contractIds,
          source,
          target,
          workflowId,
          userId,
          submissionId,
          eventFormat,
          waitForParticipants,
          timeout,
        )
        val assigned = submit_assign(
          submitter,
          unassigned.unassignId,
          source,
          target,
          workflowId,
          userId,
          submissionId,
          eventFormat,
          waitForParticipants,
          timeout,
        )
        (unassigned, assigned)
      }

      private def submitReassignments(
          submitter: PartyId,
          waitForParticipants: Map[ParticipantReference, PartyId],
          timeout: config.NonNegativeDuration,
      )(submit: String => ReassignmentWrapper): ReassignmentWrapper = {
        val commandId = UUID.randomUUID().toString
        val ledgerEndBefore = state.end()
        val participants = waitForParticipants.view.map { case (participant, partyId) =>
          participant -> (partyId, participant.ledger_api.state.end())
        }.toMap
        val reassignment = submit(commandId)
        val reassignmentUpdateId = reassignment.updateId
        participants.foreach { case (participant, (queryingParty, from)) =>
          discard(waitForUpdateId(participant, from, queryingParty, reassignmentUpdateId, timeout))
        }
        discard(
          waitForUpdateId(
            thisAdministration,
            ledgerEndBefore,
            submitter,
            reassignmentUpdateId,
            timeout,
          ) match {
            case result: ReassignmentWrapper => result
            case _ => throw new IllegalStateException("ReassignmentWrapper expected")
          }
        )
        reassignment
      }

      @Help.Summary("Submit assign command asynchronously", FeatureFlag.Testing)
      @Help.Description(
        """Provides access to the command submission service of the Ledger API.
          |See https://docs.daml.com/app-dev/services.html for documentation of the parameters."""
      )
      def submit_assign_async(
          submitter: PartyId,
          unassignId: String,
          source: SynchronizerId,
          target: SynchronizerId,
          workflowId: String = "",
          userId: String = userId,
          commandId: String = UUID.randomUUID().toString,
          submissionId: String = UUID.randomUUID().toString,
      ): Unit = check(FeatureFlag.Testing) {
        consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.CommandSubmissionService.SubmitAssignCommand(
              workflowId = workflowId,
              userId = userId,
              commandId = commandId,
              submitter = submitter.toLf,
              submissionId = submissionId,
              unassignId = unassignId,
              source = source,
              target = target,
            )
          )
        }
      }

      @Help.Summary("Submit unassign command asynchronously", FeatureFlag.Testing)
      @Help.Description(
        """Provides access to the command submission service of the Ledger API.
          |See https://docs.daml.com/app-dev/services.html for documentation of the parameters."""
      )
      def submit_unassign_async(
          submitter: PartyId,
          contractIds: Seq[LfContractId],
          source: SynchronizerId,
          target: SynchronizerId,
          workflowId: String = "",
          userId: String = userId,
          commandId: String = UUID.randomUUID().toString,
          submissionId: String = UUID.randomUUID().toString,
      ): Unit = check(FeatureFlag.Testing) {
        consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.CommandSubmissionService.SubmitUnassignCommand(
              workflowId = workflowId,
              userId = userId,
              commandId = commandId,
              submitter = submitter.toLf,
              submissionId = submissionId,
              contractIds = contractIds,
              source = source,
              target = target,
            )
          )
        }
      }
    }

    @Help.Summary("Collection of Ledger API state endpoints", FeatureFlag.Testing)
    @Help.Group("State")
    object state extends Helpful {

      @Help.Summary("Read the current ledger end offset", FeatureFlag.Testing)
      def end(): Long =
        check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.StateService.LedgerEnd()
          )
        })

      @Help.Summary("Read the current connected synchronizers for a party", FeatureFlag.Testing)
      def connected_synchronizers(partyId: PartyId): GetConnectedSynchronizersResponse =
        check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.StateService.GetConnectedSynchronizers(partyId.toLf)
          )
        })

      @Help.Summary("Investigate successful and failed commands", FeatureFlag.Testing)
      @Help.Description(
        """Find the status of commands. Note that only recent commands which are kept in memory will be returned."""
      )
      def status(
          commandIdPrefix: String = "",
          state: CommandState = CommandState.COMMAND_STATE_UNSPECIFIED,
          limit: PositiveInt = PositiveInt.tryCreate(10),
      ): Seq[CommandStatus] = check(FeatureFlag.Preview) {
        consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.CommandInspectionService.GetCommandStatus(
              commandIdPrefix = commandIdPrefix,
              state = state,
              limit = limit.unwrap,
            )
          )
        }
      }

      @Help.Summary("Investigate failed commands", FeatureFlag.Testing)
      @Help.Description(
        """Same as status(..., state = CommandState.Failed)."""
      )
      def failed(commandId: String = "", limit: PositiveInt = PositiveInt.tryCreate(10)): Seq[
        CommandStatus
      ] = check(FeatureFlag.Preview) {
        status(commandId, CommandState.COMMAND_STATE_FAILED, limit)
      }

      @Help.Summary("Read active contracts", FeatureFlag.Testing)
      @Help.Group("Active Contracts")
      object acs extends Helpful {
        @Help.Summary("List the set of active contract entries of a given party")
        @Help.Description(
          """This command will return the current set of active contracts and incomplete reassignments for the given party.
            |
            |Supported arguments:
            |- party: for which party you want to load the acs
            |- limit: limit (default set via canton.parameter.console)
            |- verbose: whether the resulting events should contain detailed type information
            |- filterTemplate: list of templates ids to filter for, empty sequence acts as a wildcard
            |- filterTemplate: list of interface ids to filter for, empty sequence acts as a wildcard
            |- activeAtOffsetO: the offset at which the snapshot of the active contracts will be computed, it
            |  must be no greater than the current ledger end offset and must be greater than or equal to the
            |  last pruning offset. If no offset is specified then the current participant end will be used.
            |- timeout: the maximum wait time for the complete acs to arrive
            |- includeCreatedEventBlob: whether the result should contain the createdEventBlobs, it works only
            |  if the filterTemplate is non-empty
            |- resultFilter: custom filter of the results, applies before limit"""
        )
        def of_party(
            party: PartyId,
            limit: PositiveInt = defaultLimit,
            verbose: Boolean = true,
            filterTemplates: Seq[TemplateId] = Seq.empty,
            filterInterfaces: Seq[Identifier] = Seq.empty,
            activeAtOffsetO: Option[Long] = None,
            timeout: config.NonNegativeDuration = timeouts.unbounded,
            includeCreatedEventBlob: Boolean = false,
            resultFilter: GetActiveContractsResponse => Boolean = _.contractEntry.isDefined,
        ): Seq[WrappedContractEntry] = {
          val observer =
            new RecordingStreamObserver[GetActiveContractsResponse](limit, resultFilter)
          val activeAt =
            activeAtOffsetO match {
              case None =>
                consoleEnvironment.run {
                  ledgerApiCommand(
                    LedgerApiCommands.StateService.LedgerEnd()
                  )
                }
              case Some(offset) => offset
            }
          mkResult(
            consoleEnvironment.run {
              ledgerApiCommand(
                LedgerApiCommands.StateService.GetActiveContracts(
                  observer,
                  Set(party.toLf),
                  limit,
                  filterTemplates,
                  filterInterfaces,
                  activeAt,
                  verbose,
                  timeout.asFiniteApproximation,
                  includeCreatedEventBlob,
                )
              )
            },
            "getActiveContracts",
            observer,
            timeout,
          ).map(activeContract => WrappedContractEntry(activeContract.contractEntry))
        }

        @Help.Summary("List the set of active contracts of a given party")
        @Help.Description(
          """This command will return the current set of active contracts for the given party.
            |
            |Supported arguments:
            |- party: for which party you want to load the acs
            |- limit: limit (default set via canton.parameter.console)
            |- verbose: whether the resulting events should contain detailed type information
            |- filterTemplate: list of templates ids to filter for, empty sequence acts as a wildcard
            |- activeAtOffsetO: the offset at which the snapshot of the active contracts will be computed, it
            |  must be no greater than the current ledger end offset and must be greater than or equal to the
            |  last pruning offset. If no offset is specified then the current participant end will be used.
            |- timeout: the maximum wait time for the complete acs to arrive
            |- includeCreatedEventBlob: whether the result should contain the createdEventBlobs, it works only
            |  if the filterTemplate is non-empty"""
        )
        def active_contracts_of_party(
            party: PartyId,
            limit: PositiveInt = defaultLimit,
            verbose: Boolean = true,
            filterTemplates: Seq[TemplateId] = Seq.empty,
            filterInterfaces: Seq[Identifier] = Seq.empty,
            activeAtOffsetO: Option[Long] = None,
            timeout: config.NonNegativeDuration = timeouts.unbounded,
            includeCreatedEventBlob: Boolean = false,
        ): Seq[ActiveContract] =
          of_party(
            party,
            limit,
            verbose,
            filterTemplates,
            filterInterfaces,
            activeAtOffsetO,
            timeout,
            includeCreatedEventBlob,
            _.contractEntry.isActiveContract,
          )
            .flatMap(_.entry.activeContract)

        @Help.Summary("List the set of incomplete unassigned events of a given party")
        @Help.Description(
          """This command will return the current set of incomplete unassigned events for the given party.
            |
            |Supported arguments:
            |- party: for which party you want to load the acs
            |- limit: limit (default set via canton.parameter.console)
            |- verbose: whether the resulting events should contain detailed type information
            |- filterTemplate: list of templates ids to filter for, empty sequence acts as a wildcard
            |- activeAtOffsetO: the offset at which the snapshot of the events will be computed, it
            |  must be no greater than the current ledger end offset and must be greater than or equal to the
            |  last pruning offset. If no offset is specified then the current participant end will be used.
            |- timeout: the maximum wait time for the complete acs to arrive
            |- includeCreatedEventBlob: whether the result should contain the createdEventBlobs, it works only
            |  if the filterTemplate is non-empty"""
        )
        def incomplete_unassigned_of_party(
            party: PartyId,
            limit: PositiveInt = defaultLimit,
            verbose: Boolean = true,
            filterTemplates: Seq[TemplateId] = Seq.empty,
            filterInterfaces: Seq[Identifier] = Seq.empty,
            activeAtOffsetO: Option[Long] = None,
            timeout: config.NonNegativeDuration = timeouts.unbounded,
            includeCreatedEventBlob: Boolean = false,
        ): Seq[WrappedIncompleteUnassigned] =
          of_party(
            party,
            limit,
            verbose,
            filterTemplates,
            filterInterfaces,
            activeAtOffsetO,
            timeout,
            includeCreatedEventBlob,
            _.contractEntry.isIncompleteUnassigned,
          )
            .flatMap(_.entry.incompleteUnassigned)
            .map(WrappedIncompleteUnassigned(_))

        @Help.Summary("List the set of incomplete assigned events of a given party")
        @Help.Description(
          """This command will return the current set of incomplete assigned events for the given party.
            |
            |Supported arguments:
            |- party: for which party you want to load the acs
            |- limit: limit (default set via canton.parameter.console)
            |- verbose: whether the resulting events should contain detailed type information
            |- filterTemplate: list of templates ids to filter for, empty sequence acts as a wildcard
            |- activeAtOffsetO: the offset at which the snapshot of the events will be computed, it must be no
            |  greater than the current ledger end offset and must be greater than or equal to the last
            |  pruning offset. If no offset is specified then the current participant end will be used.
            |- timeout: the maximum wait time for the complete acs to arrive
            |- includeCreatedEventBlob: whether the result should contain the createdEventBlobs, it works only
            |  if the filterTemplate is non-empty"""
        )
        def incomplete_assigned_of_party(
            party: PartyId,
            limit: PositiveInt = defaultLimit,
            verbose: Boolean = true,
            filterTemplates: Seq[TemplateId] = Seq.empty,
            filterInterfaces: Seq[Identifier] = Seq.empty,
            activeAtOffsetO: Option[Long] = None,
            timeout: config.NonNegativeDuration = timeouts.unbounded,
            includeCreatedEventBlob: Boolean = false,
        ): Seq[WrappedIncompleteAssigned] =
          of_party(
            party,
            limit,
            verbose,
            filterTemplates,
            filterInterfaces,
            activeAtOffsetO,
            timeout,
            includeCreatedEventBlob,
            _.contractEntry.isIncompleteAssigned,
          )
            .flatMap(_.entry.incompleteAssigned)
            .map(WrappedIncompleteAssigned(_))

        @Help.Summary(
          "List the set of active contracts for all parties hosted on this participant"
        )
        @Help.Description(
          """This command will return the current set of active contracts for all parties.

             Supported arguments:
             - limit: limit (default set via canton.parameter.console)
             - verbose: whether the resulting events should contain detailed type information
             - filterTemplate: list of templates ids to filter for, empty sequence acts as a wildcard
             - activeAtOffsetO: the offset at which the snapshot of the active contracts will be computed, it
               must be no greater than the current ledger end offset and must be greater than or equal to the
               last pruning offset. If no offset is specified then the current participant end will be used.
             - timeout: the maximum wait time for the complete acs to arrive
             - identityProviderId: limit the response to parties governed by the given identity provider
             - includeCreatedEventBlob: whether the result should contain the createdEventBlobs, it works only
               if the filterTemplate is non-empty
             - resultFilter: custom filter of the results, applies before limit
          """
        )
        def of_all(
            limit: PositiveInt = defaultLimit,
            verbose: Boolean = true,
            filterTemplates: Seq[TemplateId] = Seq.empty,
            filterInterfaces: Seq[Identifier] = Seq.empty,
            activeAtOffsetO: Option[Long] = None,
            timeout: config.NonNegativeDuration = timeouts.unbounded,
            identityProviderId: String = "",
            includeCreatedEventBlob: Boolean = false,
            resultFilter: GetActiveContractsResponse => Boolean = _.contractEntry.isDefined,
        ): Seq[WrappedContractEntry] =
          consoleEnvironment.runE {
            for {
              parties <- ledgerApiCommand(
                LedgerApiCommands.PartyManagementService.ListKnownParties(
                  identityProviderId = identityProviderId
                )
              ).toEither
              localParties <- parties.filter(_.isLocal).map(_.party).traverse(LfPartyId.fromString)
              res <- {
                if (localParties.isEmpty) Right(Seq.empty)
                else {
                  val observer = new RecordingStreamObserver[GetActiveContractsResponse](
                    limit,
                    resultFilter,
                  )
                  Try(
                    mkResult(
                      consoleEnvironment.run {
                        ledgerApiCommand(
                          LedgerApiCommands.StateService.GetActiveContracts(
                            observer,
                            localParties.toSet,
                            limit,
                            filterTemplates,
                            filterInterfaces,
                            activeAtOffsetO.getOrElse(end()),
                            verbose,
                            timeout.asFiniteApproximation,
                            includeCreatedEventBlob,
                          )
                        )
                      },
                      "getActiveContracts",
                      observer,
                      timeout,
                    ).map(activeContract => WrappedContractEntry(activeContract.contractEntry))
                  ).toEither.left.map(_.getMessage)
                }
              }
            } yield res
          }

        @Help.Summary(
          "Wait until the party sees the given contract in the active contract service",
          FeatureFlag.Testing,
        )
        @Help.Description(
          "Will throw an exception if the contract is not found to be active within the given timeout"
        )
        def await_active_contract(
            party: PartyId,
            contractId: LfContractId,
            timeout: config.NonNegativeDuration = timeouts.ledgerCommand,
        ): Unit = check(FeatureFlag.Testing) {
          ConsoleMacros.utils.retry_until_true(timeout) {
            of_party(party, verbose = false)
              .exists(_.contractId == contractId.coid)
          }
        }

        @Help.Summary("Generic search for contracts")
        @Help.Description(
          """This search function returns an untyped ledger-api event.
            |The find will wait until the contract appears or throw an exception once it times out."""
        )
        def find_generic(
            partyId: PartyId,
            filter: WrappedContractEntry => Boolean,
            timeout: config.NonNegativeDuration = timeouts.ledgerCommand,
        ): WrappedContractEntry = {
          def scan: Option[WrappedContractEntry] = of_party(partyId).find(filter(_))

          ConsoleMacros.utils.retry_until_true(timeout)(scan.isDefined)
          consoleEnvironment.runE {
            scan.toRight(s"Failed to find contract for $partyId.")
          }
        }
      }
    }

    @Help.Summary("Manage parties through the Ledger API", FeatureFlag.Testing)
    @Help.Group("Party Management")
    object parties extends Helpful {

      @Help.Summary("Allocate a new party", FeatureFlag.Testing)
      @Help.Description(
        """Allocates a new party on the ledger.
          party: a hint for generating the party identifier
          annotations: key-value pairs associated with this party and stored locally on this Ledger API server
          identityProviderId: identity provider id"""
      )
      def allocate(
          party: String,
          annotations: Map[String, String] = Map.empty,
          identityProviderId: String = "",
      ): PartyDetails = {
        val proto = check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.PartyManagementService.AllocateParty(
              partyIdHint = party,
              annotations = annotations,
              identityProviderId = identityProviderId,
            )
          )
        })
        PartyDetails.fromProtoPartyDetails(proto)
      }

      @Help.Summary("List parties known by the Ledger API server", FeatureFlag.Testing)
      @Help.Description(
        """Lists parties known by the Ledger API server.
           identityProviderId: identity provider id"""
      )
      def list(identityProviderId: String = ""): Seq[PartyDetails] = {
        val proto = check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.PartyManagementService.ListKnownParties(
              identityProviderId = identityProviderId
            )
          )
        })
        proto.map(PartyDetails.fromProtoPartyDetails)
      }

      @Help.Summary("Update participant-local party details")
      @Help.Description(
        """Currently you can update only the annotations.
           |You cannot update other user attributes.
          party: party to be updated,
          modifier: a function to modify the party details, e.g.: `partyDetails => { partyDetails.copy(annotations = partyDetails.annotations.updated("a", "b").removed("c")) }`
          identityProviderId: identity provider id"""
      )
      def update(
          party: PartyId,
          modifier: PartyDetails => PartyDetails,
          identityProviderId: String = "",
      ): PartyDetails = {
        val rawDetails = get(party = party)
        val srcDetails = PartyDetails.fromProtoPartyDetails(rawDetails)
        val modifiedDetails = modifier(srcDetails)
        verifyOnlyModifiableFieldsWhereModified(srcDetails, modifiedDetails)
        val annotationsUpdate = makeAnnotationsUpdate(
          original = srcDetails.annotations,
          modified = modifiedDetails.annotations,
        )
        val rawUpdatedDetails = check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.PartyManagementService.Update(
              party = party,
              annotationsUpdate = Some(annotationsUpdate),
              resourceVersionO = Some(rawDetails.localMetadata.fold("")(_.resourceVersion)),
              identityProviderId = identityProviderId,
            )
          )
        })
        PartyDetails.fromProtoPartyDetails(rawUpdatedDetails)
      }

      @Help.Summary("Update party's identity provider id", FeatureFlag.Testing)
      @Help.Description(
        """Updates party's identity provider id.
          party: party to be updated
          sourceIdentityProviderId: source identity provider id
          targetIdentityProviderId: target identity provider id
          """
      )
      def update_idp(
          party: PartyId,
          sourceIdentityProviderId: String,
          targetIdentityProviderId: String,
      ): Unit =
        check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.PartyManagementService.UpdateIdp(
              party = party,
              sourceIdentityProviderId = sourceIdentityProviderId,
              targetIdentityProviderId = targetIdentityProviderId,
            )
          )
        })

      private def verifyOnlyModifiableFieldsWhereModified(
          srcDetails: PartyDetails,
          modifiedDetails: PartyDetails,
      ): Unit = {
        val withAllowedUpdatesReverted = modifiedDetails.copy(annotations = srcDetails.annotations)
        if (withAllowedUpdatesReverted != srcDetails) {
          throw ModifyingNonModifiablePartyDetailsPropertiesError()
        }
      }

      private def get(party: PartyId, identityProviderId: String = ""): ProtoPartyDetails =
        check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.PartyManagementService.GetParty(
              party = party,
              identityProviderId = identityProviderId,
            )
          )
        })

    }

    @Help.Summary("Manage packages", FeatureFlag.Testing)
    @Help.Group("Package Management")
    object packages extends Helpful {

      @Help.Summary("Upload packages from Dar file", FeatureFlag.Testing)
      @Help.Description("""Uploading the Dar can be done either through the ledger Api server or through the Canton admin Api.
          |The Ledger Api is the portable method across ledgers. The Canton admin Api is more powerful as it allows for
          |controlling Canton specific behaviour.
          |In particular, a Dar uploaded using the ledger Api will not be available in the Dar store and can not be downloaded again.
          |Additionally, Dars uploaded using the ledger Api will be vetted, but the system will not wait
          |for the Dars to be successfully registered with all connected synchronizers. As such, if a Dar is uploaded and then
          |used immediately thereafter, a command might bounce due to missing package vettings.""")
      def upload_dar(darPath: String): Unit = check(FeatureFlag.Testing) {
        consoleEnvironment.run {
          ledgerApiCommand(LedgerApiCommands.PackageManagementService.UploadDarFile(darPath))
        }
      }

      @Help.Summary("List Daml Packages", FeatureFlag.Testing)
      def list(limit: PositiveInt = defaultLimit): Seq[PackageDetails] =
        check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(LedgerApiCommands.PackageManagementService.ListKnownPackages(limit))
        })

      @Help.Summary("Validate a DAR against the current participants' state", FeatureFlag.Testing)
      @Help.Description(
        """Performs the same DAR and Daml package validation checks that the upload call performs,
         but with no effects on the target participants: the DAR is not persisted or vetted."""
      )
      def validate_dar(darPath: String): Unit = check(FeatureFlag.Testing) {
        consoleEnvironment.run {
          ledgerApiCommand(LedgerApiCommands.PackageManagementService.ValidateDarFile(darPath))
        }
      }
    }

    @Help.Summary("Monitor progress of commands", FeatureFlag.Testing)
    @Help.Group("Command Completions")
    object completions extends Helpful {

      @Help.Summary("Lists command completions following the specified offset", FeatureFlag.Testing)
      @Help.Description(
        """If the participant has been pruned via `pruning.prune` and if `beginOffset` is lower than
          |the pruning offset, this command fails with a `NOT_FOUND` error.
          |An empty offset denotes the beginning of the participant's offsets."""
      )
      def list(
          partyId: PartyId,
          atLeastNumCompletions: Int,
          beginOffsetExclusive: Long,
          userId: String = userId,
          timeout: config.NonNegativeDuration = timeouts.ledgerCommand,
          filter: Completion => Boolean = _ => true,
      ): Seq[Completion] =
        check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.CommandCompletionService.CompletionRequest(
              partyId.toLf,
              beginOffsetExclusive,
              atLeastNumCompletions,
              timeout.asJavaApproximation,
              userId,
            )(filter, consoleEnvironment.environment.scheduler)
          )
        })

      @Help.Summary("Subscribe to the command completion stream", FeatureFlag.Testing)
      @Help.Description(
        """This function connects to the command completion stream and passes command completions to `observer` until
          |the stream is completed.
          |Only completions for parties in `parties` will be returned.
          |The returned completions start at `beginOffset` (default: the zero value denoting the participant begin).
          |If the participant has been pruned via `pruning.prune` and if `beginOffset` is lower than the pruning offset,
          |this command fails with a `NOT_FOUND` error."""
      )
      def subscribe(
          observer: StreamObserver[Completion],
          parties: Seq[PartyId],
          beginOffsetExclusive: Long = 0L,
          userId: String = userId,
      ): AutoCloseable =
        check(FeatureFlag.Testing)(
          consoleEnvironment.run {
            ledgerApiCommand(
              LedgerApiCommands.CommandCompletionService.Subscribe(
                observer,
                parties.map(_.toLf),
                beginOffsetExclusive,
                userId,
              )
            )
          }
        )
    }

    @Help.Summary("Identity Provider Configuration Management", FeatureFlag.Testing)
    @Help.Group("Ledger Api Identity Provider Configuration Management")
    object identity_provider_config extends Helpful {
      @Help.Summary("Create a new identity provider configuration", FeatureFlag.Testing)
      @Help.Description(
        """Create an identity provider configuration. The request will fail if the maximum allowed number of separate configurations is reached."""
      )
      def create(
          identityProviderId: String,
          isDeactivated: Boolean = false,
          jwksUrl: String,
          issuer: String,
          audience: Option[String],
      ): IdentityProviderConfig = {
        val config = check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.IdentityProviderConfigs.Create(
              identityProviderId =
                IdentityProviderId.Id(Ref.LedgerString.assertFromString(identityProviderId)),
              isDeactivated = isDeactivated,
              jwksUrl = JwksUrl.assertFromString(jwksUrl),
              issuer = issuer,
              audience = audience,
            )
          )
        })
        IdentityProviderConfigClient.fromProtoConfig(config)
      }

      @Help.Summary("Update an identity provider", FeatureFlag.Testing)
      @Help.Description("""Update identity provider""")
      def update(
          identityProviderId: String,
          isDeactivated: Boolean = false,
          jwksUrl: String,
          issuer: String,
          audience: Option[String],
          updateMask: FieldMask,
      ): IdentityProviderConfig = {
        val config = check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.IdentityProviderConfigs.Update(
              IdentityProviderConfig(
                IdentityProviderId.Id(Ref.LedgerString.assertFromString(identityProviderId)),
                isDeactivated,
                JwksUrl(jwksUrl),
                issuer,
                audience,
              ),
              updateMask,
            )
          )
        })
        IdentityProviderConfigClient.fromProtoConfig(config)
      }

      @Help.Summary("Delete an identity provider configuration", FeatureFlag.Testing)
      @Help.Description("""Delete an existing identity provider configuration""")
      def delete(identityProviderId: String): Unit =
        check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.IdentityProviderConfigs.Delete(
              IdentityProviderId.Id(Ref.LedgerString.assertFromString(identityProviderId))
            )
          )
        })

      @Help.Summary("Get an identity provider configuration", FeatureFlag.Testing)
      @Help.Description("""Get identity provider configuration by id""")
      def get(identityProviderId: String): IdentityProviderConfig = {
        val config = check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.IdentityProviderConfigs.Get(
              IdentityProviderId.Id(Ref.LedgerString.assertFromString(identityProviderId))
            )
          )
        })
        IdentityProviderConfigClient.fromProtoConfig(config)
      }

      @Help.Summary("List identity provider configurations", FeatureFlag.Testing)
      @Help.Description("""List all existing identity provider configurations""")
      def list(): Seq[IdentityProviderConfig] = {
        val configs = check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.IdentityProviderConfigs.List()
          )
        })
        configs.map(IdentityProviderConfigClient.fromProtoConfig)
      }
    }

    @Help.Summary("Manage Ledger Api Users", FeatureFlag.Testing)
    @Help.Group("Ledger Api Users")
    object users extends Helpful {

      @Help.Summary("Create a user with the given id", FeatureFlag.Testing)
      @Help.Description(
        """Users are used to dynamically managing the rights given to Daml users.
          |They allow us to link a stable local identifier (of an application) with a set of parties.
          id: the id used to identify the given user
          actAs: the set of parties this user is allowed to act as
          primaryParty: the optional party that should be linked to this user by default
          readAs: the set of parties this user is allowed to read as
          participantAdmin: flag (default false) indicating if the user is allowed to use the admin commands of the Ledger Api
          identityProviderAdmin: flag (default false) indicating if the user is allowed to manage users and parties assigned to the same identity provider
          isDeactivated: flag (default false) indicating if the user is active
          annotations: the set of key-value pairs linked to this user
          identityProviderId: identity provider id
          readAsAnyParty: flag (default false) indicating if the user is allowed to read as any party
          """
      )
      def create(
          id: String,
          actAs: Set[PartyId] = Set(),
          primaryParty: Option[PartyId] = None,
          readAs: Set[PartyId] = Set(),
          participantAdmin: Boolean = false,
          identityProviderAdmin: Boolean = false,
          isDeactivated: Boolean = false,
          annotations: Map[String, String] = Map.empty,
          identityProviderId: String = "",
          readAsAnyParty: Boolean = false,
      ): User = {
        val lapiUser = check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.Users.Create(
              id = id,
              actAs = actAs.map(_.toLf),
              primaryParty = primaryParty.map(_.toLf),
              readAs = readAs.map(_.toLf),
              participantAdmin = participantAdmin,
              identityProviderAdmin = identityProviderAdmin,
              isDeactivated = isDeactivated,
              annotations = annotations,
              identityProviderId = identityProviderId,
              readAsAnyParty = readAsAnyParty,
            )
          )
        })
        User.fromLapiUser(lapiUser)
      }

      @Help.Summary("Update a user", FeatureFlag.Testing)
      @Help.Description(
        """Currently you can update the annotations, active status and primary party.
          |You cannot update other user attributes.
          id: id of the user to be updated
          modifier: a function for modifying the user; e.g: `user => { user.copy(isActive = false, primaryParty = None, annotations = user.annotations.updated("a", "b").removed("c")) }`
          identityProviderId: identity provider id
          """
      )
      def update(
          id: String,
          modifier: User => User,
          identityProviderId: String = "",
      ): User = {
        val rawUser = doGet(
          id = id,
          identityProviderId = identityProviderId,
        )
        val srcUser = User.fromLapiUser(rawUser)
        val modifiedUser = modifier(srcUser)
        verifyOnlyModifiableFieldsWhereModified(srcUser, modifiedUser)
        val annotationsUpdate =
          makeAnnotationsUpdate(original = srcUser.annotations, modified = modifiedUser.annotations)
        val rawUpdatedUser = check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.Users.Update(
              id = id,
              annotationsUpdate = Some(annotationsUpdate),
              primaryPartyUpdate = Some(modifiedUser.primaryParty),
              isDeactivatedUpdate = Some(modifiedUser.isDeactivated),
              resourceVersionO = Some(rawUser.metadata.resourceVersion),
              identityProviderId = identityProviderId,
            )
          )
        })
        User.fromLapiUser(rawUpdatedUser)
      }

      @Help.Summary("Get the user data of the user with the given id", FeatureFlag.Testing)
      @Help.Description(
        """Fetch the data associated with the given user id failing if there is no such user.
          |You will get the user's primary party, active status and annotations.
          |If you need the user rights, use rights.list instead.
          id: user id
          identityProviderId: identity provider id"""
      )
      def get(id: String, identityProviderId: String = ""): User = User.fromLapiUser(
        doGet(
          id = id,
          identityProviderId = identityProviderId,
        )
      )

      @Help.Summary("Delete a user", FeatureFlag.Testing)
      @Help.Description("""Delete a user by id.
         id: user id
         identityProviderId: identity provider id""")
      def delete(id: String, identityProviderId: String = ""): Unit =
        check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.Users.Delete(
              id = id,
              identityProviderId = identityProviderId,
            )
          )
        })

      @Help.Summary("List users", FeatureFlag.Testing)
      @Help.Description("""List users of this participant node
          filterUser: filter results using the given filter string
          pageToken: used for pagination (the result contains a page token if there are further pages)
          pageSize: default page size before the filter is applied
          identityProviderId: identity provider id""")
      def list(
          filterUser: String = "",
          pageToken: String = "",
          pageSize: Int = 100,
          identityProviderId: String = "",
      ): UsersPage = {
        val page: ListLedgerApiUsersResult = check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.Users.List(
              filterUser = filterUser,
              pageToken = pageToken,
              pageSize = pageSize,
              identityProviderId = identityProviderId,
            )
          )
        })
        UsersPage(
          users = page.users.map(User.fromLapiUser),
          nextPageToken = page.nextPageToken,
        )
      }

      @Help.Summary("Update user's identity provider id", FeatureFlag.Testing)
      @Help.Description(
        """Updates user's identity provider id.
          id: the id used to identify the given user
          sourceIdentityProviderId: source identity provider id
          targetIdentityProviderId: target identity provider id
          """
      )
      def update_idp(
          id: String,
          sourceIdentityProviderId: String,
          targetIdentityProviderId: String,
      ): Unit =
        check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.Users.UpdateIdp(
              id = id,
              sourceIdentityProviderId = sourceIdentityProviderId,
              targetIdentityProviderId = targetIdentityProviderId,
            )
          )
        })

      private def verifyOnlyModifiableFieldsWhereModified(
          srcUser: User,
          modifiedUser: User,
      ): Unit = {
        val withAllowedUpdatesReverted = modifiedUser.copy(
          primaryParty = srcUser.primaryParty,
          isDeactivated = srcUser.isDeactivated,
          annotations = srcUser.annotations,
        )
        if (withAllowedUpdatesReverted != srcUser) {
          throw ModifyingNonModifiableUserPropertiesError()
        }
      }

      private def doGet(id: String, identityProviderId: String): LedgerApiUser =
        check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.Users.Get(
              id = id,
              identityProviderId = identityProviderId,
            )
          )
        })

      @Help.Summary("Manage Ledger Api User Rights", FeatureFlag.Testing)
      @Help.Group("Ledger Api User Rights")
      object rights extends Helpful {

        @Help.Summary("Grant new rights to a user", FeatureFlag.Testing)
        @Help.Description("""Users are used to dynamically managing the rights given to Daml applications.
          |This function is used to grant new rights to an existing user.
          id: the id used to identify the given user
          actAs: the set of parties this user is allowed to act as
          readAs: the set of parties this user is allowed to read as
          participantAdmin: flag (default false) indicating if the user is allowed to use the admin commands of the Ledger Api
          identityProviderAdmin: flag (default false) indicating if the user is allowed to manage users and parties assigned to the same identity provider
          identityProviderId: identity provider id
          readAsAnyParty: flag (default false) indicating if the user is allowed to read as any party
          """)
        def grant(
            id: String,
            actAs: Set[PartyId] = Set(),
            readAs: Set[PartyId] = Set(),
            participantAdmin: Boolean = false,
            identityProviderAdmin: Boolean = false,
            identityProviderId: String = "",
            readAsAnyParty: Boolean = false,
        ): UserRights =
          check(FeatureFlag.Testing)(consoleEnvironment.run {
            ledgerApiCommand(
              LedgerApiCommands.Users.Rights.Grant(
                id = id,
                actAs = actAs.map(_.toLf),
                readAs = readAs.map(_.toLf),
                participantAdmin = participantAdmin,
                identityProviderAdmin = identityProviderAdmin,
                identityProviderId = identityProviderId,
                readAsAnyParty = readAsAnyParty,
              )
            )
          })

        @Help.Summary("Revoke user rights", FeatureFlag.Testing)
        @Help.Description("""Use to revoke specific rights from a user.
          id: the id used to identify the given user
          actAs: the set of parties this user should not be allowed to act as
          readAs: the set of parties this user should not be allowed to read as
          participantAdmin: if set to true, the participant admin rights will be removed
          identityProviderAdmin: if set to true, the identity provider admin rights will be removed
          identityProviderId: identity provider id
          readAsAnyParty: flag (default false) indicating if the user is allowed to read as any party
          """)
        def revoke(
            id: String,
            actAs: Set[PartyId] = Set(),
            readAs: Set[PartyId] = Set(),
            participantAdmin: Boolean = false,
            identityProviderAdmin: Boolean = false,
            identityProviderId: String = "",
            readAsAnyParty: Boolean = false,
        ): UserRights =
          check(FeatureFlag.Testing)(consoleEnvironment.run {
            ledgerApiCommand(
              LedgerApiCommands.Users.Rights.Revoke(
                id = id,
                actAs = actAs.map(_.toLf),
                readAs = readAs.map(_.toLf),
                participantAdmin = participantAdmin,
                identityProviderAdmin = identityProviderAdmin,
                identityProviderId = identityProviderId,
                readAsAnyParty = readAsAnyParty,
              )
            )
          })

        @Help.Summary("List rights of a user", FeatureFlag.Testing)
        @Help.Description("""Lists the rights of a user, or the rights of the current user.
            id: user id
            identityProviderId: identity provider id""")
        def list(id: String, identityProviderId: String = ""): UserRights =
          check(FeatureFlag.Testing)(consoleEnvironment.run {
            ledgerApiCommand(
              LedgerApiCommands.Users.Rights.List(
                id = id,
                identityProviderId = identityProviderId,
              )
            )
          })

      }

    }

    @Help.Summary("Interact with the time service", FeatureFlag.Testing)
    @Help.Group("Time")
    object time {
      @Help.Summary("Get the participants time", FeatureFlag.Testing)
      @Help.Description("""Returns the current timestamp of the participant which is either the
                         system clock or the static time""")
      def get(): CantonTimestamp =
        check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.Time.Get
          )
        })

      @Help.Summary("Set the participants time", FeatureFlag.Testing)
      @Help.Description(
        """Sets the participants time if the participant is running in static time mode"""
      )
      def set(currentTime: CantonTimestamp, nextTime: CantonTimestamp): Unit =
        check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(LedgerApiCommands.Time.Set(currentTime, nextTime))
        })

    }

    @Help.Summary("Query event details", FeatureFlag.Testing)
    @Help.Group("EventQuery")
    object event_query extends Helpful {

      @Help.Summary("Get events by contract Id", FeatureFlag.Testing)
      @Help.Description("""Return events associated with the given contract Id""")
      def by_contract_id(
          contractId: String,
          requestingParties: Seq[PartyId],
      ): GetEventsByContractIdResponse =
        check(FeatureFlag.Testing)(consoleEnvironment.run {
          ledgerApiCommand(
            LedgerApiCommands.QueryService
              .GetEventsByContractId(contractId, requestingParties.map(_.toLf))
          )
        })
    }

    @Help.Summary("Group of commands that utilize java bindings", FeatureFlag.Testing)
    @Help.Group("Ledger Api (Java bindings)")
    object javaapi extends Helpful {

      @Help.Summary("Interactive submission", FeatureFlag.Testing)
      @Help.Group("Interactive Submission")
      object interactive_submission extends Helpful {

        @Help.Summary(
          "Prepare a transaction for interactive submission"
        )
        @Help.Description(
          "Prepare a transaction for interactive submission"
        )
        def prepare(
            actAs: Seq[PartyId],
            commands: Seq[javab.data.Command],
            synchronizerId: Option[SynchronizerId] = None,
            commandId: String = "",
            minLedgerTimeAbs: Option[Instant] = None,
            readAs: Seq[PartyId] = Seq.empty,
            disclosedContracts: Seq[javab.data.DisclosedContract] = Seq.empty,
            userId: String = userId,
            userPackageSelectionPreference: Seq[LfPackageId] = Seq.empty,
            verboseHashing: Boolean = false,
            prefetchContractKeys: Seq[javab.data.PrefetchContractKey] = Seq.empty,
        ): PrepareResponseProto =
          consoleEnvironment.run {
            ledgerApiCommand(
              LedgerApiCommands.InteractiveSubmissionService.PrepareCommand(
                actAs.map(_.toLf),
                readAs.map(_.toLf),
                commands.map(c => Command.fromJavaProto(c.toProtoCommand)),
                commandId,
                minLedgerTimeAbs,
                disclosedContracts.map(c => DisclosedContract.fromJavaProto(c.toProto)),
                synchronizerId,
                userId,
                userPackageSelectionPreference,
                verboseHashing,
                prefetchContractKeys.map(k => PrefetchContractKey.fromJavaProto(k.toProto)),
              )
            )
          }
      }

      @Help.Summary("Submit commands (Java bindings)", FeatureFlag.Testing)
      @Help.Group("Command Submission (Java bindings)")
      object commands extends Helpful {
        @Help.Summary(
          "Submit java codegen commands and wait for the resulting transaction, returning the transaction tree or failing otherwise",
          FeatureFlag.Testing,
        )
        @Help.Description(
          """Submits a command on behalf of the `actAs` parties, waits for the resulting transaction to commit and returns it.
            | If the timeout is set, it also waits for the transaction to appear at all other configured
            | participants who were involved in the transaction. The call blocks until the transaction commits or fails;
            | the timeout only specifies how long to wait at the other participants.
            | Fails if the transaction doesn't commit, or if it doesn't become visible to the involved participants in
            | the allotted time.
            | Note that if the optTimeout is set and the involved parties are concurrently enabled/disabled or their
            | participants are connected/disconnected, the command may currently result in spurious timeouts or may
            | return before the transaction appears at all the involved participants."""
        )
        def submit(
            actAs: Seq[PartyId],
            commands: Seq[javab.data.Command],
            synchronizerId: Option[SynchronizerId] = None,
            workflowId: String = "",
            commandId: String = "",
            optTimeout: Option[config.NonNegativeDuration] = Some(timeouts.ledgerCommand),
            deduplicationPeriod: Option[DeduplicationPeriod] = None,
            submissionId: String = "",
            minLedgerTimeAbs: Option[Instant] = None,
            readAs: Seq[PartyId] = Seq.empty,
            disclosedContracts: Seq[javab.data.DisclosedContract] = Seq.empty,
            userId: String = userId,
            userPackageSelectionPreference: Seq[LfPackageId] = Seq.empty,
        ): TransactionTree = check(FeatureFlag.Testing) {
          val tx = consoleEnvironment.run {
            ledgerApiCommand(
              LedgerApiCommands.CommandService.SubmitAndWaitTransactionTree(
                actAs.map(_.toLf),
                readAs.map(_.toLf),
                commands.map(c => Command.fromJavaProto(c.toProtoCommand)),
                workflowId,
                commandId,
                deduplicationPeriod,
                submissionId,
                minLedgerTimeAbs,
                disclosedContracts.map(c => DisclosedContract.fromJavaProto(c.toProto)),
                synchronizerId,
                userId,
                userPackageSelectionPreference,
              )
            )
          }
          javab.data.TransactionTree.fromProto(
            TransactionTreeProto.toJavaProto(
              optionallyAwait(tx, tx.updateId, tx.synchronizerId, optTimeout)
            )
          )
        }

        @Help.Summary(
          "Submit java codegen command and wait for the resulting transaction, returning the flattened transaction or failing otherwise",
          FeatureFlag.Testing,
        )
        @Help.Description(
          """Submits a command on behalf of the `actAs` parties, waits for the resulting transaction to commit, and returns the "flattened" transaction.
            | If the timeout is set, it also waits for the transaction to appear at all other configured
            | participants who were involved in the transaction. The call blocks until the transaction commits or fails;
            | the timeout only specifies how long to wait at the other participants.
            | Fails if the transaction doesn't commit, or if it doesn't become visible to the involved participants in
            | the allotted time.
            | Note that if the optTimeout is set and the involved parties are concurrently enabled/disabled or their
            | participants are connected/disconnected, the command may currently result in spurious timeouts or may
            | return before the transaction appears at all the involved participants."""
        )
        def submit_flat(
            actAs: Seq[PartyId],
            commands: Seq[javab.data.Command],
            synchronizerId: Option[SynchronizerId] = None,
            workflowId: String = "",
            commandId: String = "",
            optTimeout: Option[config.NonNegativeDuration] = Some(timeouts.ledgerCommand),
            deduplicationPeriod: Option[DeduplicationPeriod] = None,
            submissionId: String = "",
            minLedgerTimeAbs: Option[Instant] = None,
            readAs: Seq[PartyId] = Seq.empty,
            disclosedContracts: Seq[javab.data.DisclosedContract] = Seq.empty,
            userId: String = userId,
            userPackageSelectionPreference: Seq[LfPackageId] = Seq.empty,
        ): Transaction = check(FeatureFlag.Testing) {
          val tx = consoleEnvironment.run {
            ledgerApiCommand(
              LedgerApiCommands.CommandService.SubmitAndWaitTransaction(
                actAs.map(_.toLf),
                readAs.map(_.toLf),
                commands.map(c => Command.fromJavaProto(c.toProtoCommand)),
                workflowId,
                commandId,
                deduplicationPeriod,
                submissionId,
                minLedgerTimeAbs,
                disclosedContracts.map(c => DisclosedContract.fromJavaProto(c.toProto)),
                synchronizerId,
                userId,
                userPackageSelectionPreference,
              )
            )
          }
          javab.data.Transaction.fromProto(
            ApiTransaction.toJavaProto(
              optionallyAwait(tx, tx.updateId, tx.synchronizerId, optTimeout)
            )
          )
        }

        @Help.Summary("Submit java codegen command asynchronously", FeatureFlag.Testing)
        @Help.Description(
          """Provides access to the command submission service of the Ledger API.
            |See https://docs.daml.com/app-dev/services.html for documentation of the parameters."""
        )
        def submit_async(
            actAs: Seq[PartyId],
            commands: Seq[javab.data.Command],
            synchronizerId: Option[SynchronizerId] = None,
            workflowId: String = "",
            commandId: String = "",
            deduplicationPeriod: Option[DeduplicationPeriod] = None,
            submissionId: String = "",
            minLedgerTimeAbs: Option[Instant] = None,
            readAs: Seq[PartyId] = Seq.empty,
            disclosedContracts: Seq[javab.data.DisclosedContract] = Seq.empty,
            userId: String = userId,
        ): Unit =
          ledger_api.commands.submit_async(
            actAs,
            commands.map(c => Command.fromJavaProto(c.toProtoCommand)),
            synchronizerId,
            workflowId,
            commandId,
            deduplicationPeriod,
            submissionId,
            minLedgerTimeAbs,
            readAs,
            disclosedContracts.map(c => DisclosedContract.fromJavaProto(c.toProto)),
            userId,
          )

        @Help.Summary(
          "Submit assign command and wait for the resulting java codegen reassignment, returning the reassignment or failing otherwise",
          FeatureFlag.Testing,
        )
        @Help.Description(
          """Submits an unassignment command on behalf of `submitter` party, waits for the resulting unassignment to commit, and returns the reassignment.
            | If waitForParticipants is set, it also waits for the reassignment(s) to appear at all other configured
            | participants who were involved in the unassignment. The call blocks until the unassignment commits or fails.
            | Fails if the unassignment doesn't commit, or if it doesn't become visible to the involved participants in time.
            | Timout specifies the time how long to wait until the reassignment appears in the update stream for the submitting and all the specified participants."""
        )
        def submit_unassign(
            submitter: PartyId,
            contractIds: Seq[LfContractId],
            source: SynchronizerId,
            target: SynchronizerId,
            workflowId: String = "",
            userId: String = userId,
            submissionId: String = UUID.randomUUID().toString,
            eventFormat: Option[EventFormat] = eventFormatAllParties,
            waitForParticipants: Map[ParticipantReference, PartyId] = Map.empty,
            timeout: config.NonNegativeDuration = timeouts.ledgerCommand,
        ): Reassignment =
          ledger_api.commands
            .submit_unassign(
              submitter,
              contractIds,
              source,
              target,
              workflowId,
              userId,
              submissionId,
              eventFormat,
              waitForParticipants,
              timeout,
            )
            .reassignment
            .pipe(ReassignmentProto.toJavaProto)
            .pipe(Reassignment.fromProto)

        @Help.Summary(
          "Submit assign command and wait for the resulting java codegen reassignment, returning the reassignment or failing otherwise",
          FeatureFlag.Testing,
        )
        @Help.Description(
          """Submits a assignment command on behalf of `submitter` party, waits for the resulting assignment to commit, and returns the reassignment.
            | If waitForParticipants is set, it also waits for the reassignment(s) to appear at all other configured
            | participants who were involved in the assignment. The call blocks until the assignment commits or fails.
            | Fails if the assignment doesn't commit, or if it doesn't become visible to the involved participants in time.
            | Timout specifies the time how long to wait until the reassignment appears in the update stream for the submitting and all the specified participants.
            | The unassignId should be the one returned by the corresponding submit_unassign command."""
        )
        def submit_assign(
            submitter: PartyId,
            unassignId: String,
            source: SynchronizerId,
            target: SynchronizerId,
            workflowId: String = "",
            userId: String = userId,
            submissionId: String = UUID.randomUUID().toString,
            eventFormat: Option[EventFormat] = eventFormatAllParties,
            waitForParticipants: Map[ParticipantReference, PartyId] = Map.empty,
            timeout: config.NonNegativeDuration = timeouts.ledgerCommand,
        ): Reassignment =
          ledger_api.commands
            .submit_assign(
              submitter,
              unassignId,
              source,
              target,
              workflowId,
              userId,
              submissionId,
              eventFormat,
              waitForParticipants,
              timeout,
            )
            .reassignment
            .pipe(ReassignmentProto.toJavaProto)
            .pipe(Reassignment.fromProto)
      }

      @Help.Summary("Read from update stream (Java bindings)", FeatureFlag.Testing)
      @Help.Group("Updates (Java bindings)")
      object updates extends Helpful {

        @Help.Summary(
          "Get update trees in the format expected by the Java bindings",
          FeatureFlag.Testing,
        )
        @Help.Description(
          """This function connects to the update tree stream for the given parties and collects update trees
            |until either `completeAfter` update trees have been received or `timeout` has elapsed.
            |The returned update trees can be filtered to be between the given offsets (default: no filtering).
            |If the participant has been pruned via `pruning.prune` and if `beginOffset` is lower than the pruning offset,
            |this command fails with a `NOT_FOUND` error.
            |If the beginOffset is zero then the participant begin is taken as beginning offset.
            |If the endOffset is None then a continuous stream is returned."""
        )
        def trees(
            partyIds: Set[PartyId],
            completeAfter: PositiveInt,
            beginOffsetExclusive: Long = 0L,
            endOffsetInclusive: Option[Long] = None,
            verbose: Boolean = true,
            timeout: config.NonNegativeDuration = timeouts.ledgerCommand,
            resultFilter: UpdateTreeWrapper => Boolean = _ => true,
        ): Seq[GetUpdateTreesResponse] = check(FeatureFlag.Testing)(
          ledger_api.updates
            .trees(
              partyIds,
              completeAfter,
              beginOffsetExclusive,
              endOffsetInclusive,
              verbose,
              timeout,
              resultFilter,
            )
            .map {
              case tx: TransactionTreeWrapper =>
                tx.transactionTree
                  .pipe(TransactionTreeProto.toJavaProto)
                  .pipe(javab.data.TransactionTree.fromProto)
                  .pipe(new GetUpdateTreesResponse(_))

              case reassignment: ReassignmentWrapper =>
                reassignment.reassignment
                  .pipe(ReassignmentProto.toJavaProto)
                  .pipe(Reassignment.fromProto)
                  .pipe(new GetUpdateTreesResponse(_))
            }
        )

        @Help.Summary(
          "Get flat updates in the format expected by the Java bindings",
          FeatureFlag.Testing,
        )
        @Help.Description(
          """This function connects to the flat update stream for the given parties and collects updates
            |until either `completeAfter` flat updates have been received or `timeout` has elapsed.
            |The returned updates can be filtered to be between the given offsets (default: no filtering).
            |If the participant has been pruned via `pruning.prune` and if `beginOffset` is lower than the pruning offset,
            |this command fails with a `NOT_FOUND` error. If you need to specify filtering conditions for template IDs and
            |including create event blobs for explicit disclosure, consider using `flat_with_tx_filter`.
            |If the beginOffset is zero then the participant begin is taken as beginning offset.
            |If the endOffset is None then a continuous stream is returned."""
        )
        def flat(
            partyIds: Set[PartyId],
            completeAfter: PositiveInt,
            beginOffsetExclusive: Long = 0L,
            endOffsetInclusive: Option[Long] = None,
            verbose: Boolean = true,
            timeout: config.NonNegativeDuration = timeouts.ledgerCommand,
            resultFilter: UpdateWrapper => Boolean = _ => true,
        ): Seq[GetUpdatesResponse] = check(FeatureFlag.Testing)(
          ledger_api.updates
            .flat(
              partyIds,
              completeAfter,
              beginOffsetExclusive,
              endOffsetInclusive,
              verbose,
              timeout,
              resultFilter,
            )
            .map {
              case tx: TransactionWrapper =>
                tx.transaction
                  .pipe(ApiTransaction.toJavaProto)
                  .pipe(javab.data.Transaction.fromProto)
                  .pipe(new GetUpdatesResponse(_))

              case reassignment: ReassignmentWrapper =>
                reassignment.reassignment
                  .pipe(ReassignmentProto.toJavaProto)
                  .pipe(Reassignment.fromProto)
                  .pipe(new GetUpdatesResponse(_))

              case tt: TopologyTransactionWrapper =>
                tt.topologyTransaction
                  .pipe(TopoplogyTransactionProto.toJavaProto)
                  .pipe(TopologyTransaction.fromProto)
                  .pipe(new GetUpdatesResponse(_))
            }
        )

        @Help.Summary(
          "Get flat updates in the format expected by the Java bindings",
          FeatureFlag.Testing,
        )
        @Help.Description(
          """This function connects to the flat update stream for the given transaction filter and collects updates
            |until either `completeAfter` transactions have been received or `timeout` has elapsed.
            |The returned transactions can be filtered to be between the given offsets (default: no filtering).
            |If the participant has been pruned via `pruning.prune` and if `beginOffset` is lower than the pruning offset,
            |this command fails with a `NOT_FOUND` error. If you only need to filter by a set of parties, consider using
            |`flat` instead.
            |If the beginOffset is zero then the participant begin is taken as beginning offset.
            |If the endOffset is None then a continuous stream is returned."""
        )
        def flat_with_tx_filter(
            filter: TransactionFilter,
            completeAfter: PositiveInt,
            beginOffsetExclusive: Long = 0L,
            endOffsetInclusive: Option[Long] = None,
            verbose: Boolean = true,
            timeout: config.NonNegativeDuration = timeouts.ledgerCommand,
            resultFilter: UpdateWrapper => Boolean = _ => true,
        ): Seq[GetUpdatesResponse] = check(FeatureFlag.Testing)(
          ledger_api.updates
            .flat_with_tx_filter(
              TransactionFilterProto.fromJavaProto(filter.toProto),
              completeAfter,
              beginOffsetExclusive,
              endOffsetInclusive,
              verbose,
              timeout,
              resultFilter,
            )
            .map {
              case tx: TransactionWrapper =>
                tx.transaction
                  .pipe(ApiTransaction.toJavaProto)
                  .pipe(javab.data.Transaction.fromProto)
                  .pipe(new GetUpdatesResponse(_))

              case reassignment: ReassignmentWrapper =>
                reassignment.reassignment
                  .pipe(ReassignmentProto.toJavaProto)
                  .pipe(Reassignment.fromProto)
                  .pipe(new GetUpdatesResponse(_))

              case tt: TopologyTransactionWrapper =>
                tt.topologyTransaction
                  .pipe(TopoplogyTransactionProto.toJavaProto)
                  .pipe(TopologyTransaction.fromProto)
                  .pipe(new GetUpdatesResponse(_))
            }
        )
      }

      @Help.Summary("Collection of Ledger API state endpoints (Java bindings)", FeatureFlag.Testing)
      @Help.Group("State (Java bindings)")
      object state extends Helpful {

        @Help.Summary("Read active contracts (Java bindings)", FeatureFlag.Testing)
        @Help.Group("Active Contracts (Java bindings)")
        object acs extends Helpful {

          @Help.Summary(
            "Wait until a contract becomes available and return the Java codegen contract",
            FeatureFlag.Testing,
          )
          @Help.Description(
            """This function can be used for contracts with a code-generated Java model.
              |You can refine your search using the `filter` function argument.
              |You can restrict search to a synchronizer by specifying the optional synchronizer id.
              |The command will wait until the contract appears or throw an exception once it times out."""
          )
          def await[
              TC <: javab.data.codegen.Contract[TCid, T],
              TCid <: javab.data.codegen.ContractId[T],
              T <: javab.data.Template,
          ](companion: javab.data.codegen.ContractCompanion[TC, TCid, T])(
              partyId: PartyId,
              predicate: TC => Boolean = (_: TC) => true,
              synchronizerFilter: Option[SynchronizerId] = None,
              timeout: config.NonNegativeDuration = timeouts.ledgerCommand,
          ): TC = check(FeatureFlag.Testing)({
            val result = new AtomicReference[Option[TC]](None)
            ConsoleMacros.utils.retry_until_true(timeout) {
              val tmp = filter(companion)(partyId, predicate, synchronizerFilter)
              result.set(tmp.headOption)
              tmp.nonEmpty
            }
            consoleEnvironment.runE {
              result
                .get()
                .toRight(
                  s"Failed to find contract of type ${companion.getTemplateIdWithPackageId} after $timeout"
                )
            }
          })

          @Help.Summary(
            "Filter the ACS for contracts of a particular Java code-generated template",
            FeatureFlag.Testing,
          )
          @Help.Description(
            """To use this function, ensure a code-generated Java model for the target template exists.
              |You can refine your search using the `predicate` function argument.
              |You can restrict search to a synchronizer by specifying the optional synchronizer id."""
          )
          def filter[
              TC <: javab.data.codegen.Contract[TCid, T],
              TCid <: javab.data.codegen.ContractId[T],
              T <: javab.data.Template,
          ](templateCompanion: javab.data.codegen.ContractCompanion[TC, TCid, T])(
              partyId: PartyId,
              predicate: TC => Boolean = (_: TC) => true,
              synchronizerFilter: Option[SynchronizerId] = None,
          ): Seq[TC] = check(FeatureFlag.Testing) {
            val javaTemplateId = templateCompanion.getTemplateIdWithPackageId
            val templateId = TemplateId(
              templateCompanion.PACKAGE.id,
              javaTemplateId.getModuleName,
              javaTemplateId.getEntityName,
            )

            def synchronizerPredicate(entry: WrappedContractEntry) =
              synchronizerFilter match {
                case Some(_synchronizerId) => entry.synchronizerId == synchronizerFilter
                case None => true
              }

            ledger_api.state.acs
              .of_party(partyId, filterTemplates = Seq(templateId))
              .collect { case entry if synchronizerPredicate(entry) => entry.event }
              .flatMap { ev =>
                JavaDecodeUtil
                  .decodeCreated(templateCompanion)(
                    javab.data.CreatedEvent.fromProto(CreatedEvent.toJavaProto(ev))
                  )
                  .toList
              }
              .filter(predicate)
          }
        }
      }

      @Help.Summary("Query event details", FeatureFlag.Testing)
      @Help.Group("EventQuery")
      object event_query extends Helpful {

        @Help.Summary("Get events in java codegen by contract Id", FeatureFlag.Testing)
        @Help.Description("""Return events associated with the given contract Id""")
        def by_contract_id(
            contractId: String,
            requestingParties: Seq[PartyId],
        ): com.daml.ledger.api.v2.EventQueryServiceOuterClass.GetEventsByContractIdResponse =
          ledger_api.event_query
            .by_contract_id(contractId, requestingParties)
            .pipe(GetEventsByContractIdResponse.toJavaProto)
      }

    }

    private def waitForUpdateId(
        administration: BaseLedgerApiAdministration,
        from: Long,
        queryPartyId: PartyId,
        updateId: String,
        timeout: config.NonNegativeDuration,
    ): UpdateWrapper = {
      def logPrefix: String =
        s"As waiting for update-id:$updateId at participant:$administration with querying party:$queryPartyId starting from $from: "
      Try(
        administration.ledger_api.updates
          .flat(
            partyIds = Set(queryPartyId),
            beginOffsetExclusive = from,
            completeAfter = PositiveInt.one,
            resultFilter = {
              case reassignmentW: ReassignmentWrapper =>
                reassignmentW.reassignment.updateId == updateId
              case TransactionWrapper(transaction) => transaction.updateId == updateId
              case tt: TopologyTransactionWrapper =>
                tt.topologyTransaction.updateId == updateId
            },
            timeout = timeout,
          )
      ) match {
        case Success(values) if values.sizeIs == 1 => values(0)
        case Success(values) =>
          throw new IllegalStateException(
            s"$logPrefix Exactely one update expected, but received #${values.size}"
          )
        case Failure(t) => throw new IllegalStateException(s"$logPrefix an exception occurred.", t)
      }
    }
  }

  /** @return
    *   The modified map where deletion from the original are represented as keys with empty values
    */
  private def makeAnnotationsUpdate(
      original: Map[String, String],
      modified: Map[String, String],
  ): Map[String, String] = {
    val deletions = original.removedAll(modified.keys).view.mapValues(_ => "").toMap
    modified.concat(deletions)
  }
}

trait LedgerApiAdministration extends BaseLedgerApiAdministration {
  this: LedgerApiCommandRunner & AdminCommandRunner & NamedLogging & FeatureFlagFilter =>

  implicit val consoleEnvironment: ConsoleEnvironment
  protected val name: String

  import com.digitalasset.canton.util.ShowUtil.*

  private def awaitTransaction(
      transactionId: String,
      at: Map[ParticipantReference, PartyId],
      timeout: config.NonNegativeDuration,
  ): Unit = {
    def scan() =
      at.map { case (participant, party) =>
        (
          participant,
          party,
          participant.ledger_api.updates.by_id(Set(party), transactionId).isDefined,
        )
      }
    ConsoleMacros.utils.retry_until_true(timeout)(
      scan().forall(_._3), {
        val res = scan().map { case (participant, party, res) =>
          s"${party.toString}@${participant.toString}: ${if (res) "observed" else "not observed"}"
        }
        s"Failed to observe transaction on all nodes: ${res.mkString(", ")}"
      },
    )

  }

  private[console] def involvedParticipants(
      transactionId: String,
      txSynchronizerId: String,
  ): Map[ParticipantReference, PartyId] = {
    val txSynchronizer = SynchronizerId.tryFromString(txSynchronizerId)
    // TODO(#6317)
    // There's a race condition here, in the unlikely circumstance that the party->participant mapping on the synchronizer
    // changes during the command's execution. We'll have to live with it for the moment, as there's no convenient
    // way to get the record time of the transaction to pass to the parties.list call.
    val synchronizerPartiesAndParticipants =
      consoleEnvironment.participants.all.iterator
        .filter(x => x.health.is_running() && x.health.initialized() && x.name == name)
        .flatMap(_.parties.list(synchronizerIds = Set(txSynchronizer)))
        .toSet

    val synchronizerParties = synchronizerPartiesAndParticipants.map(_.party)
    // WARNING! this logic will become highly problematic if we introduce witness blinding based on topology events
    // Read the transaction under the authority of all parties on the synchronizer, in order to get the witness_parties
    // to be all the actual witnesses of the transaction. There's no other convenient way to get the full witnesses,
    // as the Exercise events don't contain the informees of the Exercise action.
    val tree = ledger_api.updates
      .by_id(synchronizerParties, transactionId)
      .getOrElse(
        throw new IllegalStateException(
          s"Can't find transaction by ID: $transactionId. Queried parties: $synchronizerParties"
        )
      )
    val witnesses = tree.eventsById.values
      .flatMap { ev =>
        ev.kind.created.fold(Seq.empty[String])(ev => ev.witnessParties) ++
          ev.kind.exercised.fold(Seq.empty[String])(ev => ev.witnessParties)
      }
      .map(PartyId.tryFromProtoPrimitive)
      .toSet

    // A participant identity equality check that doesn't blow up if the participant isn't running
    def identityIs(pRef: ParticipantReference, id: ParticipantId): Boolean = pRef match {
      case lRef: LocalParticipantReference =>
        lRef.is_running && lRef.health.initialized() && lRef.id == id
      case rRef: RemoteParticipantReference =>
        rRef.health.initialized() && rRef.id == id
      case _ => false
    }

    // Map each involved participant to some party that witnessed the transaction (it doesn't matter which one)
    synchronizerPartiesAndParticipants.toList.foldMapK { cand =>
      if (witnesses.contains(cand.party)) {
        val involvedConsoleParticipants = cand.participants.mapFilter { pd =>
          for {
            participantReference <-
              consoleEnvironment.participants.all
                .filter(x => x.health.is_running() && x.health.initialized())
                .find(identityIs(_, pd.participant))
            _ <- pd.synchronizers.find(_.synchronizerId == txSynchronizer)
          } yield participantReference
        }
        involvedConsoleParticipants
          .map(_ -> cand.party)
          .toMap
      } else Map.empty
    }
  }

  def optionallyAwait[Tx](
      tx: Tx,
      txId: String,
      txSynchronizerId: String,
      optTimeout: Option[config.NonNegativeDuration],
  ): Tx =
    optTimeout match {
      case None => tx
      case Some(timeout) =>
        val involved = involvedParticipants(txId, txSynchronizerId)
        logger.debug(show"Awaiting transaction ${txId.unquoted} at ${involved.keys.mkShow()}")
        awaitTransaction(txId, involved, timeout)
        tx
    }
}
