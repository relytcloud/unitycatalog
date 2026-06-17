package io.unitycatalog.server.service.credential.aliyun;

import lombok.Builder;
import lombok.Getter;

/**
 * Cloud-agnostic holder for Aliyun OSS credentials produced by an {@link
 * AliyunCredentialGenerator}. Mirrors {@link
 * io.unitycatalog.server.service.credential.azure.AzureCredential} so that {@link
 * io.unitycatalog.server.service.credential.CloudCredentialVendor} does not depend on the Aliyun
 * SDK types.
 *
 * <p>For the static AK/SK mode {@code securityToken} is empty and {@code
 * expirationTimeInEpochMillis} is 0 (no expiration); for the STS mode all fields are populated.
 */
@Getter
@Builder
public class AliyunCredential {
  private String accessKeyId;
  private String accessKeySecret;
  private String securityToken;
  private long expirationTimeInEpochMillis;
}
