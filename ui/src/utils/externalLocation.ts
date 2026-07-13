import { ExternalLocationInterface } from '../hooks/externalLocations';

/**
 * Resolves which registered external location covers a storage path: the
 * location whose url is the LONGEST prefix of the path (exact match or a
 * whole-segment prefix, so oss://bkt/ab never matches oss://bkt/abc).
 */
export function matchExternalLocation(
  storageLocation: string | undefined,
  externalLocations: ExternalLocationInterface[] | undefined,
): ExternalLocationInterface | undefined {
  if (!storageLocation || !externalLocations) return undefined;
  const path = storageLocation.replace(/\/+$/, '');
  let best: ExternalLocationInterface | undefined;
  let bestLength = -1;
  for (const location of externalLocations) {
    const url = (location.url ?? '').replace(/\/+$/, '');
    if (!url) continue;
    if (path === url || path.startsWith(`${url}/`)) {
      if (url.length > bestLength) {
        best = location;
        bestLength = url.length;
      }
    }
  }
  return best;
}

/**
 * Joins an external location url and an optional subpath into a storage
 * location, normalizing redundant slashes on both sides.
 */
export function joinStorageLocation(url: string, subpath?: string): string {
  const base = url.replace(/\/+$/, '');
  const sub = (subpath ?? '').replace(/^\/+/, '').replace(/\/+$/, '');
  return sub ? `${base}/${sub}` : base;
}
