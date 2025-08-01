// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.config

import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.config.*
import com.digitalasset.canton.config.RequireTypes.{
  NonNegativeLong,
  NonNegativeNumeric,
  PositiveInt,
  PositiveNumeric,
}
import com.digitalasset.canton.sequencing.SubmissionRequestAmplification
import com.digitalasset.canton.synchronizer.config.SynchronizerParametersConfig
import com.digitalasset.canton.synchronizer.mediator.RemoteMediatorConfig
import com.digitalasset.canton.synchronizer.sequencer.config.RemoteSequencerConfig
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.daml.lf.data.Ref.PackageVersion
import org.apache.pekko.http.scaladsl.model.Uri
import org.lfdecentralizedtrust.splice.auth.AuthConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.config.{
  AutomationConfig,
  BackupDumpConfig,
  BaseParticipantClientConfig,
  GcpBucketConfig,
  LedgerApiClientConfig,
  ParticipantBootstrapDumpConfig,
  SpliceBackendConfig,
  SpliceInstanceNamesConfig,
  SpliceParametersConfig,
}
import org.lfdecentralizedtrust.splice.environment.{DarResource, DarResources}
import org.lfdecentralizedtrust.splice.sv.SvAppClientConfig
import org.lfdecentralizedtrust.splice.util.SpliceUtil

import java.nio.file.Path

case class ExpectedValidatorOnboardingConfig(
    secret: String,
    expiresIn: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofHours(1),
)
object ExpectedValidatorOnboardingConfig {
  def hideConfidential(
      config: ExpectedValidatorOnboardingConfig
  ): ExpectedValidatorOnboardingConfig = {
    val hidden = "****"
    config.copy(secret = hidden)
  }
}

case class ApprovedSvIdentityConfig(
    name: String,
    publicKey: String,
    rewardWeightBps: Long,
)

sealed trait SvOnboardingConfig {
  val name: String // the human-readable name we want others to use for us
}

sealed abstract class SvBootstrapDumpConfig {
  def description: String
}

object SvBootstrapDumpConfig {
  final case class File(file: Path) extends SvBootstrapDumpConfig {
    override val description = s"Local file $file"
  }
  final case class Gcp(
      bucket: GcpBucketConfig,
      path: Path,
  ) extends SvBootstrapDumpConfig {
    override val description = s"Path $path in ${bucket.description}"
  }
}

object SvOnboardingConfig {
  case class FoundDso(
      name: String,
      firstSvRewardWeightBps: Long,
      dsoPartyHint: String = "DSO",
      initialTickDuration: NonNegativeFiniteDuration = SpliceUtil.defaultInitialTickDuration,
      // We use the tickDuration as the default bootstrapping duration to ensure our tests focus on the steady state.
      roundZeroDuration: Option[NonNegativeFiniteDuration] = None,
      initialMaxNumInputs: Int = 100,
      initialAmuletPrice: BigDecimal = 0.005,
      initialHoldingFee: BigDecimal = SpliceUtil.defaultHoldingFee.rate,
      initialCreateFee: BigDecimal = SpliceUtil.defaultCreateFee.fee,
      initialAnsConfig: InitialAnsConfig = InitialAnsConfig(),
      initialSynchronizerFeesConfig: SynchronizerFeesConfig = SynchronizerFeesConfig(),
      isDevNet: Boolean = false,
      bootstrappingDump: Option[SvBootstrapDumpConfig] = None,
      initialPackageConfig: InitialPackageConfig = InitialPackageConfig.defaultInitialPackageConfig,
      initialTransferPreapprovalFee: Option[BigDecimal] = None,
      initialFeaturedAppActivityMarkerAmount: Option[BigDecimal] = None,
      voteCooldownTime: Option[NonNegativeFiniteDuration] = None,
  ) extends SvOnboardingConfig

  case class JoinWithKey(
      name: String,
      svClient: SvAppClientConfig, // an SV that we'll contact to start our onboarding
      publicKey: String, // the key that identifies us together with our name
      privateKey: String, // the private key we use for authenticating ourselves
  ) extends SvOnboardingConfig

  object JoinWithKey

  final case class InitialPackageConfig(
      amuletVersion: String,
      amuletNameServiceVersion: String,
      dsoGovernanceVersion: String,
      validatorLifecycleVersion: String,
      walletVersion: String,
      walletPaymentsVersion: String,
  ) {
    def toPackageConfig = new splice.amuletconfig.PackageConfig(
      amuletVersion,
      amuletNameServiceVersion,
      dsoGovernanceVersion,
      validatorLifecycleVersion,
      walletVersion,
      walletPaymentsVersion,
    )
  }

