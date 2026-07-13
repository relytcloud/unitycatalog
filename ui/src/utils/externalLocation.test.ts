import { joinStorageLocation, matchExternalLocation } from './externalLocation';

const LOCATIONS = [
  { name: 'bucket-root', url: 'oss://bkt' },
  { name: 'ab', url: 'oss://bkt/ab' },
  { name: 'ab-deep', url: 'oss://bkt/ab/cd' },
  { name: 'trailing', url: 'oss://bkt/tr/' },
];

describe('matchExternalLocation', () => {
  it('picks the longest matching prefix', () => {
    expect(
      matchExternalLocation('oss://bkt/ab/cd/file.parquet', LOCATIONS)?.name,
    ).toBe('ab-deep');
    expect(matchExternalLocation('oss://bkt/ab/other', LOCATIONS)?.name).toBe(
      'ab',
    );
  });

  it('matches exact url', () => {
    expect(matchExternalLocation('oss://bkt/ab', LOCATIONS)?.name).toBe('ab');
  });

  it('does NOT match a partial path segment (bkt/ab vs bkt/abc)', () => {
    expect(matchExternalLocation('oss://bkt/abc', LOCATIONS)?.name).toBe(
      'bucket-root',
    );
  });

  it('normalizes trailing slashes on both sides', () => {
    expect(matchExternalLocation('oss://bkt/tr/x', LOCATIONS)?.name).toBe(
      'trailing',
    );
    expect(matchExternalLocation('oss://bkt/tr/', LOCATIONS)?.name).toBe(
      'trailing',
    );
  });

  it('returns undefined when nothing matches', () => {
    expect(matchExternalLocation('s3://other/x', LOCATIONS)).toBeUndefined();
    expect(matchExternalLocation(undefined, LOCATIONS)).toBeUndefined();
    expect(matchExternalLocation('oss://bkt/x', undefined)).toBeUndefined();
  });
});

describe('joinStorageLocation', () => {
  it('joins url and subpath with a single slash', () => {
    expect(joinStorageLocation('oss://bkt/base', 'tables/t1')).toBe(
      'oss://bkt/base/tables/t1',
    );
  });

  it('normalizes redundant slashes', () => {
    expect(joinStorageLocation('oss://bkt/base///', '/tables/t1/')).toBe(
      'oss://bkt/base/tables/t1',
    );
  });

  it('returns the bare url without subpath', () => {
    expect(joinStorageLocation('oss://bkt/base/', '')).toBe('oss://bkt/base');
    expect(joinStorageLocation('oss://bkt/base', undefined)).toBe(
      'oss://bkt/base',
    );
  });
});
