/**
 * Fetches every page of a token-paginated list endpoint and returns the
 * concatenated items. The server caps a single response (e.g. 100 rows), so a
 * single call would silently truncate; this follows next_page_token to the
 * end. Guards against a server returning a repeating/stable token so a bug
 * upstream can't spin an infinite loop.
 */
export interface Page<T> {
  items: T[];
  nextPageToken?: string | null;
}

export async function fetchAllPages<T>(
  fetchPage: (pageToken: string | undefined) => Promise<Page<T>>,
): Promise<T[]> {
  const all: T[] = [];
  const seenTokens = new Set<string>();
  let pageToken: string | undefined = undefined;
  for (;;) {
    const page: Page<T> = await fetchPage(pageToken);
    all.push(...(page.items ?? []));
    const token: string | null | undefined = page.nextPageToken;
    if (!token || seenTokens.has(token)) break;
    seenTokens.add(token);
    pageToken = token;
  }
  return all;
}
