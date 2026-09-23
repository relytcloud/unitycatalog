import { fireEvent, screen, waitFor } from '@testing-library/react';
import { renderWithProviders } from '../test-utils/render';
import TokenLoginPage from './TokenLogin';
import { useAuth } from '../context/auth-context';

jest.mock('../context/client', () => ({ CLIENT: { request: jest.fn() } }));
jest.mock('../context/auth-context', () => ({ useAuth: jest.fn() }));

const mockUseAuth = useAuth as jest.Mock;

describe('TokenLoginPage', () => {
  const loginWithAccessToken = jest.fn();

  beforeEach(() => {
    jest.clearAllMocks();
    mockUseAuth.mockReturnValue({ loginWithAccessToken });
  });

  it('signs in with the pasted token', async () => {
    loginWithAccessToken.mockResolvedValue(undefined);
    renderWithProviders(<TokenLoginPage />);

    fireEvent.change(screen.getByLabelText('Unity Catalog access token'), {
      target: { value: '  header.payload.signature  ' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() =>
      expect(loginWithAccessToken).toHaveBeenCalledWith(
        'header.payload.signature',
      ),
    );
  });

  it('says so when the server rejects the token', async () => {
    loginWithAccessToken.mockRejectedValue(new Error('401'));
    renderWithProviders(<TokenLoginPage />);

    fireEvent.change(screen.getByLabelText('Unity Catalog access token'), {
      target: { value: 'stale.token.here' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(
      await screen.findByText('Token is not valid for sign-in'),
    ).toBeInTheDocument();
  });

  /** A bookmarkable link signs in by itself, and the token leaves the address bar. */
  it('uses a token given in the query string exactly once', async () => {
    loginWithAccessToken.mockResolvedValue(undefined);
    renderWithProviders(<TokenLoginPage />, {
      route: '/login/token?token=header.payload.signature',
    });

    await waitFor(() =>
      expect(loginWithAccessToken).toHaveBeenCalledWith(
        'header.payload.signature',
      ),
    );
    expect(loginWithAccessToken).toHaveBeenCalledTimes(1);
  });
});
