import { useMutation } from '@tanstack/react-query';
import { CLIENT } from '../context/client';
import { route, isError, assertNever } from '../utils/openapi';
import type { paths as CatalogApi } from '../types/api/catalog.gen';
import type { RequestBody, Route, SuccessResponseBody } from '../utils/openapi';

export interface VendPathCredentialsMutationParams
  extends RequestBody<CatalogApi, '/temporary-path-credentials', 'post'> {}

/**
 * Vends temporary credentials for a path. Used by the credential "Validate"
 * button: vending for a path under an external location bound to the
 * credential exercises the REAL AssumeRole (or static-key) flow on the
 * server, so success proves the credential is usable and a failure surfaces
 * the raw STS error.
 */
export function useVendPathCredentials() {
  return useMutation<
    SuccessResponseBody<CatalogApi, '/temporary-path-credentials', 'post'>,
    Error,
    VendPathCredentialsMutationParams
  >({
    mutationFn: async (params: VendPathCredentialsMutationParams) => {
      const response = await (route as Route<CatalogApi>)({
        client: CLIENT,
        request: {
          path: '/temporary-path-credentials',
          method: 'post',
          params: {
            body: params,
          },
        },
        errorMessage: 'Failed to vend path credentials',
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
