package io.unitycatalog.server.service.credential.aliyun;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.model.AliyunRamRoleResponse;
import io.unitycatalog.server.persist.dao.CredentialDAO;
import io.unitycatalog.server.service.credential.CredentialContext;
import io.unitycatalog.server.utils.NormalizedURL;
import io.unitycatalog.server.utils.ServerProperties;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;

/**
 * Vends Aliyun credentials for accessing OSS storage. The Aliyun counterpart of {@link
 * io.unitycatalog.server.service.credential.aws.AwsCredentialVendor}.
 *
 * <p>Supports the same two credential modes as the AWS vendor, both returning the same {@link
 * AliyunCredential} shape so the consumer is agnostic to which one is used:
 *
 * <ul>
 *   <li><b>Static AK/SK:</b> long-lived OSS keys returned verbatim (no STS call, no expiration).
 *   <li><b>STS AssumeRole:</b> the master RAM client assumes a RAM role to mint scoped, short-lived
 *       credentials.
 * </ul>
 *
 * <p>The credential source is resolved as follows:
 *
 * <ol>
 *   <li><b>External Location Credentials:</b> when a storage credential ({@link
 *       io.unitycatalog.server.persist.dao.CredentialDAO}) is associated with an external location,
 *       its securable ({@link io.unitycatalog.server.model.AliyunRamRoleResponse}) provides either
 *       a static AK/SK (returned verbatim) or a RAM role to assume.
 *   <li><b>Per-Bucket Configuration:</b> per-bucket OSS configs from server.properties
 *       (oss.bucketPath.*, oss.accessKey.*, ...). A RAM role ARN selects STS; otherwise static
 *       AK/SK is used.
 * </ol>
 */
public class AliyunCredentialVendor {

  private final Map<NormalizedURL, OssStorageConfig> perBucketOssConfigs;
  private final Map<NormalizedURL, AliyunCredentialGenerator> perBucketCredGenerators =
      new ConcurrentHashMap<>();

  // The master RAM config used to construct an STS client that assumes RAM roles defined in
  // CredentialDAO. Its ramRoleArn is always null as the role to assume comes from the securable.
  private final OssStorageConfig ossMasterRoleConfig;

  // Holds credentials of the UC master RAM user; assumes roles defined in CredentialDAO. Lazily
  // initialized like perBucketCredGenerators so a server that does not use Aliyun never constructs
  // an STS client (and tests without proper ServerProperties still work).
  @Getter(lazy = true)
  private final AliyunCredentialGenerator ossMasterRoleStsGenerator =
      new AliyunCredentialGenerator.StsAliyunCredentialGenerator(ossMasterRoleConfig);

  public AliyunCredentialVendor(ServerProperties serverProperties) {
    this.perBucketOssConfigs = serverProperties.getOssConfigurations();
    this.ossMasterRoleConfig = serverProperties.getOssMasterRoleConfiguration();
  }

  public AliyunCredential vendAliyunCredential(CredentialContext context) {
    if (context.getCredentialDAO().isPresent()) {
      CredentialDAO credentialDAO = context.getCredentialDAO().get();
      // An OSS location must be bound to an Aliyun credential. Reject any other type with a clear
      // error instead of the opaque parse failure getAliyunRamRoleResponse() would otherwise throw.
      if (credentialDAO.getCredentialType() != CredentialDAO.CredentialType.ALIYUN_RAM_ROLE) {
        throw new BaseException(
            ErrorCode.FAILED_PRECONDITION,
            "OSS location is bound to a non-Aliyun storage credential: "
                + credentialDAO.getCredentialType());
      }
      AliyunRamRoleResponse ramRole = credentialDAO.getAliyunRamRoleResponse();
      // A RAM role ARN selects STS; assume it via the master STS generator.
      if (isNotEmpty(ramRole.getRoleArn())) {
        return getOssMasterRoleStsGenerator().generate(context);
      }
      // Otherwise the credential must carry a complete static AK/SK, returned verbatim. The
      // response shape matches the STS mode (empty security token, no expiration) so the consumer
      // stays agnostic.
      if (isNotEmpty(ramRole.getAccessKeyId()) && isNotEmpty(ramRole.getAccessKeySecret())) {
        return AliyunCredential.builder()
            .accessKeyId(ramRole.getAccessKeyId())
            .accessKeySecret(ramRole.getAccessKeySecret())
            .securityToken("")
            .expirationTimeInEpochMillis(0L)
            .build();
      }
      throw new BaseException(
          ErrorCode.FAILED_PRECONDITION,
          "Aliyun storage credential has neither a RAM role ARN nor a complete static AK/SK.");
    }

    // No external-location credential found: fall back to per-bucket config (static or STS).
    OssStorageConfig config = perBucketOssConfigs.get(context.getStorageBase());
    if (config == null) {
      throw new BaseException(ErrorCode.FAILED_PRECONDITION, "OSS bucket configuration not found.");
    }
    AliyunCredentialGenerator generator =
        perBucketCredGenerators.computeIfAbsent(
            context.getStorageBase(), storageBase -> createPerBucketCredentialGenerator(config));
    return generator.generate(context);
  }

  private static boolean isNotEmpty(String value) {
    return value != null && !value.isEmpty();
  }

  private AliyunCredentialGenerator createPerBucketCredentialGenerator(OssStorageConfig config) {
    // Allow a fully custom generator to be plugged in for testing or special schemes.
    if (config.getCredentialGenerator() != null) {
      try {
        return (AliyunCredentialGenerator)
            Class.forName(config.getCredentialGenerator()).getDeclaredConstructor().newInstance();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    // A RAM role ARN selects the STS mode; otherwise fall back to static AK/SK. Both modes
    // return the same AliyunCredential shape.
    if (config.getRamRoleArn() != null && !config.getRamRoleArn().isEmpty()) {
      return new AliyunCredentialGenerator.StsAliyunCredentialGenerator(config);
    }
    return new AliyunCredentialGenerator.StaticAliyunCredentialGenerator(config);
  }
}
