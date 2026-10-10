import {
  accessLevelsFor,
  grantsFor,
  isAdminOnly,
  leafPrivilegeFor,
  revokesFor,
} from './access';
import { Privilege, SecurableType } from '../types/api/catalog.gen';

jest.mock('../context/client', () => ({ CLIENT: { request: jest.fn() } }));

describe('simplified access mapping', () => {
  it('table read grants USE_CATALOG + USE_SCHEMA + SELECT across the chain', () => {
    expect(
      grantsFor(
        { securableType: SecurableType.table, fullName: 'c.s.t' },
        'read',
      ),
    ).toEqual([
      {
        securable_type: SecurableType.catalog,
        full_name: 'c',
        privilege: Privilege.USE_CATALOG,
      },
      {
        securable_type: SecurableType.schema,
        full_name: 'c.s',
        privilege: Privilege.USE_SCHEMA,
      },
      {
        securable_type: SecurableType.table,
        full_name: 'c.s.t',
        privilege: Privilege.SELECT,
      },
    ]);
  });

  it('schema create grants USE_CATALOG + USE_SCHEMA + CREATE_TABLE', () => {
    expect(
      grantsFor(
        { securableType: SecurableType.schema, fullName: 'c.s' },
        'create',
      ),
    ).toEqual([
      {
        securable_type: SecurableType.catalog,
        full_name: 'c',
        privilege: Privilege.USE_CATALOG,
      },
      {
        securable_type: SecurableType.schema,
        full_name: 'c.s',
        privilege: Privilege.USE_SCHEMA,
      },
      {
        securable_type: SecurableType.schema,
        full_name: 'c.s',
        privilege: Privilege.CREATE_TABLE,
      },
    ]);
  });

  it('catalog create grants USE_CATALOG + CREATE_SCHEMA', () => {
    expect(
      grantsFor(
        { securableType: SecurableType.catalog, fullName: 'c' },
        'create',
      ),
    ).toEqual([
      {
        securable_type: SecurableType.catalog,
        full_name: 'c',
        privilege: Privilege.USE_CATALOG,
      },
      {
        securable_type: SecurableType.catalog,
        full_name: 'c',
        privilege: Privilege.CREATE_SCHEMA,
      },
    ]);
  });

  it('revoke removes ONLY the leaf privilege, never the USE_* completions', () => {
    expect(
      revokesFor(
        { securableType: SecurableType.table, fullName: 'c.s.t' },
        'read',
      ),
    ).toEqual([
      {
        securable_type: SecurableType.table,
        full_name: 'c.s.t',
        privilege: Privilege.SELECT,
      },
    ]);
    expect(
      revokesFor(
        { securableType: SecurableType.schema, fullName: 'c.s' },
        'create',
      ),
    ).toEqual([
      {
        securable_type: SecurableType.schema,
        full_name: 'c.s',
        privilege: Privilege.CREATE_TABLE,
      },
    ]);
    expect(
      revokesFor(
        { securableType: SecurableType.catalog, fullName: 'c' },
        'create',
      ),
    ).toEqual([
      {
        securable_type: SecurableType.catalog,
        full_name: 'c',
        privilege: Privilege.CREATE_SCHEMA,
      },
    ]);
  });

  it('schema read grants USE_CATALOG + USE_SCHEMA + SELECT on the schema', () => {
    expect(
      grantsFor(
        { securableType: SecurableType.schema, fullName: 'c.s' },
        'read',
      ),
    ).toEqual([
      {
        securable_type: SecurableType.catalog,
        full_name: 'c',
        privilege: Privilege.USE_CATALOG,
      },
      {
        securable_type: SecurableType.schema,
        full_name: 'c.s',
        privilege: Privilege.USE_SCHEMA,
      },
      {
        securable_type: SecurableType.schema,
        full_name: 'c.s',
        privilege: Privilege.SELECT,
      },
    ]);
  });

  it('catalog read grants USE_CATALOG + SELECT on the catalog', () => {
    expect(
      grantsFor(
        { securableType: SecurableType.catalog, fullName: 'c' },
        'read',
      ),
    ).toEqual([
      {
        securable_type: SecurableType.catalog,
        full_name: 'c',
        privilege: Privilege.USE_CATALOG,
      },
      {
        securable_type: SecurableType.catalog,
        full_name: 'c',
        privilege: Privilege.SELECT,
      },
    ]);
  });

  it('revoking read on a schema or catalog removes only that SELECT', () => {
    expect(
      revokesFor(
        { securableType: SecurableType.schema, fullName: 'c.s' },
        'read',
      ),
    ).toEqual([
      {
        securable_type: SecurableType.schema,
        full_name: 'c.s',
        privilege: Privilege.SELECT,
      },
    ]);
    expect(
      revokesFor(
        { securableType: SecurableType.catalog, fullName: 'c' },
        'read',
      ),
    ).toEqual([
      {
        securable_type: SecurableType.catalog,
        full_name: 'c',
        privilege: Privilege.SELECT,
      },
    ]);
  });

  it('levels and leaf privileges per securable type', () => {
    expect(accessLevelsFor(SecurableType.table)).toEqual(['read']);
    expect(accessLevelsFor(SecurableType.schema)).toEqual(['read', 'create']);
    expect(accessLevelsFor(SecurableType.catalog)).toEqual(['read', 'create']);
    expect(accessLevelsFor(SecurableType.credential)).toEqual([]);
    // Without a level, the first level of the securable is meant.
    expect(leafPrivilegeFor(SecurableType.table)).toBe(Privilege.SELECT);
    expect(leafPrivilegeFor(SecurableType.schema)).toBe(Privilege.SELECT);
    expect(leafPrivilegeFor(SecurableType.schema, 'create')).toBe(
      Privilege.CREATE_TABLE,
    );
    expect(leafPrivilegeFor(SecurableType.catalog, 'read')).toBe(
      Privilege.SELECT,
    );
    expect(leafPrivilegeFor(SecurableType.catalog, 'create')).toBe(
      Privilege.CREATE_SCHEMA,
    );
    expect(leafPrivilegeFor(SecurableType.external_location)).toBeUndefined();
  });

  it('read on a schema or catalog is reserved to the metastore admin', () => {
    expect(isAdminOnly(SecurableType.schema, 'read')).toBe(true);
    expect(isAdminOnly(SecurableType.catalog, 'read')).toBe(true);
    expect(isAdminOnly(SecurableType.table, 'read')).toBe(false);
    expect(isAdminOnly(SecurableType.schema, 'create')).toBe(false);
    expect(isAdminOnly(SecurableType.catalog, 'create')).toBe(false);
  });
});
