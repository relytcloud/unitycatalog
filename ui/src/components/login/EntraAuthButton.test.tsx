import { fireEvent, render, screen } from '@testing-library/react';
import EntraAuthButton, { hostedLoginUrl } from './EntraAuthButton';

describe('hostedLoginUrl', () => {
  it('points at the server-hosted login with the return path encoded', () => {
    expect(hostedLoginUrl('/data/demo?tab=tables')).toBe(
      '/api/1.0/unity-control/auth/login?redirect=%2Fdata%2Fdemo%3Ftab%3Dtables',
    );
  });

  it('defaults to the root', () => {
    expect(hostedLoginUrl(undefined)).toBe(
      '/api/1.0/unity-control/auth/login?redirect=%2F',
    );
    expect(hostedLoginUrl('')).toBe(
      '/api/1.0/unity-control/auth/login?redirect=%2F',
    );
  });

  /** A crafted link must not be able to bounce the user to another site after login. */
  it('refuses absolute and scheme-relative return targets', () => {
    expect(hostedLoginUrl('https://evil.example/')).toBe(
      '/api/1.0/unity-control/auth/login?redirect=%2F',
    );
    expect(hostedLoginUrl('//evil.example/x')).toBe(
      '/api/1.0/unity-control/auth/login?redirect=%2F',
    );
  });
});

describe('EntraAuthButton', () => {
  it('navigates the browser to the hosted login', () => {
    const assign = jest.fn();
    const original = window.location;
    Object.defineProperty(window, 'location', {
      value: { ...original, assign },
      writable: true,
    });

    render(<EntraAuthButton returnTo="/data/demo" />);
    fireEvent.click(
      screen.getByRole('button', { name: /Sign in with Microsoft/ }),
    );

    expect(assign).toHaveBeenCalledWith(
      '/api/1.0/unity-control/auth/login?redirect=%2Fdata%2Fdemo',
    );

    Object.defineProperty(window, 'location', {
      value: original,
      writable: true,
    });
  });
});
