import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CLIENT } from '../context/client';
import { route, isError, assertNever } from '../utils/openapi';
import { fetchAllPages } from '../utils/paginate';
import type {
  paths as CatalogApi,
  components as CatalogComponent,
} from '../types/api/catalog.gen';
import type {
  Model,
  PathParam,
  RequestBody,
  Route,
  SuccessResponseBody,
} from '../utils/openapi';

export interface CredentialInterface
  extends Model<CatalogComponent, 'CredentialInfo'> {}

export function useListCredentials() {
  return useQuery<SuccessResponseBody<CatalogApi, '/credentials', 'get'>>({
    queryKey: ['listCredentials'],
    queryFn: async () => {
      // The server caps a single page, so follow next_page_token to fetch all.
      const credentials = await fetchAllPages<CredentialInterface>(
        async (pageToken) => {
          const response = await (route as Route<CatalogApi>)({
            client: CLIENT,
            request: {
              path: '/credentials',
              method: 'get',
              params: {
                query: pageToken ? { page_token: pageToken } : undefined,
              },
            },
            errorMessage: 'Failed to fetch credentials',
          }).call();
          if (isError(response)) {
            // NOTE:
            // When an expected error occurs, as defined in the OpenAPI specification, the following line will
            // be executed. This block serves as a placeholder for expected errors.
            return assertNever(response.data.status);
          }
          return {
            items: response.data.credentials ?? [],
            nextPageToken: response.data.next_page_token,
          };
        },
      );
      return { credentials, next_page_token: undefined };
    },
  });
}

export interface UseGetCredentialArgs
  extends PathParam<CatalogApi, '/credentials/{name}', 'get'> {}

export function useGetCredential({ name }: UseGetCredentialArgs) {
  return useQuery<
    SuccessResponseBody<CatalogApi, '/credentials/{name}', 'get'>
  >({
    queryKey: ['getCredential', name],
    queryFn: async () => {
      const response = await (route as Route<CatalogApi>)({
        client: CLIENT,
        request: {
          path: '/credentials/{name}',
          method: 'get',
          params: {
            paths: {
              name,
            },
          },
        },
        errorMessage: 'Failed to fetch credential',
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

export interface CreateCredentialMutationParams
  extends RequestBody<CatalogApi, '/credentials', 'post'> {}

export function useCreateCredential() {
  const queryClient = useQueryClient();

  return useMutation<
    SuccessResponseBody<CatalogApi, '/credentials', 'post'>,
    Error,
    CreateCredentialMutationParams
  >({
    mutationFn: async (params: CreateCredentialMutationParams) => {
      const response = await (route as Route<CatalogApi>)({
        client: CLIENT,
        request: {
          path: '/credentials',
          method: 'post',
          params: {
            body: params,
          },
        },
        // NOTE:
        // Creation is authorized on the server (metastore OWNER or
        // CREATE_STORAGE_CREDENTIAL); a 403 message from the server is
        // surfaced to the caller as-is.
        errorMessage: 'Failed to create credential',
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
      queryClient.invalidateQueries({
        queryKey: ['listCredentials'],
      });
    },
  });
}
