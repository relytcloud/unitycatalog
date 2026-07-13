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

export interface ExternalLocationInterface
  extends Model<CatalogComponent, 'ExternalLocationInfo'> {}

export function useListExternalLocations() {
  return useQuery<
    SuccessResponseBody<CatalogApi, '/external-locations', 'get'>
  >({
    queryKey: ['listExternalLocations'],
    queryFn: async () => {
      // The server caps a single page, so follow next_page_token to fetch all.
      const externalLocations = await fetchAllPages<ExternalLocationInterface>(
        async (pageToken) => {
          const response = await (route as Route<CatalogApi>)({
            client: CLIENT,
            request: {
              path: '/external-locations',
              method: 'get',
              params: {
                query: pageToken ? { page_token: pageToken } : undefined,
              },
            },
            errorMessage: 'Failed to fetch external locations',
          }).call();
          if (isError(response)) {
            // NOTE:
            // When an expected error occurs, as defined in the OpenAPI specification, the following line will
            // be executed. This block serves as a placeholder for expected errors.
            return assertNever(response.data.status);
          }
          return {
            items: response.data.external_locations ?? [],
            nextPageToken: response.data.next_page_token,
          };
        },
      );
      return {
        external_locations: externalLocations,
        next_page_token: undefined,
      };
    },
  });
}

export interface UseGetExternalLocationArgs
  extends PathParam<CatalogApi, '/external-locations/{name}', 'get'> {}

export function useGetExternalLocation({ name }: UseGetExternalLocationArgs) {
  return useQuery<
    SuccessResponseBody<CatalogApi, '/external-locations/{name}', 'get'>
  >({
    queryKey: ['getExternalLocation', name],
    queryFn: async () => {
      const response = await (route as Route<CatalogApi>)({
        client: CLIENT,
        request: {
          path: '/external-locations/{name}',
          method: 'get',
          params: {
            paths: {
              name,
            },
          },
        },
        errorMessage: 'Failed to fetch external location',
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

export interface CreateExternalLocationMutationParams
  extends RequestBody<CatalogApi, '/external-locations', 'post'> {}

export function useCreateExternalLocation() {
  const queryClient = useQueryClient();

  return useMutation<
    SuccessResponseBody<CatalogApi, '/external-locations', 'post'>,
    Error,
    CreateExternalLocationMutationParams
  >({
    mutationFn: async (params: CreateExternalLocationMutationParams) => {
      const response = await (route as Route<CatalogApi>)({
        client: CLIENT,
        request: {
          path: '/external-locations',
          method: 'post',
          params: {
            body: params,
          },
        },
        // NOTE:
        // Creation is authorized on the server (metastore OWNER or
        // CREATE_EXTERNAL_LOCATION); a 403 message from the server is
        // surfaced to the caller as-is.
        errorMessage: 'Failed to create external location',
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
        queryKey: ['listExternalLocations'],
      });
    },
  });
}
