import { screen } from '@testing-library/react';
import { renderWithProviders } from '../test-utils/render';
import LoginPage from './Login';

jest.mock('../context/client', () => ({ CLIENT: { request: jest.fn() } }));
// The third-party sign-in widgets touch browser APIs jsdom lacks at import
// time (the Okta widget reads canvas pixel ratios). They are not what this page
// test is about; stub them so the page's provider gating can be exercised.
jest.mock('../components/login/OktaAuthButton', () => () => (
  <button>Continue with Okta</button>
));
jest.mock('../components/login/GoogleAuthButton', () => () => (
  <button>Continue with Google</button>
));
jest.mock('../hooks/auth-providers', () => ({ useAuthProviders: jest.fn() }));
jest.mock('../context/auth-context', () => ({
  useAuth: () => ({ loginWithToken: jest.fn() }),
}));

const mockUseAuthProviders = jest.requireMock('../hooks/auth-providers')
  .useAuthProviders as jest.Mock;
function serverOffers(hosted_login: boolean) {
  mockUseAuthProviders.mockReturnValue({
    data: { authorization_enabled: true, hosted_login, admin_login: true },
    isPending: false,
  });
}

/**
 * The login page shows one button per enabled provider. Microsoft sign-in is
 * offered when the server reports hosting it; the administrator entry point is
 * never linked from here.
 */
describe('LoginPage', () => {
  const env = process.env;

  afterEach(() => {
    process.env = env;
  });

  it('offers Microsoft sign-in when the server hosts it', () => {
    serverOffers(true);

    renderWithProviders(<LoginPage />);

    expect(
      screen.getByRole('button', { name: /Sign in with Microsoft/ }),
    ).toBeInTheDocument();
    expect(
      screen.queryByText('No sign-in provider is configured on this server'),
    ).toBeNull();
    // Even with admin login configured, this page does not advertise it.
    expect(screen.queryByText(/administrator/i)).toBeNull();
  });

  it('reports no providers when none is enabled', () => {
    serverOffers(false);
    process.env = {
      ...env,
      REACT_APP_GOOGLE_AUTH_ENABLED: 'false',
      REACT_APP_OKTA_AUTH_ENABLED: 'false',
      REACT_APP_KEYCLOAK_AUTH_ENABLED: 'false',
    };

    renderWithProviders(<LoginPage />);

    expect(
      screen.getByText('No sign-in provider is configured on this server'),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: /Sign in with Microsoft/ }),
    ).toBeNull();
  });
});
