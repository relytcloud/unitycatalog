import { fireEvent, screen, waitFor } from '@testing-library/react';
import { renderWithProviders } from '../../test-utils/render';
import { programClient, requestsTo } from '../../test-utils/mockClient';
import UserAccessDetails from './UserAccessDetails';

jest.mock('../../context/client', () => ({ CLIENT: { request: jest.fn() } }));

// One catalog with one schema with one table; the scanned user has grants on
// all three levels, another principal's rows must be filtered out.
const ROUTES = [
  { method: 'get', url: '/catalogs', response: { catalogs: [{ name: 'c1' }] } },
  { method: 'get', url: '/schemas', response: { schemas: [{ name: 's1' }] } },
  { method: 'get', url: '/tables', response: { tables: [{ name: 't1' }] } },
  {
    method: 'get',
    url: '/permissions/catalog/c1',
    response: {
      privilege_assignments: [
        { principal: 'scan.me@x.com', privileges: ['USE CATALOG'] },
      ],
    },
  },
  {
    method: 'get',
    url: '/permissions/schema/c1.s1',
    response: {
      privilege_assignments: [
        { principal: 'scan.me@x.com', privileges: ['USE SCHEMA'] },
      ],
    },
  },
  {
    method: 'get',
    url: '/permissions/table/c1.s1.t1',
    response: {
      privilege_assignments: [
        { principal: 'scan.me@x.com', privileges: ['SELECT'] },
        { principal: 'someone.else@x.com', privileges: ['SELECT'] },
      ],
    },
  },
];

describe('UserAccessDetails full scan', () => {
  it('starts only on demand, walks catalog→schema→table and filters to the user', async () => {
    programClient(ROUTES);
    renderWithProviders(
      <UserAccessDetails
        open
        onClose={jest.fn()}
        principal="scan.me@x.com"
        displayName="Scan Me"
      />,
    );

    // The warning is shown and nothing has been fetched yet (explicit start).
    expect(await screen.findByText('Full metastore scan')).toBeInTheDocument();
    expect(requestsTo('get', '/catalogs')).toHaveLength(0);

    fireEvent.click(screen.getByRole('button', { name: 'Scan permissions' }));

    expect(await screen.findByText('c1.s1.t1')).toBeInTheDocument();
    expect(screen.getByText('c1.s1')).toBeInTheDocument();
    expect(screen.getByText('c1')).toBeInTheDocument();
    // The leaf privilege carries the simplified level hint.
    expect(screen.getByText('SELECT (read)')).toBeInTheDocument();
    // Rows of other principals are never attributed to the scanned user.
    expect(screen.queryByText('someone.else@x.com')).toBeNull();
    expect(
      await screen.findByText(/Scanned 1 catalogs, 1 schemas, 1 tables/),
    ).toBeInTheDocument();

    // Revoking from this view removes exactly the clicked privilege.
    programClient([
      ...ROUTES,
      {
        method: 'patch',
        url: '/permissions/table/c1.s1.t1',
        response: { privilege_assignments: [] },
      },
    ]);
    const tags = screen.getAllByText('SELECT (read)');
    // eslint-disable-next-line testing-library/no-node-access -- antd Tag close icon has no role
    const closeIcon = tags[0].parentElement?.querySelector(
      '.ant-tag-close-icon',
    );
    expect(closeIcon).not.toBeNull();
    fireEvent.click(closeIcon as Element);
    const okButton = await waitFor(() => {
      // eslint-disable-next-line testing-library/no-node-access -- confirm modal portal
      const button = document.querySelector(
        '.ant-modal-confirm-btns .ant-btn-dangerous',
      );
      if (!button) throw new Error('confirm button not rendered yet');
      return button;
    });
    fireEvent.click(okButton);

    await waitFor(() =>
      expect(requestsTo('patch', '/permissions/table/c1.s1.t1')).toHaveLength(
        1,
      ),
    );
    expect(requestsTo('patch', '/permissions/table/c1.s1.t1')[0].data).toEqual({
      changes: [{ principal: 'scan.me@x.com', add: [], remove: ['SELECT'] }],
    });
    // The revoked row disappears without a rescan.
    await waitFor(() => expect(screen.queryByText('c1.s1.t1')).toBeNull());
  });
});
