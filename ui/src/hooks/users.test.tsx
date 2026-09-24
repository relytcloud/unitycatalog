import { useEffect } from 'react';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../test-utils/render';
import { programClient, requestsTo } from '../test-utils/mockClient';
import { useSetScimUserActive, usePurgeScimUser } from './users';

// Inlined rather than calling mockClientModule(): jest.mock is hoisted above the imports, so the
// factory cannot close over anything this file imports.
jest.mock('../context/client', () => ({ CLIENT: { request: jest.fn() } }));

const USER_ID = 'u-1';
const USERS_URL = `/scim2/Users/${USER_ID}`;

/**
 * Fires a mutation once on mount. These tests are about the request that leaves the browser, not
 * about any UI, so the component exists only to run the hook.
 */
function renderMutation(
  useHook: () => { mutate: (vars: never) => void },
  vars: unknown,
) {
  function Probe() {
    const { mutate } = useHook();
    useEffect(() => {
      mutate(vars as never);
      // Mount only: re-firing on every render would loop, since the mutation itself re-renders.
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);
    return <div>fired</div>;
  }
  renderWithProviders(<Probe />);
}

describe('useSetScimUserActive request shape', () => {
  beforeEach(() => {
    programClient([{ method: 'patch', url: USERS_URL, response: {} }]);
  });

  /**
   * The bug this pins down: the UI used to send `value: false`. RFC 7644 §3.5.2.1 says a `replace`
   * without a `path` carries the object of attributes to replace, and the server's SCIM library
   * enforces that while deserialising — a bare scalar is rejected as a 500 before any handler runs,
   * so the user saw "failed to parse a JSON document" instead of a deactivated account.
   */
  it('sends a pathless replace whose value is an object, not a scalar', async () => {
    renderMutation(useSetScimUserActive, { id: USER_ID, active: false });
    await screen.findByText('fired');

    const [request] = requestsTo('patch', USERS_URL);
    expect(request).toBeDefined();

    const operation = request.data.Operations[0];
    expect(operation.op).toBe('replace');
    expect(operation.value).toEqual({ active: false });
    // A scalar value is exactly what the server refuses.
    expect(typeof operation.value).toBe('object');
    // A path would take the request down the branch the server answers with 501.
    expect(operation.path).toBeUndefined();
    expect(request.data.schemas).toEqual([
      'urn:ietf:params:scim:api:messages:2.0:PatchOp',
    ]);
  });

  it('reactivating sends the same shape with active true', async () => {
    renderMutation(useSetScimUserActive, { id: USER_ID, active: true });
    await screen.findByText('fired');

    const [request] = requestsTo('patch', USERS_URL);
    expect(request.data.Operations[0].value).toEqual({ active: true });
  });
});

describe('usePurgeScimUser request shape', () => {
  beforeEach(() => {
    programClient([{ method: 'delete', url: USERS_URL, response: {} }]);
  });

  /**
   * Purging is the irreversible one, and the server refuses it unless the principal is repeated
   * back. If the UI ever stopped sending that, every purge would fail — or, worse, a future server
   * that stopped checking would delete whichever row the caller happened to name.
   */
  it('sends purge with the principal confirmed back', async () => {
    renderMutation(usePurgeScimUser, {
      id: USER_ID,
      principal: 'someone@example.com',
    });
    await screen.findByText('fired');

    const [request] = requestsTo('delete', USERS_URL);
    expect(request.params).toMatchObject({
      purge: true,
      confirm_principal: 'someone@example.com',
    });
    expect(request.params.reassign_to).toBeUndefined();
  });

  it('passes reassign_to only when one was chosen', async () => {
    renderMutation(usePurgeScimUser, {
      id: USER_ID,
      principal: 'someone@example.com',
      reassignTo: 'heir@example.com',
    });
    await screen.findByText('fired');

    const [request] = requestsTo('delete', USERS_URL);
    expect(request.params.reassign_to).toBe('heir@example.com');
  });
});
