// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// TODO(DACH-NY/canton-network-node#7675) - do we need this model?
import { SvVote } from '@lfdecentralizedtrust/splice-common-frontend';
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import * as damlTypes from '@daml/types';
import { Vote, VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';
import { ContractId } from '@daml/types';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

function getVoteStatus(votes: damlTypes.Map<string, Vote>): Vote[] {
  const allVotes: Vote[] = [];
  votes.forEach(v => allVotes.push(v));
  return allVotes;
}

export const useListVotes = (contractIds: ContractId<VoteRequest>[]): UseQueryResult<SvVote[]> => {
  const { listVoteRequestsByTrackingCid } = useSvAdminClient();
  return useQuery({
    queryKey: ['listVoteRequestsByTrackingCid', contractIds],
    queryFn: async () => {
      if (contractIds.length === 0) {
        return [];
      }
      const { vote_requests } = await listVoteRequestsByTrackingCid(contractIds);
      const requests = vote_requests.map(v => Contract.decodeOpenAPI(v, VoteRequest));
      return requests.flatMap(vr =>
        getVoteStatus(vr.payload.votes).map(vote => {
          return {
            requestCid: vr.payload.trackingCid ? vr.payload.trackingCid : vr.contractId,
            voter: vote.sv,
            accept: vote.accept,
            reason: {
              url: vote.reason.url,
              body: vote.reason.body,
            },
            expiresAt: new Date(vr.payload.voteBefore),
          };
        })
      );
    },
  });
};
