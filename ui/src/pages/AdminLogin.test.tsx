import { fireEvent, screen, waitFor } from '@testing-library/react';
import { renderWithProviders } from '../test-utils/render';
import AdminLoginPage from './AdminLogin';
import { useAuth } from '../context/auth-context';
import { useAuthProviders } from '../hooks/auth-providers';

jest.mock('../context/client', () => ({ CLIENT: { request: jest.fn() } }));
jest.mock('../context/auth-context', () => ({ useAuth: jest.fn() }));
jest.mock('../hooks/auth-providers', () => ({ useAuthProviders: jest.fn() }));

const mockUseAuth = useAuth as jest.Mock;
const mockUseAuthProviders = useAuthProviders as jest.Mock;

function providers(admin_login: boolean) {
  mockUseAuthProviders.mockReturnValue({
    data: { authorization_enabled: true, hosted_login: false, admin_login },
    isPending: false,
  });
}

describe('AdminLoginPage', () => {
  const loginWithPassword = jest.fn();

  beforeEach(() => {
    jest.clearAllMocks();
    mockUseAuth.mockReturnValue({ loginWithPassword });
  });

  it('signs in with the entered credentials', async () => {
    providers(true);
    loginWithPassword.mockResolvedValue(undefined);
    renderWithProviders(<AdminLoginPage />);

    // The username defaults to the built-in administrator.
    expect(screen.getByLabelText('Username')).toHaveValue('admin');
    fireEvent.change(screen.getByLabelText('Password'), {
      target: { value: 's3cret' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() =>
      expect(loginWithPassword).toHaveBeenCalledWith('admin', 's3cret'),
    );
  });

  it('shows a clear failure and no session on a rejected password', async () => {
    providers(true);
    loginWithPassword.mockRejectedValue(new Error('401'));
    renderWithProviders(<AdminLoginPage />);

    fireEvent.change(screen.getByLabelText('Password'), {
      target: { value: 'wrong' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(
      await screen.findByText('Wrong username or password'),
    ).toBeInTheDocument();
  });

  /** The page exists whether or not the server has a password; it must say which. */
  it('explains when the server has no administrator password', () => {
    providers(false);
    renderWithProviders(<AdminLoginPage />);

    expect(
      screen.getByText(
        'Administrator sign-in is not configured on this server',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sign in' })).toBeNull();
  });
});
