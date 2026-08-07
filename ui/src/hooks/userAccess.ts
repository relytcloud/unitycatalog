import { useCallback, useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { CLIENT } from '../context/client';
import { route, isError } from '../utils/openapi';
import { fetchAllPages } from '../utils/paginate';
import type { paths as CatalogApi } from '../types/api/catalog.gen';
import { SecurableType } from '../types/api/catalog.gen';
import type { Route } from '../utils/openapi';
import { privilegesForPrincipal } from './permissions';
import type {
  PrivilegeAssignmentInterface,
  PrivilegeType,
  SecurableRef,
  UserSecurablePrivileges,
} from './permissions';
import type { CatalogInterface } from './catalog';
import type { SchemaInterface } from './schemas';
import type { TableInterface } from './tables';

/**
 * Helpers for per-user permission views (issue #6).
 *
 * Unity Catalog has NO "list securables by principal" API — GET /permissions
 * only works per securable (and the server ignores its ?principal filter, so
 * principals are matched client-side). Any per-user view therefore has to
 * enumerate securables and query them one by one. The catalog+schema summary
 * (useAllSecurableRefs) is bounded; the full catalog→schema→table scan
 * (useUserAccessScan) is NOT:
 *
 *   *** WARNING: full-traversal fan-out ***
 *   The scan issues one LIST per catalog/schema plus one permissions GET per
 *   catalog, schema AND table. On a metastore with many tables this hammers
 *   the UC server — it is meant for admins on small/medium metastores and is
 *   started explicitly by a button, never automatically. See ui/README.md.
 */

const CONCURRENCY = 8;

async function listCatalogNames(): Promise<string[]> {
  const catalogs = await fetchAllPages<CatalogInterface>(async (pageToken) => {
    const response = await (route as Route<CatalogApi>)({
      client: CLIENT,
      request: {
        path: '/catalogs',
        method: 'get',
        params: { query: pageToken ? { page_token: pageToken } : undefined },
      },
      errorMessage: 'Failed to fetch catalogs',
    }).call();
    if (isError(response)) throw new Error('Failed to fetch catalogs');
    return {
      items: response.data.catalogs ?? [],
      nextPageToken: response.data.next_page_token,
    };
  });
  return catalogs.flatMap((catalog) => (catalog.name ? [catalog.name] : []));
}

async function listSchemaNames(catalogName: string): Promise<string[]> {
  const schemas = await fetchAllPages<SchemaInterface>(async (pageToken) => {
    const response = await (route as Route<CatalogApi>)({
      client: CLIENT,
      request: {
        path: '/schemas',
        method: 'get',
        params: {
          query: {
            catalog_name: catalogName,
            ...(pageToken ? { page_token: pageToken } : {}),
          },
        },
      },
      errorMessage: 'Failed to fetch schemas',
    }).call();
    if (isError(response)) throw new Error('Failed to fetch schemas');
    return {
      items: response.data.schemas ?? [],
      nextPageToken: response.data.next_page_token,
    };
  });
  return schemas.flatMap((schema) => (schema.name ? [schema.name] : []));
}

async function listTableNames(
  catalogName: string,
  schemaName: string,
): Promise<string[]> {
  const tables = await fetchAllPages<TableInterface>(async (pageToken) => {
    const response = await (route as Route<CatalogApi>)({
      client: CLIENT,
      request: {
        path: '/tables',
        method: 'get',
        params: {
          query: {
            catalog_name: catalogName,
            schema_name: schemaName,
            ...(pageToken ? { page_token: pageToken } : {}),
          },
        },
      },
      errorMessage: 'Failed to fetch tables',
    }).call();
    if (isError(response)) throw new Error('Failed to fetch tables');
    return {
      items: response.data.tables ?? [],
      nextPageToken: response.data.next_page_token,
    };
  });
  return tables.flatMap((table) => (table.name ? [table.name] : []));
}

/**
 * The bounded securable set for the Users-page summary: the metastore itself,
 * every catalog, and every schema in every catalog (one LIST per catalog).
 * The metastore is included so its admin-level grants (CREATE_CATALOG,
 * CREATE_EXTERNAL_LOCATION, CREATE_STORAGE_CREDENTIAL) — which the Grant modal
 * can hand out — are visible and revocable here rather than being write-only.
 * Tables are deliberately excluded — they live behind the explicit full scan.
 */
export function useAllSecurableRefs() {
  return useQuery<SecurableRef[]>({
    queryKey: ['allSecurableRefs'],
    queryFn: async () => {
      const catalogNames = await listCatalogNames();
      const refs: SecurableRef[] = [
        { securable_type: SecurableType.metastore, full_name: 'metastore' },
        ...catalogNames.map((name) => ({
          securable_type: SecurableType.catalog,
          full_name: name,
        })),
      ];
      for (let i = 0; i < catalogNames.length; i += CONCURRENCY) {
        const batch = catalogNames.slice(i, i + CONCURRENCY);
        const schemaLists = await Promise.all(
          batch.map(async (catalogName) => {
            try {
              return (await listSchemaNames(catalogName)).map((schemaName) => ({
                securable_type: SecurableType.schema,
                full_name: `${catalogName}.${schemaName}`,
              }));
            } catch {
              // A catalog we cannot list contributes no schema rows rather
              // than failing the whole summary.
              return [];
            }
          }),
        );
        refs.push(...schemaLists.flat());
      }
      return refs;
    },
  });
}

async function fetchPrincipalPrivileges(
  securable: SecurableRef,
  principal: string,
): Promise<PrivilegeType[]> {
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
      },
    },
    errorMessage: 'Failed to fetch permissions',
  }).call();
  if (isError(response)) throw new Error('Failed to fetch permissions');
  const assignments: PrivilegeAssignmentInterface[] =
    response.data.privilege_assignments ?? [];
  return privilegesForPrincipal(assignments, principal);
}

