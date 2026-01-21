..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _validator-delegations:

Minting Delegations
-------------------

Minting delegations allow a validator to mint rewards on behalf of another party (the beneficiary).
This is useful for external parties who want a validator to manage their reward collection
without having to run their own wallet automation.

Overview
++++++++

A **minting delegation** grants a validator (the delegate) the authority to:

- Mint validator rewards on behalf of a beneficiary party
- Auto-merge amulets for the beneficiary (up to the configured limit)

The delegation has the following key properties:

.. list-table::
   :widths: 20 80
   :header-rows: 1

   * - Property
     - Description
   * - Beneficiary
     - The party on whose behalf minting is performed
   * - Delegate
     - The validator party authorized to perform minting operations
   * - Expiration
     - The time after which the delegation is no longer valid
   * - Amulet merge limit
     - The number of amulets to keep after auto-merging

Workflow
++++++++

The minting delegation workflow consists of two steps:

1. **Proposal Creation**: The beneficiary creates a ``MintingDelegationProposal`` specifying the
   delegate (validator), expiration date, and amulet merge limit. This is typically done via
   the Ledger API or through an application built on top of the wallet API.

2. **Proposal Acceptance**: The validator reviews and accepts (or rejects) the proposal through
   the wallet UI. Upon acceptance, an active ``MintingDelegation`` contract is created.

Using the Delegations Tab
+++++++++++++++++++++++++

Validators can manage minting delegations through the **Delegations** tab in the wallet UI.
This tab is visible to validators and displays two sections:

Proposed Delegations
^^^^^^^^^^^^^^^^^^^^

The **Proposed** section shows all pending ``MintingDelegationProposal`` contracts where
the validator is the designated delegate.

For each proposal, the validator can:

- **Accept**: Approve the delegation request. This creates an active minting delegation.
  If a delegation already exists for the same beneficiary, accepting a new proposal will
  replace the existing delegation.
- **Reject**: Decline the delegation request. This archives the proposal.

.. note::
   The Accept button is disabled if the beneficiary is not yet onboarded to the network.
   The beneficiary must be onboarded before a delegation can be accepted.

Active Delegations
^^^^^^^^^^^^^^^^^^

The **Active** section shows all current ``MintingDelegation`` contracts where the validator
is the delegate.

For each active delegation, the validator can:

- **Withdraw**: Terminate the delegation. This archives the delegation contract and stops
  the validator from minting rewards for the beneficiary.

Replacing Delegations
+++++++++++++++++++++

When a validator accepts a proposal for a beneficiary that already has an active delegation,
a confirmation dialog will appear showing:

- The current delegation's Max Amulets and Expiration values
- The new proposal's Max Amulets and Expiration values

Accepting the proposal will automatically replace the existing delegation with the new one.
This allows beneficiaries to update their delegation parameters (such as extending the
expiration date) without the validator having to manually withdraw the old delegation first.

Security Considerations
+++++++++++++++++++++++

When managing minting delegations, validators should consider:

1. **Verify the beneficiary**: Before accepting a delegation, ensure you recognize and trust
   the beneficiary party. The Party ID should match the expected party.

2. **Review expiration dates**: Check that the expiration date is reasonable. Very long
   expiration periods may not be desirable.

3. **Onboarding status**: Only accept delegations from onboarded parties. The UI enforces
   this by disabling the Accept button for non-onboarded beneficiaries.

4. **Monitor active delegations**: Periodically review active delegations and withdraw any
   that are no longer needed or authorized.

API Reference
+++++++++++++

For programmatic access to minting delegation functionality, the wallet exposes
the following HTTP REST API endpoints:

.. list-table::
   :widths: 40 60
   :header-rows: 1

   * - Endpoint
     - Description
   * - ``GET /v0/wallet/minting-delegation-proposals``
     - List all proposals where the user is the delegate
   * - ``POST /v0/wallet/minting-delegation-proposals/{contract_id}/accept``
     - Accept a proposal, creating a minting delegation
   * - ``POST /v0/wallet/minting-delegation-proposals/{contract_id}/reject``
     - Reject a proposal
   * - ``GET /v0/wallet/minting-delegations``
     - List all active delegations where the user is the delegate
   * - ``POST /v0/wallet/minting-delegations/{contract_id}/reject``
     - Withdraw (terminate) an active delegation

For the underlying Daml contract models, see the
:ref:`Splice.Wallet.MintingDelegation <module-splice-wallet-mintingdelegation-95009>` module documentation.
