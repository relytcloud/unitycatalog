import { CLIENT } from '../context/client';

// The axios CLIENT is replaced by a jest mock (tests must call
// jest.mock('../context/client', ...) BEFORE importing this helper's users;
// see mockClientModule below for the canonical factory).

export interface MockRoute {
  method: string;
  // Matched with String.prototype.includes on the request url.
  url: string;
  response: unknown;
  status?: number;
}

/**
 * Programs the mocked CLIENT.request with canned responses per (method, url
 * substring). Unmatched requests reject, so a test can never silently pass
 * against an unexpected call. Returns the underlying jest.fn for payload
 * assertions.
 */
export function programClient(routes: MockRoute[]) {
  const requestMock = CLIENT.request as jest.Mock;
  requestMock.mockReset();
  requestMock.mockImplementation((config: { url: string; method: string }) => {
    const route = routes.find(
      (candidate) =>
        candidate.method.toLowerCase() === config.method.toLowerCase() &&
        config.url.includes(candidate.url),
    );
    if (!route) {
      return Promise.reject(
        new Error(`Unmocked request: ${config.method} ${config.url}`),
      );
    }
    if (route.status && route.status >= 400) {
      const error = Object.assign(new Error('Request failed'), {
        isAxiosError: true,
        response: { status: route.status, data: route.response },
      });
      return Promise.reject(error);
    }
    return Promise.resolve({ data: route.response });
  });
  return requestMock;
}

/**
 * Extracts the calls the mocked CLIENT received for a given method + url
 * substring — the payload-contract assertion helper.
 */
export function requestsTo(method: string, url: string) {
  const requestMock = CLIENT.request as jest.Mock;
  return requestMock.mock.calls
    .map(([config]) => config)
    .filter(
      (config) =>
        config.method.toLowerCase() === method.toLowerCase() &&
        config.url.includes(url),
    );
}

/** The canonical jest.mock factory for '../context/client'. */
export function mockClientModule() {
  return { CLIENT: { request: jest.fn() } };
}
