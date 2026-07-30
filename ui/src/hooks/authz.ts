import { useQueries, useQuery } from '@tanstack/react-query';
import { CLIENT } from '../context/client';
import { route, isError } from '../utils/openapi';
import { UC_AUTH_API_PREFIX } from '../utils/constants';
import type { paths as CatalogApi } from '../types/api/catalog.gen';
import type { paths as ControlApi } from '../types/api/control.gen';
import { SecurableType } from '../types/api/catalog.gen';
import type { Route } from '../utils/openapi';
import type {
  PrivilegeAssignmentInterface,
  PrivilegeType,
} from './permissions';

/**
 * Positive-signal authorization hints for the UI (issue #6).
 *
 * The server is the only real authorizer — every mutating endpoint re-checks
 * permissions and a 403 is always possible. These hooks only decide whether an
 * action button is rendered enabled (blue) or disabled (grey), using the few
 * signals the REST API exposes to the current caller:
 *
 *   1. Unknown identity (auth disabled / not logged in): every button is
 *      enabled and the server has the final say (fail-open).
 *   2. Owner match: the securable's `owner` field equals the current user.
 *   3. Own privileges: GET /permissions/{type}/{name} deterministically
 *      returns the caller's own assignments even for non-owners, so the UI
 *      can check e.g. CREATE_TABLE on a schema.
 *   4. Owner-side signal: that same GET returns OTHER principals' assignments
 *      only when the caller owns the securable, one of its ancestors, or the
 *      metastore — seeing someone else's row proves manage rights.
 *
 * Known blind spot (documented in ui/README.md): the metastore owner cannot
 * be detected through any read-only endpoint, so a metastore admin who does
 * not own a resource — in a system with no visible grants at all — sees
 * disabled buttons even though the server would authorize the call. Granting
 * any metastore-level privilege (or any grant the admin can see) restores the
 * owner-side signal.
 */

export interface AuthzCheck {
  securableType: SecurableType;
  /** undefined disables the underlying query and the check can only be
   * satisfied by an owner match or the metastore owner-side signal. */
  fullName: string | undefined;
  /** Satisfied when the current user's own grants include ANY of these. */
  anyOf?: PrivilegeType[];
  /** Satisfied when the current user's own grants include ALL of these. */
  allOf?: PrivilegeType[];
  /** Owners whose match with the current user satisfies this check outright
   * (e.g. the catalog/schema owners for operations down the hierarchy). */
  ownerAnyOf?: (string | undefined)[];
}

export interface AuthzResult {
  /** Positive-signal decision; false until `ready`. */
  allowed: boolean;
  /** All underlying queries settled. */
  ready: boolean;
}

/**
 * The current user's email (grant principal), or null when the identity is
 * unknown — auth disabled, session expired, or /scim2/Me unavailable. A null
 * principal makes every gate fail-open: the server still authorizes.
 */
export function useCurrentPrincipal() {
  return useQuery<string | null>({
    queryKey: ['currentPrincipal'],
    queryFn: async () => {
      try {
        const response = await (route as Route<ControlApi>)({
          client: CLIENT,
          request: { path: '/scim2/Me', method: 'get' },
          config: { baseURL: UC_AUTH_API_PREFIX },
          errorMessage: 'Failed to fetch current user',
        }).call();
        if (isError(response)) return null;
        const emails = response.data.emails ?? [];
        const email = (
          emails.find((candidate) => candidate.primary) ?? emails[0]
        )?.value;
        return email ? email.toLowerCase() : null;
      } catch {
        return null;
      }
    },
  });
}

interface OwnGrants {
  /** The current user's own privileges on the securable. */
  privileges: PrivilegeType[];
  /** Another principal's assignment was visible — proves the caller owns the
   * securable, an ancestor, or the metastore (see PermissionService). */
  sawOthers: boolean;
  failed: boolean;
}

