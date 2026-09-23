import { useQuery } from '@tanstack/react-query';
import { CLIENT } from '../context/client';
import { UC_AUTH_API_PREFIX } from '../utils/constants';
import { route, isError } from '../utils/openapi';
import type {
  paths as ControlApi,
  components as ControlComponent,
} from '../types/api/control.gen';
import type { Model, Route } from '../utils/openapi';

export interface AuthProvidersInterface
  extends Model<ControlComponent, 'AuthProviders'> {}

/**
 * What the server offers for signing in, asked at runtime rather than baked
 * in at build time: whether a token is required at all, whether the
 * server-hosted Microsoft sign-in is configured, and whether the administrator
 * password entry point is. One UI build thereby follows the server's
 * configuration.
 *
 * The endpoint is public. A server that predates it (404) is treated as
 * offering nothing, which leaves the build-time Google switch as the only
 * gate -- the behaviour such a server had before.
 */
export function useAuthProviders() {
  return useQuery<AuthProvidersInterface>({
    queryKey: ['authProviders'],
    staleTime: Infinity,
    queryFn: async () => {
      try {
        const response = await (route as Route<ControlApi>)({
          client: CLIENT,
          request: { path: '/auth/providers', method: 'get' },
          config: { baseURL: UC_AUTH_API_PREFIX },
          errorMessage: 'Failed to fetch sign-in providers',
        }).call();
        if (isError(response)) {
          return NONE;
        }
        return response.data;
      } catch {
        return NONE;
      }
    },
  });
}

const NONE: AuthProvidersInterface = {
  authorization_enabled: false,
  hosted_login: false,
  admin_login: false,
};
