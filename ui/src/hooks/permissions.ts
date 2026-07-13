import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CLIENT } from '../context/client';
import { route, isError, assertNever } from '../utils/openapi';
import type {
  paths as CatalogApi,
  components as CatalogComponent,
} from '../types/api/catalog.gen';
import { SecurableType } from '../types/api/catalog.gen';
import type {
  Model,
  PathParam,
  RequestBody,
  Route,
  SuccessResponseBody,
} from '../utils/openapi';

export interface PrivilegeAssignmentInterface
  extends Model<CatalogComponent, 'PrivilegeAssignment'> {}

export type PrivilegeType = Model<CatalogComponent, 'Privilege'>;

export interface UseGetPermissionsArgs
  extends PathParam<
    CatalogApi,
    '/permissions/{securable_type}/{full_name}',
    'get'
  > {
  principal?: string;
}

export function useGetPermissions({
  securable_type,
  full_name,
  principal,
}: UseGetPermissionsArgs) {
  return useQuery<
    SuccessResponseBody<
      CatalogApi,
      '/permissions/{securable_type}/{full_name}',
      'get'
    >
  >({
    queryKey: ['getPermissions', securable_type, full_name, principal],
    queryFn: async () => {
      const response = await (route as Route<CatalogApi>)({
        client: CLIENT,
        request: {
          path: '/permissions/{securable_type}/{full_name}',
          method: 'get',
          params: {
            paths: {
              securable_type,
              full_name,
            },
            query: principal ? { principal } : undefined,
          },
        },
        errorMessage: 'Failed to fetch permissions',
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

export interface UpdatePermissionsMutationParams
  extends RequestBody<
    CatalogApi,
    '/permissions/{securable_type}/{full_name}',
    'patch'
  > {
  securable_type: PathParam<
    CatalogApi,
    '/permissions/{securable_type}/{full_name}',
    'patch'
  >['securable_type'];
  full_name: string;
}

export function useUpdatePermissions() {
  const queryClient = useQueryClient();

  return useMutation<
    SuccessResponseBody<
      CatalogApi,
      '/permissions/{securable_type}/{full_name}',
      'patch'
    >,
    Error,
    UpdatePermissionsMutationParams
  >({
    mutationFn: async ({
      securable_type,
      full_name,
      changes,
    }: UpdatePermissionsMutationParams) => {
      const response = await (route as Route<CatalogApi>)({
        client: CLIENT,
        request: {
          path: '/permissions/{securable_type}/{full_name}',
          method: 'patch',
          params: {
            paths: {
              securable_type,
              full_name,
            },
            body: {
              changes,
            },
          },
        },
        // NOTE:
        // Grant/revoke are authorized on the server (securable OWNER); a 403
        // message from the server is surfaced to the caller as-is.
        errorMessage: 'Failed to update permissions',
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
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['getPermissions'] });
      queryClient.invalidateQueries({ queryKey: ['userPermissions'] });
    },
  });
}

export interface SecurableRef {
  securable_type: SecurableType;
  full_name: string;
}

export interface UserSecurablePrivileges extends SecurableRef {
  privileges: PrivilegeType[];
}

/**
 * Aggregates one principal's privileges across a bounded set of securables
 * (metastore, catalogs, credentials, external locations) with one filtered
 * GET per securable. Securables the caller cannot read are treated as
 * "no privileges" rather than failing the whole view.
 */
export function useUserPermissions(
  principal: string | undefined,
  securables: SecurableRef[],
) {
  return useQuery<UserSecurablePrivileges[]>({
    queryKey: [
      'userPermissions',
      principal,
      securables
        .map(
          (securable) => `${securable.securable_type}:${securable.full_name}`,
        )
        .join(','),
    ],
    enabled: !!principal && securables.length > 0,
    queryFn: async () => {
      const wanted = (principal ?? '').toLowerCase();
      // Bound the fan-out: a metastore with many catalogs/credentials/locations
      // would otherwise fire hundreds of simultaneous permission GETs.
      const CONCURRENCY = 8;
      const results: UserSecurablePrivileges[] = [];
      let failures = 0;
      const fetchOne = async (
        securable: SecurableRef,
      ): Promise<UserSecurablePrivileges & { failed: boolean }> => {
        try {
          const response = await (route as Route<CatalogApi>)({
            client: CLIENT,
            request: {
              path: '/permissions/{securable_type}/{full_name}',
              method: 'get',
              params: {
                paths: {
                  securable_type: securable.securable_type,
                  full_name: securable.full_name,
                },
                query: { principal },
              },
            },
            errorMessage: 'Failed to fetch permissions',
          }).call();
          if (isError(response)) {
            return { ...securable, privileges: [], failed: true };
          }
          // The server returns EVERY principal's assignments and ignores the
          // ?principal filter, so filter to this user here (case-insensitive
          // to match SCIM email semantics). Without this the drawer would
          // attribute other users' privileges to this one.
          const assignments = response.data.privilege_assignments ?? [];
          return {
            ...securable,
            failed: false,
            privileges: assignments
              .filter(
                (assignment) =>
                  (assignment.principal ?? '').toLowerCase() === wanted,
              )
              .flatMap((assignment) => assignment.privileges ?? []),
          };
        } catch {
          return { ...securable, privileges: [], failed: true };
        }
      };
      for (let i = 0; i < securables.length; i += CONCURRENCY) {
        const batch = securables.slice(i, i + CONCURRENCY);
        const batchResults = await Promise.all(batch.map(fetchOne));
        for (const { failed, ...result } of batchResults) {
          if (failed) failures += 1;
          results.push(result);
        }
      }
      // Distinguish "user genuinely has no grants" from "every request failed"
      // (expired session, server down): the latter must surface as an error,
      // not a misleading empty "no privileges" table.
      if (securables.length > 0 && failures === securables.length) {
        throw new Error('Failed to load this user’s permissions');
      }
      return results.filter((result) => result.privileges.length > 0);
    },
  });
}
