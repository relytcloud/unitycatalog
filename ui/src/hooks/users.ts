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
