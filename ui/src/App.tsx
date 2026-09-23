import React, { useMemo } from 'react';
import {
  Avatar,
  ConfigProvider,
  Dropdown,
  Layout,
  Menu,
  MenuProps,
  Typography,
} from 'antd';
import {
  createBrowserRouter,
  Navigate,
  Outlet,
  RouteObject,
  RouterProvider,
  useLocation,
  useNavigate,
} from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { QUERY_STALE_TIME } from './utils/constants';

import SchemaBrowser from './components/SchemaBrowser';
import TableDetails from './pages/TableDetails';
import FunctionDetails from './pages/FunctionDetails';
import VolumeDetails from './pages/VolumeDetails';
import CatalogsList from './pages/CatalogsList';
import CatalogDetails from './pages/CatalogDetails';
import SchemaDetails from './pages/SchemaDetails';
import { NotificationProvider } from './utils/NotificationContext';
import ModelDetails from './pages/ModelDetails';
import Login from './pages/Login';
import AdminLogin from './pages/AdminLogin';
import TokenLogin from './pages/TokenLogin';
import { useAuthProviders } from './hooks/auth-providers';
import { AuthProvider, useAuth } from './context/auth-context';
import { UserOutlined } from '@ant-design/icons';
import ModelVersionDetails from './pages/ModelVersionDetails';
import ExternalData from './pages/ExternalData';
import CredentialDetails from './pages/CredentialDetails';
import ExternalLocationDetails from './pages/ExternalLocationDetails';
import UsersList from './pages/UsersList';
import ResizableSplit from './components/layouts/ResizableSplit';

// TODO:
// As of [19/02/2025], this implementation should be updated once the following PR are merged.
// SEE:
// https://github.com/unitycatalog/unitycatalog/pull/809
// Google sign-in is the one provider still decided at build time (it runs an
// OAuth client in the browser). Whether sign-in is required at all, whether
// Microsoft sign-in is offered and whether the administrator password is set
// are asked of the server at runtime (useAuthProviders), so a single UI build
// follows the server's configuration.
const buildTimeAuth = process.env.REACT_APP_GOOGLE_AUTH_ENABLED === 'true';

// The sign-in entry points, each an address of its own. /login is the default
// one people are sent to; the other two are for whoever runs the server and are
// not linked from it. The application itself is never an entry point: without a
// session it redirects here.
export const LOGIN_PATH = '/login';
export const ADMIN_LOGIN_PATH = '/login/admin';
export const TOKEN_LOGIN_PATH = '/login/token';

/** The route table, exported so tests can mount it on a memory router. */
export const appRoutes: RouteObject[] = [
  {
    path: LOGIN_PATH,
    element: (
      <SignInRoute>
        <Login />
      </SignInRoute>
    ),
  },
  {
    path: ADMIN_LOGIN_PATH,
    element: (
      <SignInRoute>
        <AdminLogin />
      </SignInRoute>
    ),
  },
  {
    path: TOKEN_LOGIN_PATH,
    element: (
      <SignInRoute>
        <TokenLogin />
      </SignInRoute>
    ),
  },
  {
    element: <AppProvider />,
    children: [
      {
        path: '/',
        element: <CatalogsList />,
      },
      {
        path: '/data/:catalog',
        element: <CatalogDetails />,
      },
      {
        path: '/data/:catalog/:schema',
        element: <SchemaDetails />,
      },
      {
        path: '/data/:catalog/:schema/:table',
        element: <TableDetails />,
      },
      {
        path: '/volumes/:catalog/:schema/:volume',
        element: <VolumeDetails />,
      },
      {
        path: '/functions/:catalog/:schema/:ucFunction',
        element: <FunctionDetails />,
      },
      {
        path: '/models/:catalog/:schema/:model',
        element: <ModelDetails />,
      },
      {
        path: '/models/:catalog/:schema/:model/versions/:version',
        element: <ModelVersionDetails />,
      },
      {
        path: '/external-data',
        element: <Navigate to="/external-data/external-locations" replace />,
      },
      {
        path: '/external-data/external-locations',
        element: <ExternalData />,
      },
      {
        path: '/external-data/credentials',
        element: <ExternalData />,
      },
      {
        path: '/external-data/external-locations/:name',
        element: <ExternalLocationDetails />,
      },
      {
        path: '/external-data/credentials/:name',
        element: <CredentialDetails />,
      },
      {
        path: '/users',
        element: <UsersList />,
      },
    ],
  },
];

const router = createBrowserRouter(appRoutes);

/**
 * Whether a session is needed, whether there is one, and whether either is
 * still being established. Both the application and the sign-in pages decide
 * from this, so they cannot disagree about who is signed in.
 */
function useSignInState() {
  const { currentUser, currentUserPending } = useAuth();
  const { data: providers, isPending: providersPending } = useAuthProviders();
  const loginRequired =
    buildTimeAuth || providers?.authorization_enabled === true;
  // Until the server has said whether sign-in is required, and then until the
  // session has been checked, show neither the login page nor the app:
  // flashing either one misleads.
  const pending =
    (!buildTimeAuth && providersPending) ||
    (loginRequired && currentUserPending);
  return { loginRequired, pending, currentUser };
}

