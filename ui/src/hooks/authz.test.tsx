import { screen } from '@testing-library/react';
import { renderWithProviders } from '../test-utils/render';
import { programClient } from '../test-utils/mockClient';
import { AuthzCheck, useAuthorized } from './authz';
import { Privilege, SecurableType } from '../types/api/catalog.gen';

jest.mock('../context/client', () => ({ CLIENT: { request: jest.fn() } }));

function Probe({ checks }: { checks: AuthzCheck[] }) {
  const { allowed, ready } = useAuthorized(checks);
  return <div>{!ready ? 'loading' : allowed ? 'allowed' : 'denied'}</div>;
}

const ME = {
  id: 'u1',
  displayName: 'Me',
  emails: [{ primary: true, value: 'me@x.com' }],
};
const EMPTY_PERMISSIONS = { privilege_assignments: [] };

const CATALOG_CHECK: AuthzCheck[] = [
  {
    securableType: SecurableType.catalog,
    fullName: 'c',
    allOf: [Privilege.USE_CATALOG, Privilege.CREATE_SCHEMA],
    ownerAnyOf: ['owner@x.com'],
  },
];

describe('useAuthorized positive signals', () => {
  it('fails open when the identity is unknown (auth disabled)', async () => {
    // /scim2/Me is not mocked → the request rejects → principal null.
    programClient([]);
    renderWithProviders(<Probe checks={CATALOG_CHECK} />);
    expect(await screen.findByText('allowed')).toBeInTheDocument();
  });

  it('denies without any signal', async () => {
    programClient([
      { method: 'get', url: '/scim2/Me', response: ME },
      { method: 'get', url: '/permissions/', response: EMPTY_PERMISSIONS },
    ]);
    renderWithProviders(<Probe checks={CATALOG_CHECK} />);
    expect(await screen.findByText('denied')).toBeInTheDocument();
  });

  it('allows on owner match', async () => {
    programClient([
      { method: 'get', url: '/scim2/Me', response: ME },
      { method: 'get', url: '/permissions/', response: EMPTY_PERMISSIONS },
    ]);
    renderWithProviders(
      <Probe checks={[{ ...CATALOG_CHECK[0], ownerAnyOf: ['ME@x.com'] }]} />,
    );
    expect(await screen.findByText('allowed')).toBeInTheDocument();
  });

  it('allows when own privileges satisfy allOf', async () => {
    programClient([
      { method: 'get', url: '/scim2/Me', response: ME },
      {
        method: 'get',
        url: '/permissions/metastore/metastore',
        response: EMPTY_PERMISSIONS,
      },
      {
        method: 'get',
        url: '/permissions/catalog/c',
        response: {
          privilege_assignments: [
            {
              principal: 'me@x.com',
              privileges: ['USE CATALOG', 'CREATE SCHEMA'],
            },
          ],
        },
      },
    ]);
    renderWithProviders(<Probe checks={CATALOG_CHECK} />);
    expect(await screen.findByText('allowed')).toBeInTheDocument();
  });

  it('denies when own privileges only partially satisfy allOf', async () => {
    programClient([
      { method: 'get', url: '/scim2/Me', response: ME },
      {
        method: 'get',
        url: '/permissions/metastore/metastore',
        response: EMPTY_PERMISSIONS,
      },
      {
        method: 'get',
        url: '/permissions/catalog/c',
        response: {
          privilege_assignments: [
            { principal: 'me@x.com', privileges: ['USE CATALOG'] },
          ],
        },
      },
    ]);
    renderWithProviders(<Probe checks={CATALOG_CHECK} />);
    expect(await screen.findByText('denied')).toBeInTheDocument();
  });

  it("allows when another principal's rows are visible (owner-side signal)", async () => {
    programClient([
      { method: 'get', url: '/scim2/Me', response: ME },
      {
        method: 'get',
        url: '/permissions/metastore/metastore',
        response: EMPTY_PERMISSIONS,
      },
      {
        method: 'get',
        url: '/permissions/catalog/c',
        response: {
          privilege_assignments: [
            { principal: 'someone.else@x.com', privileges: ['USE CATALOG'] },
          ],
        },
      },
    ]);
    renderWithProviders(<Probe checks={CATALOG_CHECK} />);
    expect(await screen.findByText('allowed')).toBeInTheDocument();
  });

  it('metastore owner-side signal unlocks unrelated checks (admin)', async () => {
    programClient([
      { method: 'get', url: '/scim2/Me', response: ME },
      {
        method: 'get',
        url: '/permissions/metastore/metastore',
        response: {
          privilege_assignments: [
            { principal: 'someone.else@x.com', privileges: ['CREATE CATALOG'] },
          ],
        },
      },
      {
        method: 'get',
        url: '/permissions/catalog/c',
        response: EMPTY_PERMISSIONS,
      },
    ]);
    renderWithProviders(<Probe checks={CATALOG_CHECK} />);
    expect(await screen.findByText('allowed')).toBeInTheDocument();
  });
});

describe('metastore admin capability signal', () => {
  const CAPS = '/auth/capabilities';

  /**
   * The case no other signal can see: an admin looking at a resource they do
   * not own, with no grant visible anywhere. Before the capability endpoint
   * this rendered a disabled button even though the server would allow it.
   */
  it('allows an unowned resource with no visible grants when the caller is metastore admin', async () => {
    programClient([
      { method: 'get', url: '/scim2/Me', response: ME },
      { method: 'get', url: CAPS, response: { metastore_admin: true } },
      { method: 'get', url: '/permissions/', response: EMPTY_PERMISSIONS },
    ]);
    renderWithProviders(<Probe checks={CATALOG_CHECK} />);
    expect(await screen.findByText('allowed')).toBeInTheDocument();
  });

  it('still denies a non-admin with no signals', async () => {
    programClient([
      { method: 'get', url: '/scim2/Me', response: ME },
      { method: 'get', url: CAPS, response: { metastore_admin: false } },
      { method: 'get', url: '/permissions/', response: EMPTY_PERMISSIONS },
    ]);
    renderWithProviders(<Probe checks={CATALOG_CHECK} />);
    expect(await screen.findByText('denied')).toBeInTheDocument();
  });

  /** Older servers have no such endpoint; the other signals must still work. */
  it('falls back to the other signals when the endpoint is unavailable', async () => {
    programClient([
      { method: 'get', url: '/scim2/Me', response: ME },
      { method: 'get', url: CAPS, response: {}, status: 404 },
      { method: 'get', url: '/permissions/', response: EMPTY_PERMISSIONS },
    ]);
    renderWithProviders(<Probe checks={CATALOG_CHECK} />);
    expect(await screen.findByText('denied')).toBeInTheDocument();
  });
});
