import { render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { NotificationProvider } from './utils/NotificationContext';
import { appRoutes } from './App';
import { useAuth } from './context/auth-context';
import { useAuthProviders } from './hooks/auth-providers';
import { useIsMetastoreAdmin } from './hooks/authz';

// The real route table is under test, so only the leaves are replaced: the
// identity hooks, the sign-in buttons that need a live SDK, and the two heavy
// pages behind the gate.
jest.mock('./context/auth-context', () => ({
  AuthProvider: ({ children }: { children: React.ReactNode }) => (
    <>{children}</>
  ),
  useAuth: jest.fn(),
}));
jest.mock('./hooks/auth-providers', () => ({ useAuthProviders: jest.fn() }));
jest.mock('./context/client', () => ({ CLIENT: { request: jest.fn() } }));
jest.mock(
  './components/login/OktaAuthButton',
  () =>
    function OktaAuthButton() {
      return <div />;
    },
);
jest.mock(
  './components/login/GoogleAuthButton',
  () =>
    function GoogleAuthButton() {
      return <div />;
    },
);
jest.mock(
  './components/login/KeycloakAuthButton',
  () =>
    function KeycloakAuthButton() {
      return <div />;
    },
);
jest.mock(
  './components/SchemaBrowser',
  () =>
    function SchemaBrowser() {
      return <div />;
    },
);
jest.mock(
  './pages/CatalogsList',
  () =>
    function CatalogsList() {
      return <div>Catalogs page</div>;
    },
);
jest.mock(
  './pages/UsersList',
  () =>
    function UsersList() {
      return <div>Users page</div>;
    },
);
jest.mock('./hooks/authz', () => ({
  ...jest.requireActual('./hooks/authz'),
  useIsMetastoreAdmin: jest.fn(),
}));

const mockUseAuth = useAuth as jest.Mock;
const mockUseAuthProviders = useAuthProviders as jest.Mock;
const mockUseIsMetastoreAdmin = useIsMetastoreAdmin as jest.Mock;

function metastoreAdmin(isAdmin: boolean) {
  mockUseIsMetastoreAdmin.mockReturnValue({ data: isAdmin, isPending: false });
}

function signedIn(user: object | null) {
  mockUseAuth.mockReturnValue({
    currentUser: user,
    currentUserPending: false,
    logout: jest.fn(),
  });
}

/** Mounts the application's own routes at an address. */
function openAt(path: string) {
  const router = createMemoryRouter(appRoutes, { initialEntries: [path] });
  render(
    <NotificationProvider>
      <QueryClientProvider
        client={
          new QueryClient({ defaultOptions: { queries: { retry: false } } })
        }
      >
        <RouterProvider router={router} />
      </QueryClientProvider>
    </NotificationProvider>,
  );
  return router;
}

describe('App routing', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockUseAuthProviders.mockReturnValue({
      data: {
        authorization_enabled: true,
        hosted_login: true,
        admin_login: true,
      },
      isPending: false,
    });
    metastoreAdmin(false);
  });

  /**
   * The user directory belongs to the metastore administrators: only they may
   * create, deactivate or delete accounts. An ordinary user gets neither the
   * menu entry nor the page, even by address.
   */
  it('keeps an ordinary user out of the user directory', async () => {
    signedIn({ id: '1', displayName: 'Someone' });

    const router = openAt('/users');

    expect(await screen.findByText('Catalogs page')).toBeVisible();
    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
    expect(screen.queryByText('Users page')).toBeNull();
    expect(screen.queryByRole('menuitem', { name: 'Users' })).toBeNull();
  });

  it('shows the user directory to a metastore administrator', async () => {
    metastoreAdmin(true);
    signedIn({ id: '1', displayName: 'Admin' });

    const router = openAt('/users');

    expect(await screen.findByText('Users page')).toBeVisible();
    expect(router.state.location.pathname).toBe('/users');
    expect(screen.getByRole('menuitem', { name: 'Users' })).toBeVisible();
  });

  it('sends a visitor without a session away from the application', async () => {
    signedIn(null);

    const router = openAt('/');

    expect(await screen.findByText('Login to Unity Catalog')).toBeVisible();
    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
  });

  it('keeps the administrator form on an address of its own', async () => {
    signedIn(null);

    openAt('/login/admin');

    expect(await screen.findByText('Administrator sign-in')).toBeVisible();
  });

  it('keeps the token sign-in on an address of its own', async () => {
    signedIn(null);

    openAt('/login/token');

    expect(await screen.findByText('Sign in with a token')).toBeVisible();
  });

  /** Once signed in the sign-in pages have nothing to offer. */
  it('sends a signed-in visitor from a sign-in page to the application', async () => {
    signedIn({ id: '1', displayName: 'Admin' });

    const router = openAt('/login/admin');

    expect(await screen.findByText('Catalogs page')).toBeVisible();
    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
  });
});