export interface ScanProgress {
  catalogs: number;
  schemas: number;
  tables: number;
  failures: number;
}

export type ScanStatus = 'idle' | 'running' | 'done';

/**
 * Explicit full catalog→schema→table permission scan for one principal.
 * See the file-header warning: this is expensive by design and only ever
 * runs when start() is called. Results are plain state (not react-query
 * cache); removePrivilege() lets the caller mirror a successful revoke
 * without re-scanning.
 */
export function useUserAccessScan(principal: string | undefined) {
  const [status, setStatus] = useState<ScanStatus>('idle');
  const [progress, setProgress] = useState<ScanProgress>({
    catalogs: 0,
    schemas: 0,
    tables: 0,
    failures: 0,
  });
  const [rows, setRows] = useState<UserSecurablePrivileges[]>([]);
  const [error, setError] = useState<string | null>(null);
  // Bumped on reset/unmount so an in-flight scan stops writing state.
  const scanIdRef = useRef(0);

  useEffect(() => {
    return () => {
      scanIdRef.current += 1;
    };
  }, []);

  const reset = useCallback(() => {
    scanIdRef.current += 1;
    setStatus('idle');
    setProgress({ catalogs: 0, schemas: 0, tables: 0, failures: 0 });
    setRows([]);
    setError(null);
  }, []);

  const start = useCallback(async () => {
    if (!principal) return;
    const wanted = principal.toLowerCase();
    const scanId = ++scanIdRef.current;
    const active = () => scanIdRef.current === scanId;
    setStatus('running');
    setProgress({ catalogs: 0, schemas: 0, tables: 0, failures: 0 });
    setRows([]);
    setError(null);

    const collected: UserSecurablePrivileges[] = [];
    let failures = 0;
    const counts = { catalogs: 0, schemas: 0, tables: 0 };
    const publish = () => {
      if (!active()) return;
      setProgress({ ...counts, failures });
      setRows([...collected]);
    };

    const checkSecurables = async (securables: SecurableRef[]) => {
      for (let i = 0; i < securables.length; i += CONCURRENCY) {
        if (!active()) return;
        const batch = securables.slice(i, i + CONCURRENCY);
        const results = await Promise.all(
          batch.map(async (securable) => {
            try {
              return {
                securable,
                failed: false,
                privileges: await fetchPrincipalPrivileges(securable, wanted),
              };
            } catch {
              return {
                securable,
                failed: true,
                privileges: [] as PrivilegeType[],
              };
            }
          }),
        );
        for (const { securable, privileges, failed } of results) {
          if (failed) failures += 1;
          if (privileges.length > 0)
            collected.push({ ...securable, privileges });
        }
        publish();
      }
    };

    try {
      const catalogNames = await listCatalogNames();
      if (!active()) return;
      counts.catalogs = catalogNames.length;
      publish();
      await checkSecurables(
        catalogNames.map((name) => ({
          securable_type: SecurableType.catalog,
          full_name: name,
        })),
      );

      for (const catalogName of catalogNames) {
        if (!active()) return;
        let schemaNames: string[] = [];
        try {
          schemaNames = await listSchemaNames(catalogName);
        } catch {
          failures += 1;
          publish();
          continue;
        }
        counts.schemas += schemaNames.length;
        publish();
        await checkSecurables(
          schemaNames.map((schemaName) => ({
            securable_type: SecurableType.schema,
            full_name: `${catalogName}.${schemaName}`,
          })),
        );

        for (const schemaName of schemaNames) {
          if (!active()) return;
          let tableNames: string[] = [];
          try {
            tableNames = await listTableNames(catalogName, schemaName);
          } catch {
            failures += 1;
            publish();
            continue;
          }
          counts.tables += tableNames.length;
          publish();
          await checkSecurables(
            tableNames.map((tableName) => ({
              securable_type: SecurableType.table,
              full_name: `${catalogName}.${schemaName}.${tableName}`,
            })),
          );
        }
      }
      if (active()) setStatus('done');
    } catch (scanError) {
      if (active()) {
        setError(
          scanError instanceof Error ? scanError.message : 'Scan failed',
        );
        setStatus('done');
      }
    }
  }, [principal]);

  const removePrivilege = useCallback(
    (securable: SecurableRef, privilege: PrivilegeType) => {
      setRows((current) =>
        current
          .map((row) =>
            row.securable_type === securable.securable_type &&
            row.full_name === securable.full_name
              ? {
                  ...row,
                  privileges: row.privileges.filter((p) => p !== privilege),
                }
              : row,
          )
          .filter((row) => row.privileges.length > 0),
      );
    },
    [],
  );

  return { status, progress, rows, error, start, reset, removePrivilege };
}
