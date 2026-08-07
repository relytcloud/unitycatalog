import { useMutation, useQueryClient } from '@tanstack/react-query';
import { CLIENT } from '../context/client';
import { route, isError, assertNever } from '../utils/openapi';
import type { paths as CatalogApi } from '../types/api/catalog.gen';
import { Privilege, SecurableType } from '../types/api/catalog.gen';
import type { Route, SuccessResponseBody } from '../utils/openapi';
import type { PrivilegeType } from './permissions';

/**
 * Simplified access model for the permissions UI (issue #6).
 *
 * UnityCatalog's server-side authorizer does NOT inherit privileges down the
 * hierarchy: to read one table a non-owner needs USE_CATALOG (on the catalog) +
 * USE_SCHEMA (on the schema) + SELECT (on the table); a SELECT/USE granted on
 * the catalog or schema does NOT cascade to the table. Exposing every raw
 * privilege in the UI is error-prone, so we collapse them into two levels —
 * `read` and `create` — and "auto-complete" the multiple securables each level
 * needs. This is a pure UI convenience; the backend authorizer is unchanged
 * (no inheritance is introduced).
 *
 * Level → granted (securable, privilege) set:
 *   - table  · read   = catalog:USE_CATALOG + schema:USE_SCHEMA + table:SELECT
 *   - schema · create = catalog:USE_CATALOG + schema:USE_SCHEMA + schema:CREATE_TABLE
 *   - catalog· create = catalog:USE_CATALOG + catalog:CREATE_SCHEMA
 *
 * Revoke intentionally removes ONLY the leaf privilege and keeps the USE_*
 * grants, because USE_CATALOG / USE_SCHEMA are shared by every read on sibling
 * tables in the same catalog/schema — cascading their removal would silently
 * break other tables' access. (Revoking read on a table only removes that
 * table's SELECT.)
 */
export type AccessLevel = 'read' | 'create';

export interface AccessTarget {
  // Only catalog / schema / table participate in the simplified model.
  securableType: SecurableType;
  // catalog | "catalog.schema" | "catalog.schema.table" (UC dotted full name).
  fullName: string;
}

export interface SecurablePrivilege {
  securable_type: SecurableType;
  full_name: string;
  privilege: PrivilegeType;
}

/** Derive the ancestor full names from a (dotted) securable full name. */
export function ancestors(fullName: string): {
  catalog: string;
  schema?: string;
} {
  const parts = fullName.split('.');
  return {
    catalog: parts[0],
    schema: parts.length >= 2 ? `${parts[0]}.${parts[1]}` : undefined,
  };
}

/**
 * The full (securable, privilege) set that granting `level` on `target` must
 * write — including the USE_* completion the server requires (no inheritance).
 */
export function grantsFor(
  target: AccessTarget,
  level: AccessLevel,
): SecurablePrivilege[] {
  const { catalog, schema } = ancestors(target.fullName);
  const useCatalog: SecurablePrivilege = {
    securable_type: SecurableType.catalog,
    full_name: catalog,
    privilege: Privilege.USE_CATALOG,
  };

  if (target.securableType === SecurableType.table && level === 'read') {
    const list: SecurablePrivilege[] = [useCatalog];
    if (schema) {
      list.push({
        securable_type: SecurableType.schema,
        full_name: schema,
        privilege: Privilege.USE_SCHEMA,
      });
    }
    list.push({
      securable_type: SecurableType.table,
      full_name: target.fullName,
      privilege: Privilege.SELECT,
    });
    return list;
  }

  if (target.securableType === SecurableType.schema && level === 'create') {
    return [
      useCatalog,
      {
        securable_type: SecurableType.schema,
        full_name: target.fullName,
        privilege: Privilege.USE_SCHEMA,
      },
      {
        securable_type: SecurableType.schema,
        full_name: target.fullName,
        privilege: Privilege.CREATE_TABLE,
      },
    ];
  }

  if (target.securableType === SecurableType.catalog && level === 'create') {
    return [
      useCatalog,
      {
        securable_type: SecurableType.catalog,
        full_name: target.fullName,
        privilege: Privilege.CREATE_SCHEMA,
      },
    ];
  }

  return [];
}

/**
 * The (securable, privilege) set that revoking `level` removes — ONLY the leaf
 * privilege; USE_* completions are left in place on purpose (see file header).
 */
export function revokesFor(
  target: AccessTarget,
  level: AccessLevel,
): SecurablePrivilege[] {
  if (target.securableType === SecurableType.table && level === 'read') {
    return [
      {
        securable_type: SecurableType.table,
        full_name: target.fullName,
        privilege: Privilege.SELECT,
      },
    ];
  }
  if (target.securableType === SecurableType.schema && level === 'create') {
    return [
      {
        securable_type: SecurableType.schema,
        full_name: target.fullName,
        privilege: Privilege.CREATE_TABLE,
      },
    ];
  }
  if (target.securableType === SecurableType.catalog && level === 'create') {
    return [
      {
        securable_type: SecurableType.catalog,
        full_name: target.fullName,
        privilege: Privilege.CREATE_SCHEMA,
      },
    ];
  }
  return [];
}