async function fetchOwnGrants(
  securableType: SecurableType,
  fullName: string,
  principal: string,
): Promise<OwnGrants> {
  try {
    const response = await (route as Route<CatalogApi>)({
      client: CLIENT,
      request: {
        path: '/permissions/{securable_type}/{full_name}',
        method: 'get',
        params: {
          paths: { securable_type: securableType, full_name: fullName },
        },
      },
      errorMessage: 'Failed to fetch permissions',
    }).call();
    if (isError(response))
      return { privileges: [], sawOthers: false, failed: true };
    const assignments: PrivilegeAssignmentInterface[] =
      response.data.privilege_assignments ?? [];
    return {
      privileges: assignments
        .filter(
          (assignment) =>
            (assignment.principal ?? '').toLowerCase() === principal,
        )
        .flatMap((assignment) => assignment.privileges ?? []),
      sawOthers: assignments.some(
        (assignment) =>
          (assignment.principal ?? '').toLowerCase() !== principal,
      ),
      failed: false,
    };
  } catch {
    return { privileges: [], sawOthers: false, failed: true };
  }
}

/**
 * Evaluate positive-signal authorization for an action. ALL checks must be
 * satisfied unless a global signal short-circuits: unknown identity
 * (fail-open) or the metastore owner-side signal (admin).
 */
export function useAuthorized(checks: AuthzCheck[]): AuthzResult {
  const { data: principal, isPending: principalPending } =
    useCurrentPrincipal();
  const identityKnown = !!principal;

  // One extra probe: any visible foreign grant on the metastore proves the
  // caller is the metastore owner (the metastore has no ancestors), which
  // authorizes everything the UI gates.
  const securables: { securableType: SecurableType; fullName: string }[] = [
    { securableType: SecurableType.metastore, fullName: 'metastore' },
    ...checks.flatMap((check) =>
      check.fullName
        ? [{ securableType: check.securableType, fullName: check.fullName }]
        : [],
    ),
  ];

  const grantQueries = useQueries({
    queries: securables.map((securable) => ({
      queryKey: [
        'ownGrants',
        securable.securableType,
        securable.fullName,
        principal,
      ],
      enabled: identityKnown,
      queryFn: () =>
        fetchOwnGrants(securable.securableType, securable.fullName, principal!),
    })),
  });

  if (principalPending) return { allowed: false, ready: false };
  // Unknown identity: the UI cannot evaluate anything — leave the buttons
  // enabled and let the server decide (it is the enforcement floor anyway).
  if (!identityKnown) return { allowed: true, ready: true };

  const ready = grantQueries.every((query) => !query.isPending);
  if (!ready) return { allowed: false, ready: false };

  const grantsBySecurable = new Map<string, OwnGrants>();
  securables.forEach((securable, index) => {
    grantsBySecurable.set(
      `${securable.securableType}:${securable.fullName}`,
      grantQueries[index].data ?? {
        privileges: [],
        sawOthers: false,
        failed: true,
      },
    );
  });

  const metastoreGrants = grantsBySecurable.get(
    `${SecurableType.metastore}:metastore`,
  );
  if (metastoreGrants?.sawOthers) return { allowed: true, ready: true };

  const allowed = checks.every((check) => {
    if (
      (check.ownerAnyOf ?? []).some(
        (owner) => owner && owner.toLowerCase() === principal,
      )
    ) {
      return true;
    }
    if (!check.fullName) return false;
    const grants = grantsBySecurable.get(
      `${check.securableType}:${check.fullName}`,
    );
    if (!grants || grants.failed) return false;
    if (grants.sawOthers) return true;
    if (check.anyOf && check.anyOf.some((p) => grants.privileges.includes(p))) {
      return true;
    }
    if (check.allOf) {
      return check.allOf.every((p) => grants.privileges.includes(p));
    }
    return false;
  });

  return { allowed, ready: true };
}

/**
 * Gate for managing grants on a securable. Mirrors PermissionService's PATCH
 * rules: metastore OWNER or an owner along the resource chain; plain
 * privileges never grant manage rights, so only owner matches and owner-side
 * signals count.
 */
export function useCanManageGrants(
  securableType: SecurableType,
  fullName: string | undefined,
  ownerAnyOf: (string | undefined)[],
): AuthzResult {
  return useAuthorized([{ securableType, fullName, ownerAnyOf }]);
}