  object InitialPackageConfig {

    def apply(
        amuletVersion: String,
        amuletNameServiceVersion: String,
        dsoGovernanceVersion: String,
        validatorLifecycleVersion: String,
        walletVersion: String,
        walletPaymentsVersion: String,
    ): InitialPackageConfig = {
      def assertPackageVersion(version: String, resource: DarResource): Unit = {
        assert(
          PackageVersion.assertFromString(version) >= resource.metadata.version,
          s"${resource.metadata.name} must have version at least ${resource.metadata.version}",
        )
      }

      assertPackageVersion(amuletVersion, DarResources.amulet_0_1_8)
      assertPackageVersion(amuletNameServiceVersion, DarResources.amuletNameService_0_1_8)
      assertPackageVersion(dsoGovernanceVersion, DarResources.dsoGovernance_0_1_11)
      assertPackageVersion(validatorLifecycleVersion, DarResources.validatorLifecycle_0_1_2)
      assertPackageVersion(walletVersion, DarResources.wallet_0_1_8)
      assertPackageVersion(walletPaymentsVersion, DarResources.walletPayments_0_1_8)

      new InitialPackageConfig(
        amuletVersion,
        amuletNameServiceVersion,
        dsoGovernanceVersion,
        validatorLifecycleVersion,
        walletVersion,
        walletPaymentsVersion,
      )
    }

    val defaultInitialPackageConfig: InitialPackageConfig = {
      val fromResources = SpliceUtil.readPackageConfig()
      InitialPackageConfig(
        amuletVersion = fromResources.amulet,
        amuletNameServiceVersion = fromResources.amuletNameService,
        dsoGovernanceVersion = fromResources.dsoGovernance,
        validatorLifecycleVersion = fromResources.validatorLifecycle,
        walletVersion = fromResources.wallet,
        walletPaymentsVersion = fromResources.walletPayments,
      )
    }
  }

  // TODO(DACH-NY/canton-network-internal#498) Consider adding `JoinWithToken` based on an already signed token instead of the raw keys

  case class DomainMigration(
      name: String,
      dumpFilePath: Path,
  ) extends SvOnboardingConfig

  def hideConfidential(config: SvOnboardingConfig): SvOnboardingConfig = {
    val hidden = "****"
    config match {
      case JoinWithKey(name, svClient, publicKey, _) =>
        JoinWithKey(name, svClient, publicKey, hidden)
      case other => other
    }
  }
}

final case class InitialAnsConfig(
    renewalDuration: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofDays(30),
    entryLifetime: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofDays(90),
    entryFee: Double = 1.0,
)

final case class SynchronizerFeesConfig(
    extraTrafficPrice: NonNegativeNumeric[BigDecimal] =
      NonNegativeNumeric.tryCreate(BigDecimal(16.67)),
    minTopupAmount: NonNegativeLong = NonNegativeLong.tryCreate(200_000L),
    baseRateBurstAmount: NonNegativeLong = NonNegativeLong.tryCreate(400_000L),
    baseRateBurstWindow: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofMinutes(20),
    // charge 4 per 10,000, i.e., 0.04% of write cost for every read.
    readVsWriteScalingFactor: PositiveNumeric[Int] = PositiveNumeric.tryCreate(4),
)

final case class SvDecentralizedSynchronizerConfig(
    alias: SynchronizerAlias,
    /** This must be set for SVs that onboard to initiallly connect to their sponsoring SV’s sequencer.
      * Afterwards it can be unset.
      */
    url: Option[String],

    /** amount of extra traffic reserved for high priority transactions
      *
      * Note that this value MUST be smaller or euqal to the value provided in SV's validator config; and ideally it SHOULD be equal to it.
      * Also an SV's validator must always be configured to do top-ups
      */
    reservedTraffic: NonNegativeNumeric[Long] = NonNegativeNumeric.tryCreate(200_000L),

    /** The SV's ledger client compares its remaining traffic balance against the reserved amount
      * on every command submission. This setting controls how long the traffic balance is cached before
      * being rehydrated by querying its participant.
      */
    trafficBalanceCacheTimeToLive: NonNegativeFiniteDuration =
      NonNegativeFiniteDuration.ofSeconds(1),
)

