..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - Validator app

         - Added a sharing-automation option to each party's reward-sharing config.
           When set to external, an off-node automation owns reward-coupon beneficiary assignment:
           the validator neither mints unassigned reward coupons nor runs built-in sharing for that party.
           Defaults to built-in, preserving existing behavior.