/** A sign-in page: pointless once signed in, and when nothing requires a session. */
function SignInRoute({ children }: { children: React.ReactNode }) {
  const { loginRequired, pending, currentUser } = useSignInState();

  if (pending) {
    return <p>Loading...</p>;
  }
  if (currentUser || !loginRequired) {
    return <Navigate to="/" replace />;
  }
  return <>{children}</>;
}

function AppProvider() {
  const { logout, currentUser } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const { pathname } = location;
  const { loginRequired, pending } = useSignInState();

  const selectedNavKey = pathname.startsWith('/external-data')
    ? 'external-data'
    : pathname.startsWith('/users')
      ? 'users'
      : 'catalogs';

  const profileMenuItems = useMemo(
    (): MenuProps['items'] => [
      {
        key: 'userInfo',
        label: (
          <div
            style={{
              display: 'flex',
              flexDirection: 'column',
              cursor: 'default',
            }}
          >
            <Typography.Text>{currentUser?.displayName}</Typography.Text>
            <Typography.Text>{currentUser?.emails?.[0]?.value}</Typography.Text>
          </div>
        ),
      },
      {
        type: 'divider',
      },
      {
        key: 'logout',
        label: 'Log out',
        onClick: () => logout().then(() => navigate('/')),
      },
    ],
    [currentUser, logout, navigate],
  );

  if (pending) {
    return <p>Loading...</p>;
  }
  // No session: the address people land on is the login page, not this one.
  if (loginRequired && !currentUser) {
    return (
      <Navigate
        to={LOGIN_PATH}
        state={{ from: pathname + location.search }}
        replace
      />
    );
  }

  return (
    <ConfigProvider
      theme={{
        components: {
          Typography: {
            titleMarginBottom: 0,
            titleMarginTop: 0,
          },
        },
      }}
    >
      <Layout>
        {/* Header */}
        <Layout.Header
          style={{
            display: 'flex',
            alignItems: 'center',
            width: '100%',
            justifyContent: 'space-between',
          }}
        >
          <div
            style={{ display: 'flex', alignItems: 'center', flex: '1 0 auto' }}
          >
            <div style={{ marginRight: 24 }} onClick={() => navigate('/')}>
              <img
                src="/uc-logo-reverse.png"
                height={32}
                alt="uc-logo-reverse"
              />
            </div>
            <Menu
              theme="dark"
              mode="horizontal"
              selectedKeys={[selectedNavKey]}
              items={[
                {
                  key: 'catalogs',
                  label: 'Catalogs',
                  onClick: () => navigate('/'),
                },
                {
                  key: 'external-data',
                  label: 'External Data',
                  onClick: () => navigate('/external-data'),
                },
                {
                  key: 'users',
                  label: 'Users',
                  onClick: () => navigate('/users'),
                },
              ]}
              style={{ flex: 1, minWidth: 0 }}
            />
          </div>
          {loginRequired && (
            <div>
              <Dropdown
                menu={{ items: profileMenuItems }}
                trigger={['click']}
                placement={'bottomRight'}
              >
                <Avatar
                  icon={<UserOutlined />}
                  style={{
                    backgroundColor: 'white',
                    color: 'black',
                    cursor: 'pointer',
                  }}
                />
              </Dropdown>
            </div>
          )}
        </Layout.Header>
        {/* Content */}
        <Layout.Content
          style={{
            height: 'calc(100vh - 64px)',
            backgroundColor: '#fff',
            display: 'flex',
          }}
        >
          {/* Draggable split: schema browser (left, fixed px) ↔ details. */}
          <ResizableSplit
            fixed="left"
            defaultSize={340}
            minSize={220}
            maxSize={640}
            storageKey="uc-ui-split-browser"
            left={
              <div style={{ overflowY: 'auto', height: '100%' }}>
                <SchemaBrowser />
              </div>
            }
            right={
              <div
                style={{
                  overflowY: 'auto',
                  flex: 1,
                  padding: 16,
                  display: 'flex',
                }}
              >
                <Outlet />
              </div>
            }
          />
        </Layout.Content>
      </Layout>
    </ConfigProvider>
  );
}

function App() {
  const queryClient = new QueryClient({
    defaultOptions: {
      // No retry: these are authenticated admin CRUD reads — a 401/403/404 is
      // deterministic, and the default 3 retries just delayed the error state
      // by several seconds of blank UI.
      queries: { staleTime: QUERY_STALE_TIME, retry: false },
    },
  });

  // AuthProvider is always mounted: whether a session is required is decided
  // per request by AppProvider from what the server reports, not at build time.
  return (
    <NotificationProvider>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <RouterProvider router={router} fallbackElement={<p>Loading...</p>} />
        </AuthProvider>
      </QueryClientProvider>
    </NotificationProvider>
  );
}

export default App;