final case class SvSynchronizerConfig(
    global: SvDecentralizedSynchronizerConfig
)

final case class BeneficiaryConfig(
    beneficiary: PartyId,
    weight: NonNegativeLong,
)

final case class SvParticipantClientConfig(
    override val adminApi: FullClientConfig,
    override val ledgerApi: LedgerApiClientConfig,
    sequencerRequestAmplification: SubmissionRequestAmplification =
      SvAppBackendConfig.DefaultParticipantSequencerRequestAmplification,
) extends BaseParticipantClientConfig(adminApi, ledgerApi)

case class SvAppBackendConfig(
    override val adminApi: AdminServerConfig = AdminServerConfig(),
    override val storage: DbConfig,
    ledgerApiUser: String,
    // The SV app shares the primary party with the validator app. To discover it we query the
    // validator user. Additionally, sv1 app is expected to create that user,
    // so it needs to know the expected user name.
    validatorLedgerApiUser: String,
    auth: AuthConfig,
    participantClient: SvParticipantClientConfig,
    override val automation: AutomationConfig = AutomationConfig(),
    domains: SvSynchronizerConfig,
    expectedValidatorOnboardings: List[ExpectedValidatorOnboardingConfig] = Nil,
    approvedSvIdentities: List[ApprovedSvIdentityConfig] = Nil,
    // If not set the onboarding name is used. We set this in our tests
    // because this one can be suffixed per test while we keep the onboarding name stable.
    svPartyHint: Option[String] = None,
    onboarding: Option[SvOnboardingConfig] = None,
    initialAmuletPriceVote: Option[BigDecimal] = None,
    cometBftConfig: Option[SvCometBftConfig] = None,
    localSynchronizerNode: Option[SvSynchronizerNodeConfig],
    scan: Option[SvScanConfig],
    participantBootstrappingDump: Option[ParticipantBootstrapDumpConfig] = None,
    identitiesDump: Option[BackupDumpConfig] = None,
    domainMigrationDumpPath: Option[Path] = None,
    // TODO(DACH-NY/canton-network-node#9731): get migration id from sponsor sv / scan instead of configuring here
    domainMigrationId: Long = 0L,
    onLedgerStatusReportInterval: NonNegativeFiniteDuration =
      NonNegativeFiniteDuration.ofMinutes(2),
    parameters: SpliceParametersConfig = SpliceParametersConfig(batching = BatchingConfig()),
    ingestFromParticipantBegin: Boolean = true,
    ingestUpdateHistoryFromParticipantBegin: Boolean = true,
    extraBeneficiaries: Seq[BeneficiaryConfig] = Seq.empty,
    enableOnboardingParticipantPromotionDelay: Boolean = true,
    onboardingPollingInterval: Option[NonNegativeFiniteDuration],
    trafficBalanceReconciliationDelay: NonNegativeFiniteDuration =
      NonNegativeFiniteDuration.ofSeconds(10),
    // Duplicated with the validator config since the SV app sets up the ValidatorLicense.
    // We don't make this optional to encourage users to think about it at least. They
    // can always set it to an empty string.
    contactPoint: String,
    spliceInstanceNames: SpliceInstanceNamesConfig,
    // If the node does not receive an event for that amount of time, it will request a time proof
    // so it can produce a more recent acknowledgement.
    timeTrackerMinObservationDuration: NonNegativeFiniteDuration =
      NonNegativeFiniteDuration.ofMinutes(30),
    // Identifier for all Canton nodes controlled by this application
    cantonIdentifierConfig: Option[SvCantonIdentifierConfig] = None,
    legacyMigrationId: Option[Long] = None,
    // Defaults to 24h to allow for 24h between preparation and execution of an externally signed transaction
    preparationTimeRecordTimeTolerance: NonNegativeFiniteDuration =
      NonNegativeFiniteDuration.ofHours(24),
    // Defaults to 48h as it must be at least 2x preparationTimeRecordtimeTolerance
    mediatorDeduplicationTimeout: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofHours(48),
    topologyChangeDelayDuration: NonNegativeFiniteDuration =
      NonNegativeFiniteDuration.ofMillis(250),
    delegatelessAutomation: Boolean = true,
    expectedTaskDuration: Long = 5000, // milliseconds
    expiredRewardCouponBatchSize: Int = 100,
    bftSequencerConnection: Boolean = true,
    // Skip synchronizer initialization and synchronizer config reconciliation.
    // Can be safely set to true for an SV that has completed onboarding unless you
    // 1. try to reset one of your sequencers or mediators
    // 2. change sequencer URLs that need to get published externally.
    skipSynchronizerInitialization: Boolean = false,
) extends SpliceBackendConfig {
  override val nodeTypeName: String = "SV"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

  def rewardWeightBpsOf(name: String): Option[Long] = approvedSvIdentities
    .collectFirst { case ApprovedSvIdentityConfig(`name`, _, weightBps) =>
      weightBps
    }
    .orElse {
      onboarding match {
        case Some(sv1: SvOnboardingConfig.FoundDso) if sv1.name == name =>
          Some(sv1.firstSvRewardWeightBps)
        case _ => None
      }
    }
}

