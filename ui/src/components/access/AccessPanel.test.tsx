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
