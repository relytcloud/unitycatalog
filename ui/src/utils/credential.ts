import { CredentialInterface } from '../hooks/credentials';

export type CredentialTypeLabel =
  | 'Aliyun RAM (STS)'
  | 'Aliyun AK/SK (static)'
  | 'AWS IAM role'
  | 'Unknown';

/**
 * Derives the human-facing credential type from which cloud-role field is
 * populated (the API models the union implicitly: exactly one of
 * `aws_iam_role` / `aliyun_ram_role` is set, and an Aliyun credential is STS
 * when it carries a role_arn, static AK/SK otherwise).
 */
export function credentialTypeOf(
  credential: CredentialInterface,
): CredentialTypeLabel {
  if (credential.aws_iam_role?.role_arn) return 'AWS IAM role';
  if (credential.aliyun_ram_role?.role_arn) return 'Aliyun RAM (STS)';
  if (credential.aliyun_ram_role) return 'Aliyun AK/SK (static)';
  return 'Unknown';
}

/**
 * Masks the middle of an access key id (e.g. LTAI****ab12) so lists never
 * show the full key. The secret itself is never returned by the API at all.
 */
export function maskAccessKeyId(accessKeyId: string): string {
  if (accessKeyId.length <= 8) return '****';
  return `${accessKeyId.slice(0, 4)}****${accessKeyId.slice(-4)}`;
}

/**
 * The role ARN or masked access key id — whichever identifies the underlying
 * cloud identity of the credential.
 */
export function credentialIdentityOf(credential: CredentialInterface): string {
  const identity =
    credential.aws_iam_role?.role_arn ?? credential.aliyun_ram_role?.role_arn;
  if (identity) return identity;
  const accessKeyId = credential.aliyun_ram_role?.access_key_id;
  return accessKeyId ? maskAccessKeyId(accessKeyId) : '';
}
