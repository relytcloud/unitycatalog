import { useMemo } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CLIENT } from '../context/client';
import { UC_AUTH_API_PREFIX } from '../utils/constants';
import { route, isError, assertNever } from '../utils/openapi';
import type {
  paths as ControlApi,
  components as ControlComponent,
} from '../types/api/control.gen';
import type {
  Model,
  RequestBody,
  Route,
  SuccessResponseBody,
} from '../utils/openapi';

export interface ScimUserInterface
  extends Model<ControlComponent, 'UserResource'> {}

export function useListScimUsers() {
  return useQuery<
    SuccessResponseBody<
      ControlApi,
      '/scim2/Users',
      'get',
      'application/scim+json'
    >
  >({
    queryKey: ['listScimUsers'],
    queryFn: async () => {
      // SCIM uses 1-based startIndex/count offset pagination. Walk every page
      // (server caps count per response) so the Users table and the grant
      // principal picker see the full directory, not just the first page.
      const PAGE = 100;
      const all: ScimUserInterface[] = [];
      let startIndex = 1;
      for (;;) {
        const response = await (route as Route<ControlApi>)({
          client: CLIENT,
          request: {
            path: '/scim2/Users',
            method: 'get',
            params: {
              query: { startIndex, count: PAGE },
            },
          },
          config: {
            baseURL: UC_AUTH_API_PREFIX,
          },
          errorMessage: 'Failed to fetch users',
        }).call();
        if (isError(response)) {
          // NOTE:
          // When an expected error occurs, as defined in the OpenAPI specification, the following line will
          // be executed. This block serves as a placeholder for expected errors.
          return assertNever(response.data.status);
        }
        const page = response.data.Resources ?? [];
        all.push(...page);
        const total = response.data.totalResults ?? all.length;
        // Stop on a short page, when we've collected the reported total, or if
        // the server ignored startIndex (no forward progress) — never loop.
        if (page.length === 0 || page.length < PAGE || all.length >= total) {
          break;
        }
        startIndex += page.length;
      }
      return { Resources: all, totalResults: all.length };
    },
  });
}

/**
 * Users as antd Select options for principal pickers, searched with contains
 * (%xx%) semantics on name AND email. The option value is the email (the
 * grant principal), deduped so two accounts sharing an address don't produce
 * duplicate option values.
 */
export function useScimUserOptions() {
  const { data } = useListScimUsers();
  // Memoize: rebuilding the Set + options array on every render is wasted work
  // for a directory that only changes on a mutation (which invalidates the
  // query and produces a fresh `data`).
  return useMemo(() => {
    const seen = new Set<string>();
    return (data?.Resources ?? []).flatMap((user) => {
      const email = (
        user.emails?.find((candidate) => candidate.primary) ?? user.emails?.[0]
      )?.value;
      if (!email || seen.has(email)) return [];
      seen.add(email);
      return [
        {
          value: email,
          label: `${user.displayName ?? email} (${email})`,
        },
      ];
    });
  }, [data]);
}

export interface OwnedObjectInterface
  extends Model<ControlComponent, 'OwnedObject'> {}

/**
 * Everything a user owns, for the delete confirmation.
 *
 * Disabled until asked for: it is cheap on the server (one indexed query per
 * securable type, unlike the per-object walk in `useUserAccessScan`) but there
 * is no reason to run it for every row of the Users table.
 */
export function useUserOwnedObjects(id: string | undefined, enabled: boolean) {
  return useQuery<
    SuccessResponseBody<
      ControlApi,
      '/scim2/Users/{id}/ownedObjects',
      'get',
      'application/json'
    >
  >({
    queryKey: ['userOwnedObjects', id],
    enabled: enabled && !!id,
    queryFn: async () => {
      const response = await (route as Route<ControlApi>)({
        client: CLIENT,
        request: {
          path: '/scim2/Users/{id}/ownedObjects',
          method: 'get',
          params: { paths: { id: id! } },
        },
        config: { baseURL: UC_AUTH_API_PREFIX },
        errorMessage: 'Failed to fetch the objects this user owns',
      }).call();
      if (isError(response)) {
        // NOTE:
        // When an expected error occurs, as defined in the OpenAPI specification, the following line will
        // be executed. This block serves as a placeholder for expected errors.
        return assertNever(response.data.status);
      }
      return response.data;
    },
  });
}

export interface SetScimUserActiveParams {
  id: string;
  active: boolean;
}

/**
 * Deactivates or reactivates a user.
 *
 * Deliberately a SCIM patch rather than a DELETE: both flip the same flag, but
 * a patch only ever touches `active`, whereas a PUT would write back whatever
 * else the request body happened to carry. Deactivation takes effect on the
 * next request the user makes — the server re-reads their state every time, so
 * tokens already issued stop working at once — and nothing else about them
 * changes, which is what makes it reversible.
 */
export function useSetScimUserActive() {
  const queryClient = useQueryClient();

  return useMutation<unknown, Error, SetScimUserActiveParams>({
    mutationFn: async ({ id, active }: SetScimUserActiveParams) => {
      const response = await (route as Route<ControlApi>)({
        client: CLIENT,
        request: {
          path: '/scim2/Users/{id}',
          method: 'patch',
          params: {
            paths: { id },
            body: {
              schemas: ['urn:ietf:params:scim:api:messages:2.0:PatchOp'],
              // A pathless `replace` is the only shape the server honours, and RFC 7644 §3.5.2.1
              // requires its value to be the object of attributes to replace — not a bare scalar.
              // The SCIM library enforces that while deserialising, so `value: active` never
              // reaches the service: it fails as a 500 before any handler runs.
              Operations: [{ op: 'replace', value: { active } }],
            },
          },
        },
        config: { baseURL: UC_AUTH_API_PREFIX },
        errorMessage: active
          ? 'Failed to reactivate user'
          : 'Failed to deactivate user',
      }).call();
      if (isError(response)) {
        // NOTE:
        // When an expected error occurs, as defined in the OpenAPI specification, the following line will
        // be executed. This block serves as a placeholder for expected errors.
        return assertNever(response.data.status);
      }
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['listScimUsers'] });
    },
  });
}

