import { useMutation, useQuery } from '@tanstack/react-query';
import { CLIENT } from '../context/client';
import { TokenEndpointExtensionType } from '../types/api/control.gen';
import { UC_AUTH_API_PREFIX } from '../utils/constants';
import { route, isError, assertNever } from '../utils/openapi';
import type {
  paths as ControlApi,
  components as ControlComponent,
} from '../types/api/control.gen';
import type {
  Model,
  RequestBody,
  ErrorResponseBody,
  Route,
  SuccessResponseBody,
} from '../utils/openapi';

export interface OAuthTokenExchangeInterface
  extends Model<ControlComponent, 'OAuthTokenExchangeInfo'> {}

export interface LoginWithTokenMutationParams
  extends RequestBody<
    ControlApi,
    '/auth/tokens',
    'post',
    'application/x-www-form-urlencoded'
  > {}

export function useLoginWithToken() {
  return useMutation<
    OAuthTokenExchangeInterface,
    Error,
    LoginWithTokenMutationParams
  >({
    mutationFn: async (params: LoginWithTokenMutationParams) => {
      const response = await (route as Route<ControlApi>)({
        client: CLIENT,
        request: {
          path: '/auth/tokens',
          method: 'post',
          params: {
            query: {
              ext: TokenEndpointExtensionType.cookie,
            },
            body: params,
          },
        },
        config: {
          baseURL: UC_AUTH_API_PREFIX,
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        },
        errorMessage: 'Failed to login',
      }).call();
      if (isError(response)) {
        // NOTE:
        // When an expected error occurs, as defined in the OpenAPI specification, the following line will
        // be executed. This block serves as a placeholder for expected errors.
        return assertNever(response.data.status);
      } else {
        return response.data;
      }
    },
  });
}

export interface LoginWithPasswordMutationParams
  extends RequestBody<
    ControlApi,
    '/auth/admin/login',
    'post',
    'application/x-www-form-urlencoded'
  > {}

/**
 * The administrator's password sign-in. Like the token exchange it asks for the
 * session to be set as a cookie, so the browser is signed in the same way a
 * Microsoft sign-in leaves it.
 */
export function useLoginWithPassword() {
  return useMutation<
    OAuthTokenExchangeInterface,
    Error,
    LoginWithPasswordMutationParams
  >({
    mutationFn: async (params: LoginWithPasswordMutationParams) => {
      const response = await (route as Route<ControlApi>)({
        client: CLIENT,
        request: {
          path: '/auth/admin/login',
          method: 'post',
          params: { body: params },
        },
        config: {
          baseURL: UC_AUTH_API_PREFIX,
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        },
        errorMessage: 'Failed to login',
      }).call();
      if (isError(response)) {
        return assertNever(response.data.status);
      } else {
        return response.data;
      }
    },
  });
}

export interface LoginWithAccessTokenMutationParams
  extends RequestBody<
    ControlApi,
    '/auth/token/login',
    'post',
    'application/x-www-form-urlencoded'
  > {}

/**
 * Opens a session from an access token this server already issued, which is how
 * an operator holding the token in etc/conf/token.txt reaches the UI. The token
 * itself becomes the session cookie; the server verifies it first.
 */
export function useLoginWithAccessToken() {
  return useMutation<
    OAuthTokenExchangeInterface,
    Error,
    LoginWithAccessTokenMutationParams
  >({
    mutationFn: async (params: LoginWithAccessTokenMutationParams) => {
      const response = await (route as Route<ControlApi>)({
        client: CLIENT,
        request: {
          path: '/auth/token/login',
          method: 'post',
          params: { body: params },
        },
        config: {
          baseURL: UC_AUTH_API_PREFIX,
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        },
        errorMessage: 'Failed to login',
      }).call();
      if (isError(response)) {
        return assertNever(response.data.status);
      } else {
        return response.data;
      }
    },
  });
}

export interface LogoutCurrentUserMutationParams {}

export function useLogoutCurrentUser() {
  return useMutation<
    SuccessResponseBody<ControlApi, '/auth/logout', 'post'>,
    Error,
    LogoutCurrentUserMutationParams
  >({
    mutationFn: async () => {
      const response = await (route as Route<ControlApi>)({
        client: CLIENT,
        request: {
          path: '/auth/logout',
          method: 'post',
        },
        config: {
          baseURL: UC_AUTH_API_PREFIX,
        },
        errorMessage: 'Failed to logout',
      }).call();
      if (isError(response)) {
        // NOTE:
        // When an expected error occurs, as defined in the OpenAPI specification, the following line will
        // be executed. This block serves as a placeholder for expected errors.
        return assertNever(response.data.status);
      } else {
        return response.data;
      }
    },
  });
}

export interface UserInterface
  extends Model<ControlComponent, 'UserResource'> {}

export function useGetCurrentUser() {
  const expectedErrorCodes = [
    401, // UNAUTHORIZED
  ] as const;

  type ErrorCode = (typeof expectedErrorCodes)[number];

  const isExpectedError = (response: {
    status: number;
    data: any;
  }): response is ErrorResponseBody<
    ControlApi,
    '/scim2/Me',
    'get',
    ErrorCode
  > => expectedErrorCodes.map(Number).includes(response.status);

  return useQuery<SuccessResponseBody<ControlApi, '/scim2/Me', 'get'> | null>({
    queryKey: ['getUser'],
    queryFn: async () => {
      const response = await (route as Route<ControlApi>)({
        client: CLIENT,
        request: {
          path: '/scim2/Me',
          method: 'get',
        },
        config: {
          baseURL: UC_AUTH_API_PREFIX,
        },
        errorMessage: 'Failed to fetch user',
        errorTypeGuard: isExpectedError,
      }).call();
      if (isError(response)) {
        switch (response.data.status) {
          case 401:
            return null;
          default:
            return assertNever(response.data.status);
        }
      } else {
        return response.data;
      }
    },
  });
}
