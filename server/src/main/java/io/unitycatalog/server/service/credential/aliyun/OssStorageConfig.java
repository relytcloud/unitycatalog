package io.unitycatalog.server.service.credential.aliyun;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * Per-bucket (or master-role) configuration for vending Aliyun OSS credentials. Mirrors {@link
 * io.unitycatalog.server.service.credential.aws.S3StorageConfig}.
 *
 * <p>Two modes are supported and share this single config shape so that the rest of the system is
 * agnostic to which one is in use:
 *
 * <ul>
 *   <li><b>Static AK/SK:</b> {@code accessKey}/{@code secretKey} are long-lived OSS keys and {@code
 *       securityToken} is empty. The static generator returns them verbatim.
 *   <li><b>STS AssumeRole:</b> {@code accessKey}/{@code secretKey} identify the UC master RAM user,
 *       and {@code ramRoleArn} (or the role from the credential securable) is assumed to obtain
 *       scoped short-lived credentials via {@link
 *       AliyunCredentialGenerator.StsAliyunCredentialGenerator}.
 * </ul>
 */
@Getter
@Builder
@ToString
public class OssStorageConfig {
  private final String bucketPath;
  private final String region;
  // The RAM role ARN to assume for the per-bucket STS scheme, e.g.
  // acs:ram::123456789012:role/uc-oss-access. Null for the master-role config (the role to assume
  // comes from the credential securable instead) and null for the static scheme.
  private final String ramRoleArn;
  private final String accessKey;
  private final String secretKey;
  private final String securityToken;
  private final String credentialGenerator;
}