/**
 * The leaf privilege that *defines* the simplified level on a securable —
 * the one shown as a read/create tag and the only one a revoke removes:
 * table has SELECT → read, schema has CREATE_TABLE → create, catalog has
 * CREATE_SCHEMA → create.
 */
export function leafPrivilegeFor(
  securableType: SecurableType,
): PrivilegeType | undefined {
  switch (securableType) {
    case SecurableType.table:
      return Privilege.SELECT;
    case SecurableType.schema:
      return Privilege.CREATE_TABLE;
    case SecurableType.catalog:
      return Privilege.CREATE_SCHEMA;
    default:
      return undefined;
  }
}

/** The access levels offered for a given securable type in the simplified UI. */
export function accessLevelsFor(securableType: SecurableType): AccessLevel[] {
  switch (securableType) {
    case SecurableType.table:
      return ['read'];
    case SecurableType.schema:
      return ['create'];
    case SecurableType.catalog:
      return ['create'];
    default:
      return [];
  }
}

/** Group (securable, privilege) items so each securable gets ONE PATCH. */
function groupBySecurable(items: SecurablePrivilege[]): {
  securable_type: SecurableType;
  full_name: string;
  privileges: PrivilegeType[];
}[] {
  const byKey = new Map<
    string,
    {
      securable_type: SecurableType;
      full_name: string;
      privileges: PrivilegeType[];
    }
  >();
  for (const item of items) {
    const key = `${item.securable_type}:${item.full_name}`;
    const group = byKey.get(key);
    if (group) {
      if (!group.privileges.includes(item.privilege)) {
        group.privileges.push(item.privilege);
      }
    } else {
      byKey.set(key, {
        securable_type: item.securable_type,
        full_name: item.full_name,
        privileges: [item.privilege],
      });
    }
  }
  return Array.from(byKey.values());
}

async function patchPermissions(
  securable_type: SecurableType,
  full_name: string,
  principal: string,
  add: PrivilegeType[],
  remove: PrivilegeType[],
) {
  const response = await (route as Route<CatalogApi>)({
    client: CLIENT,
    request: {
      path: '/permissions/{securable_type}/{full_name}',
      method: 'patch',
      params: {
        paths: { securable_type, full_name },
        body: { changes: [{ principal, add, remove }] },
      },
    },
    errorMessage: 'Failed to update permissions',
  }).call();
  if (isError(response)) {
    return assertNever(response.data.status);
  }
  return response.data;
}

export interface UpdateAccessParams {
  principal: string;
  target: AccessTarget;
  level: AccessLevel;
  mode: 'grant' | 'revoke';
}

/**
 * Grant or revoke a simplified access level, translating it into the required
 * per-securable PATCHes (see grantsFor/revokesFor). Securables are patched
 * sequentially so we never issue two concurrent writes against the same object
 * and, on grant, the USE_* completions land before/with the leaf privilege.
 * Authorization is enforced server-side (securable owner); a 403 surfaces as-is.
 */
export function useUpdateAccess() {
  const queryClient = useQueryClient();
  return useMutation<unknown, Error, UpdateAccessParams>({
    mutationFn: async ({
      principal,
      target,
      level,
      mode,
    }: UpdateAccessParams) => {
      const items =
        mode === 'grant' ? grantsFor(target, level) : revokesFor(target, level);
      const groups = groupBySecurable(items);
      const results: SuccessResponseBody<
        CatalogApi,
        '/permissions/{securable_type}/{full_name}',
        'patch'
      >[] = [];
      for (const group of groups) {
        const data = await patchPermissions(
          group.securable_type,
          group.full_name,
          principal,
          mode === 'grant' ? group.privileges : [],
          mode === 'grant' ? [] : group.privileges,
        );
        results.push(
          data as SuccessResponseBody<
            CatalogApi,
            '/permissions/{securable_type}/{full_name}',
            'patch'
          >,
        );
      }
      return results;
    },
    // Invalidate on SETTLED, not just success: a grant fans out to several
    // sequential PATCHes and an earlier one can land before a later one fails
    // (e.g. the USE_* completion succeeds but the leaf 403s). Refreshing on
    // both outcomes keeps the visible grants in sync with whatever was applied.
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ['getPermissions'] });
      queryClient.invalidateQueries({ queryKey: ['userPermissions'] });
      queryClient.invalidateQueries({ queryKey: ['ownGrants'] });
    },
  });
}
