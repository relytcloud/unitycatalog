import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { renderWithProviders } from '../../test-utils/render';
import { programClient, requestsTo } from '../../test-utils/mockClient';
import { clickModalOk, selectAntdOption } from '../../test-utils/antd';
import { GrantPermissionModal } from './GrantPermissionModal';
import { SecurableType } from '../../types/api/catalog.gen';

jest.mock('../../context/client', () => ({ CLIENT: { request: jest.fn() } }));

const USERS = {
  Resources: [
    {
      id: 'u1',
      displayName: 'Demo Reader',
      emails: [{ primary: true, value: 'demo.reader@x.com' }],
    },
    {
      id: 'u2',
      displayName: 'Alice Writer',
      emails: [{ primary: true, value: 'alice.writer@x.com' }],
    },
  ],
};

const LIST_ROUTES = [
  { method: 'get', url: '/scim2/Users', response: USERS },
  {
    method: 'get',
    url: '/catalogs',
    response: { catalogs: [{ name: 'main' }] },
  },
];

describe('GrantPermissionModal (securable locked)', () => {
  it('grants selected privileges to the picked user', async () => {
    programClient([
      ...LIST_ROUTES,
      {
        method: 'patch',
        url: '/permissions/metastore/metastore',
        response: { privilege_assignments: [] },
      },
    ]);
    renderWithProviders(
      <GrantPermissionModal
        open
        closeModal={jest.fn()}
        securableType={SecurableType.metastore}
        fullName="metastore"
      />,
    );

    // Select index 0 = user, index 1 = privileges (securable is locked).
    await selectAntdOption(0, /Demo Reader/);
    await selectAntdOption(1, 'CREATE CATALOG');
    clickModalOk();

    await waitFor(() =>
      expect(
        requestsTo('patch', '/permissions/metastore/metastore'),
      ).toHaveLength(1),
    );
    expect(
      requestsTo('patch', '/permissions/metastore/metastore')[0].data,
    ).toEqual({
      changes: [
        {
          principal: 'demo.reader@x.com',
          add: ['CREATE CATALOG'],
          remove: [],
        },
      ],
    });
  });

  it('filters users with contains (%xx%) semantics on name and email', async () => {
    programClient(LIST_ROUTES);
    renderWithProviders(
      <GrantPermissionModal
        open
        closeModal={jest.fn()}
        securableType={SecurableType.metastore}
        fullName="metastore"
      />,
    );

    // eslint-disable-next-line testing-library/no-node-access -- antd Select renders in a body portal
    const selectors = document.querySelectorAll('.ant-select-selector');
    fireEvent.mouseDown(selectors[0]);
    // Both options visible initially.
    await screen.findByText(/Demo Reader/);
    const searchInput = within(
      // eslint-disable-next-line testing-library/no-node-access -- scope the combobox to the first Select
      selectors[0].parentElement as HTMLElement,
    ).getByRole('combobox');
    // Substring of the EMAIL, not the display name.
    fireEvent.change(searchInput, { target: { value: 'alice.w' } });
    await waitFor(() => {
      expect(screen.queryByText(/Demo Reader/)).toBeNull();
    });
    expect(screen.getByText(/Alice Writer/)).toBeInTheDocument();
  });
});

describe('GrantPermissionModal (securable picker)', () => {
  it('offers only metastore and catalogs — credential/external location grants were removed', async () => {
    programClient(LIST_ROUTES);
    renderWithProviders(
      <GrantPermissionModal
        open
        closeModal={jest.fn()}
        principal="demo.reader@x.com"
      />,
    );

    // Principal locked → index 0 is the securable picker.
    // eslint-disable-next-line testing-library/no-node-access -- antd Select renders in a body portal
    const selectors = document.querySelectorAll('.ant-select-selector');
    fireEvent.mouseDown(selectors[0]);
    expect(await screen.findByText('catalog: main')).toBeInTheDocument();
    expect(screen.getByText('metastore')).toBeInTheDocument();
    expect(screen.queryByText(/credential:/)).toBeNull();
    expect(screen.queryByText(/external location:/)).toBeNull();
  });
});