export interface PurgeScimUserParams {
  id: string;
  /** The user's principal, repeated back — the server rejects a mismatch. */
  principal: string;
  /** Who takes over the user's objects; omitted when they own nothing. */
  reassignTo?: string;
}

/**
 * Permanently removes a user.
 *
 * This is what frees the email and externalId for reuse; it does not block
 * access any harder than deactivation, which the server already enforces on
 * every request. Every guard lives on the server (deactivated first, not
 * yourself, not the built-in admin, an administrator left over, a valid
 * reassignment target) — what the UI does here is keep the caller from
 * reaching an obviously refused state, and surface the refusal when it comes.
 */
export function usePurgeScimUser() {
  const queryClient = useQueryClient();

  return useMutation<unknown, Error, PurgeScimUserParams>({
    mutationFn: async ({ id, principal, reassignTo }: PurgeScimUserParams) => {
      const response = await (route as Route<ControlApi>)({
        client: CLIENT,
        request: {
          path: '/scim2/Users/{id}',
          method: 'delete',
          params: {
            paths: { id },
            query: {
              purge: true,
              confirm_principal: principal,
              ...(reassignTo ? { reassign_to: reassignTo } : {}),
            },
          },
        },
        config: { baseURL: UC_AUTH_API_PREFIX },
        errorMessage: 'Failed to delete user',
      }).call();
      if (isError(response)) {
        // NOTE:
        // When an expected error occurs, as defined in the OpenAPI specification, the following line will
        // be executed. This block serves as a placeholder for expected errors.
        return assertNever(response.data.status);
      }
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['listScimUsers'] });
    },
  });
}

/**
 * The metastore's administrators, as a set of principals.
 *
 * Nothing else exposes this: OWNER is filtered out of /permissions responses,
 * so a user list cannot otherwise tell an administrator from anyone else.
 * Readable only by an administrator, so a failure means "cannot tell" rather
 * than "there are none" — callers render nothing rather than guessing.
 */
export function useMetastoreAdmins() {
  return useQuery<Set<string>>({
    queryKey: ['metastoreAdmins'],
    queryFn: async () => {
      try {
        const response = await (route as Route<ControlApi>)({
          client: CLIENT,
          request: { path: '/metastore/admins', method: 'get' },
          config: { baseURL: UC_AUTH_API_PREFIX },
          errorMessage: 'Failed to fetch administrators',
        }).call();
        if (isError(response)) return new Set<string>();
        return new Set<string>(response.data.admins ?? []);
      } catch {
        return new Set<string>();
      }
    },
  });
}

export interface MetastoreAdminMutationParams {
  /** The user's principal (email). */
  email: string;
  /** True to make them an administrator, false to withdraw it. */
  admin: boolean;
}

/**
 * Makes a user an administrator, or withdraws it.
 *
 * Any administrator may do either — metastore OWNER is the top of the
 * privilege lattice, so granting one escalates nobody past the grantor. The
 * server keeps the invariant that matters: one enabled administrator always
 * remains.
 */
export function useSetMetastoreAdmin() {
  const queryClient = useQueryClient();

  return useMutation<unknown, Error, MetastoreAdminMutationParams>({
    mutationFn: async ({ email, admin }: MetastoreAdminMutationParams) => {
      const response = await (route as Route<ControlApi>)({
        client: CLIENT,
        request: {
          path: '/metastore/admins/{email}',
          method: admin ? 'put' : 'delete',
          params: { paths: { email } },
        },
        config: { baseURL: UC_AUTH_API_PREFIX },
        errorMessage: admin
          ? 'Failed to make user an administrator'
          : 'Failed to withdraw the administrator privilege',
      }).call();
      if (isError(response)) {
        // NOTE:
        // When an expected error occurs, as defined in the OpenAPI specification, the following line will
        // be executed. This block serves as a placeholder for expected errors.
        return assertNever(response.data.status);
      }
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['metastoreAdmins'] });
      // The caller may have just changed their own standing.
      queryClient.invalidateQueries({ queryKey: ['isMetastoreAdmin'] });
    },
  });
}

export interface CreateScimUserMutationParams
  extends RequestBody<ControlApi, '/scim2/Users', 'post'> {}

export function useCreateScimUser() {
  const queryClient = useQueryClient();

  return useMutation<
    SuccessResponseBody<
      ControlApi,
      '/scim2/Users',
      'post',
      'application/scim+json'
    >,
    Error,
    CreateScimUserMutationParams
  >({
    mutationFn: async (params: CreateScimUserMutationParams) => {
      const response = await (route as Route<ControlApi>)({
        client: CLIENT,
        request: {
          path: '/scim2/Users',
          method: 'post',
          params: {
            body: params,
          },
        },
        config: {
          baseURL: UC_AUTH_API_PREFIX,
        },
        // NOTE:
        // Creation is authorized on the server (metastore OWNER); a 403
        // message from the server is surfaced to the caller as-is.
        errorMessage: 'Failed to create user',
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
      queryClient.invalidateQueries({ queryKey: ['listScimUsers'] });
    },
  });
}
