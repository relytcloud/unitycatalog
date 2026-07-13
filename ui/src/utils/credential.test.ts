import {
  credentialIdentityOf,
  credentialTypeOf,
  maskAccessKeyId,
} from './credential';

describe('credentialTypeOf', () => {
  it('detects AWS IAM role', () => {
    expect(
      credentialTypeOf({ aws_iam_role: { role_arn: 'arn:aws:iam::1:role/r' } }),
    ).toBe('AWS IAM role');
  });

  it('detects Aliyun STS (role_arn present)', () => {
    expect(
      credentialTypeOf({
        aliyun_ram_role: { role_arn: 'acs:ram::1:role/r' },
      }),
    ).toBe('Aliyun RAM (STS)');
  });

  it('detects Aliyun static AK/SK (no role_arn)', () => {
    expect(
      credentialTypeOf({
        aliyun_ram_role: { access_key_id: 'LTAI5tDemoAccessKey1' },
      }),
    ).toBe('Aliyun AK/SK (static)');
  });

  it('falls back to Unknown', () => {
    expect(credentialTypeOf({})).toBe('Unknown');
  });
});

describe('maskAccessKeyId', () => {
  it('masks the middle, keeping 4+4 chars', () => {
    expect(maskAccessKeyId('LTAI5tDemoAccessKey1')).toBe('LTAI****Key1');
  });

  it('fully masks short keys', () => {
    expect(maskAccessKeyId('short')).toBe('****');
    expect(maskAccessKeyId('12345678')).toBe('****');
  });
});

describe('credentialIdentityOf', () => {
  it('prefers the role arn', () => {
    expect(
      credentialIdentityOf({
        aliyun_ram_role: { role_arn: 'acs:ram::1:role/r' },
      }),
    ).toBe('acs:ram::1:role/r');
  });

  it('falls back to the masked access key id', () => {
    expect(
      credentialIdentityOf({
        aliyun_ram_role: { access_key_id: 'LTAI5tDemoAccessKey1' },
      }),
    ).toBe('LTAI****Key1');
  });

  it('returns empty for neither', () => {
    expect(credentialIdentityOf({})).toBe('');
  });
});