object SvAppBackendConfig {
  // This is consistent with what the validator sets for a single sequencer connection
  val DefaultParticipantSequencerRequestAmplification = SubmissionRequestAmplification(
    PositiveInt.tryCreate(1),
    NonNegativeFiniteDuration.ofSeconds(10),
  )
  val DefaultMediatorSequencerRequestAmplification = SubmissionRequestAmplification(
    PositiveInt.tryCreate(5),
    NonNegativeFiniteDuration.ofSeconds(10),
  )
}

case class SvCometBftConfig(
    enabled: Boolean = false,
    connectionUri: String = "",
    // `None` means that we will be abusing the SV participant for generating and storing that key
    // (which doesn't work if the participant uses an external KMS).
    governanceKey: Option[CometBftGovernanceKey] = None,
)

case class CometBftGovernanceKey(
    // both base64 encoded
    publicKey: String,
    privateKey: String,
)
object CometBftGovernanceKey {
  def hideConfidential(config: CometBftGovernanceKey): CometBftGovernanceKey = {
    val hidden = "****"
    config.copy(privateKey = hidden)
  }
}

// Removes unnecessary data from the Sequencer that is earlier than the configured retention period
final case class SequencerPruningConfig(
    // this defines how frequent the prune command is being called to the sequencer
    pruningInterval: NonNegativeFiniteDuration,
    // data within the retention period preceding the current time will not be removed during the pruning process
    retentionPeriod: NonNegativeFiniteDuration,
)

final case class SvSequencerConfig(
    adminApi: FullClientConfig,
    internalApi: FullClientConfig,
    externalPublicApiUrl: String,
    // This needs to be participantResponseTimeout + mediatorResponseTimeout to make sure that the sequencer
    // does not have to serve requests that have been in flight before the sequencer's signing keys became valid.
    // See also https://github.com/DACH-NY/canton-network-node/issues/5938#issuecomment-1677165109
    // The default value of 60 seconds is based on Canton defaulting to 30s for each of those.
    // TODO (#845): consider reading config value from participant instead of configuring here
    sequencerAvailabilityDelay: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(60),
    pruning: Option[SequencerPruningConfig] = None,
    isBftSequencer: Boolean = false,
) {
  def toCantonConfig: RemoteSequencerConfig = RemoteSequencerConfig(
    adminApi,
    SequencerApiClientConfig(
      internalApi.address,
      internalApi.port,
    ),
  )
}

final case class SvMediatorConfig(
    adminApi: FullClientConfig,
    sequencerRequestAmplification: SubmissionRequestAmplification =
      SvAppBackendConfig.DefaultMediatorSequencerRequestAmplification,
) {

  def toCantonConfig: RemoteMediatorConfig = RemoteMediatorConfig(
    adminApi
  )
}

final case class SvScanConfig(
    publicUrl: Uri,
    internalUrl: Uri,
)

final case class SvSynchronizerNodeConfig(
    sequencer: SvSequencerConfig,
    mediator: SvMediatorConfig,
) {
  val parameters: SynchronizerParametersConfig = SynchronizerParametersConfig()
}

final case class SvCantonIdentifierConfig(
    participant: String,
    sequencer: String,
    mediator: String,
)
object SvCantonIdentifierConfig {
  def default(config: SvAppBackendConfig): SvCantonIdentifierConfig = {
    val identifier = config.onboarding.fold("unnamedSv")(_.name)
    SvCantonIdentifierConfig(
      participant = identifier,
      sequencer = identifier,
      mediator = identifier,
    )
  }
}
