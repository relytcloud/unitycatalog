package io.unitycatalog.server.service.credential.aliyun;

import io.unitycatalog.server.service.credential.CredentialContext;

/**
 * Test-only generator that issues fixed short-lived STS-shaped credentials (with a security token
 * and an expiration). Loaded reflectively via {@code OssStorageConfig.credentialGenerator} so that
 * {@link AliyunCredentialVendorTest} can verify per-bucket vending and the STS response mapping
 * without calling Aliyun. Mirrors the GCP {@code StaticTestingCredentialGenerator}.
 */
public class StaticTestingAliyunCredentialGenerator implements AliyunCredentialGenerator {

  public static final String ACCESS_KEY_ID = "testing-ak";
  public static final String ACCESS_KEY_SECRET = "testing-sk";
  public static final String SECURITY_TOKEN = "testing://security-token";
  public static final long EXPIRATION_EPOCH_MILLIS = 4102444800000L; // 2100-01-01T00:00:00Z

  @Override
  public AliyunCredential generate(CredentialContext context) {
    return AliyunCredential.builder()
        .accessKeyId(ACCESS_KEY_ID)
        .accessKeySecret(ACCESS_KEY_SECRET)
        .securityToken(SECURITY_TOKEN)
        .expirationTimeInEpochMillis(EXPIRATION_EPOCH_MILLIS)
        .build();
  }
}
