import { fireEvent, screen, waitFor } from '@testing-library/react';
import { renderWithProviders } from '../../test-utils/render';
import { programClient, requestsTo } from '../../test-utils/mockClient';
import AccessPanel from './AccessPanel';
import { SecurableType } from '../../types/api/catalog.gen';

jest.mock('../../context/client', () => ({ CLIENT: { request: jest.fn() } }));

const TABLE_PERMISSIONS = {
  privilege_assignments: [
    { principal: 'reader@x.com', privileges: ['SELECT'] },
    // No SELECT → must not show up as "read" in the simplified panel.
    { principal: 'writer@x.com', privileges: ['MODIFY'] },
  ],
};

describe('AccessPanel (table)', () => {
  it('lists only principals holding the leaf privilege, as the simplified level', async () => {
    programClient([
      {
        method: 'get',
        url: '/permissions/table/c.s.t',
        response: TABLE_PERMISSIONS,
      },
    ]);
    renderWithProviders(
      <AccessPanel securableType={SecurableType.table} fullName="c.s.t" />,
    );

    expect(await screen.findByText('reader@x.com')).toBeInTheDocument();
    expect(screen.queryByText('writer@x.com')).toBeNull();
    expect(screen.getByText('read')).toBeInTheDocument();
  });

  it('revoking read removes ONLY table SELECT (keeps USE_* grants)', async () => {
    programClient([
      {
        method: 'get',
        url: '/permissions/table/c.s.t',
        response: TABLE_PERMISSIONS,
      },
      {
        method: 'patch',
        url: '/permissions/table/c.s.t',
        response: { privilege_assignments: [] },
      },
    ]);
    renderWithProviders(
      <AccessPanel securableType={SecurableType.table} fullName="c.s.t" />,
    );

    await screen.findByText('reader@x.com');
    // eslint-disable-next-line testing-library/no-node-access -- antd Tag close icon has no role
    const closeIcon = document.querySelector('.ant-tag-close-icon');
    expect(closeIcon).not.toBeNull();
    fireEvent.click(closeIcon as Element);
    // Imperative Modal.confirm renders its own footer (not .ant-modal-footer).
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
      expect(requestsTo('patch', '/permissions/table/c.s.t')).toHaveLength(1),
    );
    expect(requestsTo('patch', '/permissions/table/c.s.t')[0].data).toEqual({
      changes: [{ principal: 'reader@x.com', add: [], remove: ['SELECT'] }],
    });
    // The USE_* completions on catalog/schema must never be touched.
    expect(requestsTo('patch', '/permissions/catalog/')).toHaveLength(0);
    expect(requestsTo('patch', '/permissions/schema/')).toHaveLength(0);
  });
});

const SCHEMA_PERMISSIONS = {
  privilege_assignments: [
    // Reads every table beneath the schema, present and future.
    { principal: 'svc@x.com', privileges: ['USE SCHEMA', 'SELECT'] },
    // May create tables but reads none of them.
    { principal: 'builder@x.com', privileges: ['USE SCHEMA', 'CREATE TABLE'] },
    // USE alone is plumbing and shows up nowhere.
    { principal: 'visitor@x.com', privileges: ['USE SCHEMA'] },
  ],
};

describe('AccessPanel (schema)', () => {
  it('lists read and create rows, from SELECT and CREATE_TABLE respectively', async () => {
    programClient([
      {
        method: 'get',
        url: '/permissions/schema/c.s',
        response: SCHEMA_PERMISSIONS,
      },
      {
        method: 'get',
        url: '/auth/capabilities',
        response: { metastore_admin: true },
      },
    ]);
    renderWithProviders(
      <AccessPanel securableType={SecurableType.schema} fullName="c.s" />,
    );

    expect(await screen.findByText('svc@x.com')).toBeInTheDocument();
    expect(screen.getByText('builder@x.com')).toBeInTheDocument();
    expect(screen.queryByText('visitor@x.com')).toBeNull();
    expect(screen.getByText('read')).toBeInTheDocument();
    expect(screen.getByText('create')).toBeInTheDocument();
    // The panel says what read here means.
    expect(
      screen.getByText(/covers every table beneath this schema/),
    ).toBeInTheDocument();
  });

  it('lets only a metastore admin revoke read on a schema', async () => {
    programClient([
      {
        method: 'get',
        url: '/permissions/schema/c.s',
        response: {
          privilege_assignments: [
            { principal: 'svc@x.com', privileges: ['USE SCHEMA', 'SELECT'] },
          ],
        },
      },
      {
        method: 'get',
        url: '/auth/capabilities',
        response: { metastore_admin: false },
      },
    ]);
    renderWithProviders(
      <AccessPanel
        securableType={SecurableType.schema}
        fullName="c.s"
        owners={['me@x.com', 'me@x.com']}
      />,
    );

    await screen.findByText('svc@x.com');
    // The read tag renders, but without a close control for a non-admin.
    expect(screen.getByText('read')).toBeInTheDocument();
    // eslint-disable-next-line testing-library/no-node-access -- antd Tag close icon has no role
    expect(document.querySelector('.ant-tag-close-icon')).toBeNull();
  });
});
