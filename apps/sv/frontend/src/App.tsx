// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import {
  AuthProvider,
  ErrorBoundary,
  ErrorRouterPage,
  UserProvider,
  theme,
  SvClientProvider,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { replaceEqualDeep } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReactQueryDevtools } from '@tanstack/react-query-devtools';
import { Helmet, HelmetProvider } from 'react-helmet-async';
import {
  Navigate,
  Route,
  RouterProvider,
  createBrowserRouter,
  createRoutesFromElements,
  useNavigate,
} from 'react-router-dom';

import { CssBaseline, ThemeProvider } from '@mui/material';
import { LocalizationProvider } from '@mui/x-date-pickers';
import { AdapterDayjs } from '@mui/x-date-pickers/AdapterDayjs';

import { SvAdminClientProvider } from './contexts/SvAdminServiceContext';
import { SvAppVotesHooksProvider } from './contexts/SvAppVotesHooksContext';
import AmuletPrice from './routes/amuletPrice';
import AuthCheck from './routes/authCheck';
import Delegate from './routes/delegate';
import Dso from './routes/dso';
import Root from './routes/root';
import ValidatorOnboarding from './routes/validatorOnboarding';
import Voting from './routes/voting';
import { useConfigPollInterval, useSvConfig } from './utils';
import { Governance } from './routes/governance';
import { VoteRequestDetails } from './routes/voteRequestDetails';
import { CreateProposal } from './routes/createProposal';

const Providers: React.FC<React.PropsWithChildren> = ({ children }) => {
  const config = useSvConfig();
  const refetchInterval = useConfigPollInterval();
  const navigate = useNavigate();

  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        refetchInterval,
        structuralSharing: replaceEqualDeep,
      },
    },
  });

  return (
    <LocalizationProvider dateAdapter={AdapterDayjs}>
      <AuthProvider authConf={config.auth} redirect={(path: string) => navigate(path)}>
        <QueryClientProvider client={queryClient}>
          <ReactQueryDevtools initialIsOpen={false} />
          <UserProvider authConf={config.auth} testAuthConf={config.testAuth}>
            <SvClientProvider url={config.services.sv.url}>
              <SvAppVotesHooksProvider>
                <SvAdminClientProvider url={config.services.sv.url}>
                  {children}
                </SvAdminClientProvider>
              </SvAppVotesHooksProvider>
            </SvClientProvider>
          </UserProvider>
        </QueryClientProvider>
      </AuthProvider>
    </LocalizationProvider>
  );
};

const App: React.FC = () => {
  const config = useSvConfig();
  const router = createBrowserRouter(
    createRoutesFromElements(
      <Route
        errorElement={<ErrorRouterPage />}
        element={
          <Providers>
            <AuthCheck authConfig={config.auth} testAuthConfig={config.testAuth} />
          </Providers>
        }
      >
        <Route path="/" element={<Root />}>
          <Route index element={<Dso />} />
          <Route path="dso" element={<Dso />} />
          <Route path="validator-onboarding" element={<ValidatorOnboarding />} />
          <Route path="amulet-price" element={<AmuletPrice />} />
          <Route path="votes" element={<Voting />} />
          <Route
            path="governance-beta"
            element={<Navigate to="/governance-beta/proposals" replace />}
          />
          <Route path="governance-beta/proposals" element={<Governance />} />
          <Route path="governance-beta/proposals/create" element={<CreateProposal />} />
          <Route path="governance-beta/proposals/:contractId" element={<VoteRequestDetails />} />
          <Route path="delegate" element={<Delegate />} />
        </Route>
      </Route>
    )
  );

  return (
    <ErrorBoundary>
      <ThemeProvider theme={theme}>
        <HelmetProvider>
          <Helmet>
            <title>Super Validator Operations</title>
            <meta name="description" content="Super Validator Operations" />
            <link rel="icon" href={config.spliceInstanceNames.networkFaviconUrl} />
          </Helmet>
          <CssBaseline />
          <RouterProvider router={router} />
        </HelmetProvider>
      </ThemeProvider>
    </ErrorBoundary>
  );
};

export default App;
