// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.mintingdelegation as mintingDelegationCodegen
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithSharedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.WalletTestUtil
import com.digitalasset.canton.topology.PartyId

import java.time.Duration
import scala.jdk.CollectionConverters.*

class WalletMintingDelegationIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with WalletTestUtil
    with ExternallySignedPartyTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition.simpleTopology1Sv(this.getClass.getSimpleName)

  "Wallet MintingDelegation APIs" should {
    "allow delegate to list, accept, and reject minting delegation proposals and delegations" in {
      implicit env =>
        // 1. Setup parties - Alice is the delegate (wallet user), external party is the beneficiary
        val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

        // Tap to fund the validator wallet for external party setup
        aliceValidatorWalletClient.tap(100.0)

        // Onboard external party and complete setup (creates TransferPreapproval)
        val beneficiaryOnboarding = onboardExternalParty(aliceValidatorBackend, Some("beneficiary"))
        createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, beneficiaryOnboarding)

        // 2. Verify empty initial state
        clue("Check that no minting delegation proposals exist") {
          aliceWalletClient.listMintingDelegationProposals().proposals shouldBe empty
        }
        clue("Check that no minting delegations exist") {
          aliceWalletClient.listMintingDelegations().delegations shouldBe empty
        }

        // 3. Beneficiary (external party) creates a proposal for Alice (delegate) via externally signed submit
        val expiresAt = env.environment.clock.now.plus(Duration.ofDays(30)).toInstant
        createMintingDelegationProposal(beneficiaryOnboarding, aliceParty, expiresAt)

        // 4. Verify listMintingDelegationProposals shows it and get the contract ID
        val proposal1Cid = clue("Check that the proposal is listed for Alice (delegate)") {
          eventually() {
            val proposals = aliceWalletClient.listMintingDelegationProposals()
            proposals.proposals should have size 1
            proposals.proposals.head.contractId
          }
        }

        // 5. Create a second proposal and test reject
        createMintingDelegationProposal(beneficiaryOnboarding, aliceParty, expiresAt)
        val proposal2Cid = clue("Check that both proposals are listed") {
          eventually() {
            val proposals = aliceWalletClient.listMintingDelegationProposals()
            proposals.proposals should have size 2
            // Return the new proposal's contract ID (different from proposal1)
            proposals.proposals.map(_.contractId).find(_ != proposal1Cid).value
          }
        }

        actAndCheck(
          "Alice rejects the second proposal",
          aliceWalletClient.rejectMintingDelegationProposal(proposal2Cid),
        )(
          "Rejected proposal disappears from list",
          _ => aliceWalletClient.listMintingDelegationProposals().proposals should have size 1,
        )

        // 6. Test acceptMintingDelegationProposal
        val (delegationCid, _) = actAndCheck(
          "Alice accepts the first proposal",
          aliceWalletClient.acceptMintingDelegationProposal(proposal1Cid),
        )(
          "Proposal disappears and delegation is created",
          delegationCid => {
            aliceWalletClient.listMintingDelegationProposals().proposals shouldBe empty
            val delegations = aliceWalletClient.listMintingDelegations()
            delegations.delegations should have size 1
            delegationCid
          },
        )

        // 7. Test rejectMintingDelegation (terminates active delegation)
        actAndCheck(
          "Alice rejects/terminates the active delegation",
          aliceWalletClient.rejectMintingDelegation(delegationCid),
        )(
          "Delegation disappears from list",
          _ => aliceWalletClient.listMintingDelegations().delegations shouldBe empty,
        )
    }
  }

  private def createMintingDelegationProposal(
      beneficiaryOnboarding: OnboardingResult,
      delegate: PartyId,
      expiresAt: java.time.Instant,
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val beneficiary = beneficiaryOnboarding.party
    val proposal = new mintingDelegationCodegen.MintingDelegationProposal(
      new mintingDelegationCodegen.MintingDelegation(
        beneficiary.toProtoPrimitive,
        delegate.toProtoPrimitive,
        dsoParty.toProtoPrimitive,
        expiresAt,
        10, // amuletMergeLimit
      )
    )
    // Use externally signed submission for the external party
    aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
      .submitJavaExternalOrLocal(
        actingParty = beneficiaryOnboarding.richPartyId,
        commands = proposal.create.commands.asScala.toSeq,
      )
  }
}
