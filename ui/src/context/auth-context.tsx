import React, { useCallback, useMemo } from 'react';
import {
  useGetCurrentUser,
  useLoginWithAccessToken,
  useLoginWithPassword,
  useLoginWithToken,
  useLogoutCurrentUser,
  UserInterface,
} from '../hooks/user';
import { GrantType, TokenType } from '../types/api/control.gen';
import { useNotification } from '../utils/NotificationContext';

interface AuthContextProps {
  accessToken: any;
  loginWithToken: any;
  /** Administrator password sign-in; rejects on a wrong password. */
  loginWithPassword: (username: string, password: string) => Promise<void>;
  /** Sign-in with an access token this server issued; rejects on an invalid one. */
  loginWithAccessToken: (token: string) => Promise<void>;
  logout: any;
  currentUser: UserInterface | null;
  /** True until the first /scim2/Me answer, so callers can avoid flashing a login page. */
  currentUserPending: boolean;
}

const AuthContext = React.createContext<AuthContextProps>({
  accessToken: null,
  loginWithToken: null,
  loginWithPassword: async () => {},
  loginWithAccessToken: async () => {},
  logout: null,
  currentUser: null,
  currentUserPending: false,
});
AuthContext.displayName = 'AuthContext';

function AuthProvider(props: any) {
  const {
    data: currentUser,
    refetch,
    isPending: currentUserPending,
  } = useGetCurrentUser();
  const loginWithTokenMutation = useLoginWithToken();
  const loginWithPasswordMutation = useLoginWithPassword();
  const loginWithAccessTokenMutation = useLoginWithAccessToken();
  const logoutUser = useLogoutCurrentUser();
  const { setNotification } = useNotification();

  const loginWithToken = useCallback(
    async (idToken: string) => {
      return loginWithTokenMutation.mutate(
        {
          grant_type: GrantType.TOKEN_EXCHANGE,
          requested_token_type: TokenType.ACCESS_TOKEN,
          subject_token_type: TokenType.ID_TOKEN,
          subject_token: idToken,
        },
        {
          onSuccess: () => {
            refetch();
          },
          onError: () => {
            setNotification(
              'Login failed. Please contact your system administrator.',
              'error',
            );
          },
        },
      );
    },
    [loginWithTokenMutation, setNotification, refetch],
  );

  const loginWithPassword = useCallback(
    async (username: string, password: string) => {
      try {
        await loginWithPasswordMutation.mutateAsync({ username, password });
      } catch (error) {
        setNotification(
          'Login failed. Check the username and password.',
          'error',
        );
        throw error;
      }
      await refetch();
    },
    [loginWithPasswordMutation, setNotification, refetch],
  );

  const loginWithAccessToken = useCallback(
    async (token: string) => {
      try {
        await loginWithAccessTokenMutation.mutateAsync({ token });
      } catch (error) {
        setNotification('Login failed. Check the access token.', 'error');
        throw error;
      }
      await refetch();
    },
    [loginWithAccessTokenMutation, setNotification, refetch],
  );

  const logout = useCallback(async () => {
    return logoutUser.mutate(
      {},
      {
        onSuccess: () => {
          refetch();
        },
        onError: () => {
          setNotification(
            'Logout failed. Please contact your system administrator.',
            'error',
          );
        },
      },
    );
  }, [refetch, logoutUser, setNotification]);

  const value = useMemo(
    () => ({
      loginWithToken,
      loginWithPassword,
      loginWithAccessToken,
      logout,
      currentUser,
      currentUserPending,
    }),
    [
      loginWithToken,
      loginWithPassword,
      loginWithAccessToken,
      logout,
      currentUser,
      currentUserPending,
    ],
  );

  return <AuthContext.Provider value={value} {...props} />;
}

function useAuth() {
  const context = React.useContext(AuthContext);
  if (context === undefined) {
    throw new Error(`useAuth must be used within an AuthProvider`);
  }
  return context;
}

export { AuthProvider, useAuth };
