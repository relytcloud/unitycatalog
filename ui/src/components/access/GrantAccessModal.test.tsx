import { waitFor } from '@testing-library/react';
import { renderWithProviders } from '../../test-utils/render';
import { programClient, requestsTo } from '../../test-utils/mockClient';
import { clickModalOk, selectAntdOption } from '../../test-utils/antd';
import { GrantAccessModal } from './GrantAccessModal';
import { SecurableType } from '../../types/api/catalog.gen';

jest.mock('../../context/client', () => ({ CLIENT: { request: jest.fn() } }));

const USERS = {
  Resources: [
    {
      id: 'u1',
      displayName: 'Demo Reader',
      emails: [{ primary: true, value: 'demo.reader@x.com' }],
    },
  ],
};

describe('GrantAccessModal', () => {
  it('table read auto-completes USE_CATALOG + USE_SCHEMA + SELECT', async () => {
    programClient([
      { method: 'get', url: '/scim2/Users', response: USERS },
      {
        method: 'patch',
        url: '/permissions/',
        response: { privilege_assignments: [] },
      },
    ]);
    renderWithProviders(
      <GrantAccessModal
        open
        closeModal={jest.fn()}
        target={{ securableType: SecurableType.table, fullName: 'c.s.t' }}
      />,
    );

    await selectAntdOption(0, /Demo Reader/);
    clickModalOk();

    // One PATCH per securable, leaf last.
    await waitFor(() =>
      expect(requestsTo('patch', '/permissions/table/c.s.t')).toHaveLength(1),
    );
    expect(requestsTo('patch', '/permissions/catalog/c')[0].data).toEqual({
      changes: [
        { principal: 'demo.reader@x.com', add: ['USE CATALOG'], remove: [] },
      ],
    });
    expect(requestsTo('patch', '/permissions/schema/c.s')[0].data).toEqual({
      changes: [
        { principal: 'demo.reader@x.com', add: ['USE SCHEMA'], remove: [] },
      ],
    });
    expect(requestsTo('patch', '/permissions/table/c.s.t')[0].data).toEqual({
      changes: [
        { principal: 'demo.reader@x.com', add: ['SELECT'], remove: [] },
      ],
    });
  });

  it('schema create groups both schema privileges into one PATCH', async () => {
    programClient([
      { method: 'get', url: '/scim2/Users', response: USERS },
      {
        method: 'patch',
        url: '/permissions/',
        response: { privilege_assignments: [] },
      },
    ]);
    renderWithProviders(
      <GrantAccessModal
        open
        closeModal={jest.fn()}
        target={{ securableType: SecurableType.schema, fullName: 'c.s' }}
      />,
    );

    await selectAntdOption(0, /Demo Reader/);
    clickModalOk();

    await waitFor(() =>
      expect(requestsTo('patch', '/permissions/schema/c.s')).toHaveLength(1),
    );
    expect(requestsTo('patch', '/permissions/schema/c.s')[0].data).toEqual({
      changes: [
        {
          principal: 'demo.reader@x.com',
          add: ['USE SCHEMA', 'CREATE TABLE'],
          remove: [],
        },
      ],
    });
    expect(requestsTo('patch', '/permissions/catalog/c')[0].data).toEqual({
      changes: [
        { principal: 'demo.reader@x.com', add: ['USE CATALOG'], remove: [] },
      ],
    });
  });
});
