import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import { renderWithProviders } from '../../test-utils/render';
import { programClient, requestsTo } from '../../test-utils/mockClient';
import GrantToUserModal from './GrantToUserModal';
import { Privilege } from '../../types/api/catalog.gen';

jest.mock('../../context/client', () => ({ CLIENT: { request: jest.fn() } }));

const ROUTES = [
  {
    method: 'get',
    url: '/catalogs',
    response: { catalogs: [{ name: 'demo' }] },
  },
  {
    method: 'get',
    url: '/schemas',
    response: { schemas: [{ name: 'sales' }] },
  },
  { method: 'get', url: '/tables', response: { tables: [{ name: 'orders' }] } },
  {
    method: 'get',
    url: '/permissions',
    response: { privilege_assignments: [] },
  },
  {
    method: 'patch',
    url: '/permissions',
    response: { privilege_assignments: [] },
  },
];

/** Lets react-query flush a resolved listing into the select. */
async function settle() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 20));
  });
}

/** antd renders Select dropdowns in a body portal, so drive them by mouseDown. */
async function pick(selectIndex: number, optionText: string) {
  // Opening the dropdown while the list is still empty renders options that do
  // not refill, so wait for the data first.
  await settle();
  // eslint-disable-next-line testing-library/no-node-access -- antd Select renders in a body portal
  const selectors = document.querySelectorAll('.ant-select-selector');
  fireEvent.mouseDown(selectors[selectIndex]);
  fireEvent.click(await screen.findByTitle(optionText));
}

/**
 * Exercises the real hooks and the real privilege mapping, stubbed only at the
 * HTTP client, to pin what granting from the user list actually sends to Unity
 * Catalog. The menu click that opens this modal is covered by the dropdown
 * component tests; what matters here is the request contract.
 *
 * Reading one table needs privileges on three securables — USE_CATALOG on the
 * catalog, USE_SCHEMA on the schema, SELECT on the table — because UC
 * privileges do not inherit. The assertions pin that whole set, not just
 * SELECT: dropping an ancestor privilege silently yields an unreadable table.
 */
describe('granting table read to a user', () => {
  beforeEach(() => programClient(ROUTES));

  function renderModal() {
    return renderWithProviders(
      <GrantToUserModal
        open
        closeModal={jest.fn()}
        principal="customer@example.com"
      />,
    );
  }

  it('sends USE_CATALOG + USE_SCHEMA + SELECT for the chosen table', async () => {
    renderModal();

    await pick(0, 'demo');
    await pick(1, 'sales');
    await pick(2, 'orders');
    fireEvent.click(screen.getByRole('button', { name: 'Grant' }));

    await waitFor(() =>
      expect(requestsTo('patch', '/permissions').length).toBeGreaterThan(0),
    );

    const granted = requestsTo('patch', '/permissions').flatMap((config) =>
      ((config.data as any)?.changes ?? []).flatMap((change: any) =>
        (change.add ?? []).map((privilege: string) => ({
          url: config.url as string,
          principal: change.principal as string,
          privilege,
        })),
      ),
    );

    // Use the generated enum rather than literals: the wire values carry
    // spaces ("USE CATALOG"), not the identifier spelling.
    expect(granted.map((g) => g.privilege).sort()).toEqual(
      [Privilege.SELECT, Privilege.USE_CATALOG, Privilege.USE_SCHEMA].sort(),
    );
    granted.forEach((g) => expect(g.principal).toBe('customer@example.com'));
    // Each privilege must land on its own securable, not all on one.
    expect(
      granted.find((g) => g.privilege === Privilege.SELECT)?.url,
    ).toContain('demo.sales.orders');
    expect(
      granted.find((g) => g.privilege === Privilege.USE_SCHEMA)?.url,
    ).toContain('demo.sales');
    expect(
      granted.find((g) => g.privilege === Privilege.USE_CATALOG)?.url,
    ).toContain('demo');
  });

  /**
   * The reason this entry point exists: the per-user access view has to walk
   * every catalog, schema and table because UC has no per-user permissions
   * lookup. Granting must not drag that scan along.
   */
  it('never enumerates permissions while granting', async () => {
    renderModal();

    await pick(0, 'demo');
    await pick(1, 'sales');
    await pick(2, 'orders');

    expect(requestsTo('get', '/permissions')).toHaveLength(0);
  });

  /** Nothing below the chosen level may be listed up front. */
  it('lists schemas and tables only after their parent is chosen', async () => {
    renderModal();

    await settle();
    expect(requestsTo('get', '/schemas')).toHaveLength(0);
    expect(requestsTo('get', '/tables')).toHaveLength(0);

    await pick(0, 'demo');
    await waitFor(() =>
      expect(requestsTo('get', '/schemas').length).toBeGreaterThan(0),
    );
    expect(requestsTo('get', '/tables')).toHaveLength(0);

    await pick(1, 'sales');
    await waitFor(() =>
      expect(requestsTo('get', '/tables').length).toBeGreaterThan(0),
    );
  });
});
