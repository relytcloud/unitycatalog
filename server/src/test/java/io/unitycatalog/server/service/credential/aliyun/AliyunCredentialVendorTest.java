package io.unitycatalog.server.service.credential.aliyun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aliyuncs.IAcsClient;
import com.aliyuncs.sts.model.v20150401.AssumeRoleRequest;
import com.aliyuncs.sts.model.v20150401.AssumeRoleResponse;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.model.AliyunRamRoleResponse;
import io.unitycatalog.server.model.TemporaryCredentials;
import io.unitycatalog.server.persist.dao.CredentialDAO;
import io.unitycatalog.server.service.credential.CloudCredentialVendor;
import io.unitycatalog.server.service.credential.CredentialContext;
import io.unitycatalog.server.service.credential.CredentialContext.Privilege;
import io.unitycatalog.server.utils.NormalizedURL;
import io.unitycatalog.server.utils.ServerProperties;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mock unit tests for the Aliyun OSS credential vending path. None of these tests touch a real
 * Aliyun endpoint:
 *
 * <ul>
 *   <li>static AK/SK — per-bucket config and external-location credential
 *   <li>a pluggable test generator returning STS-shaped credentials
 *   <li>the real STS generator with a mocked {@link IAcsClient} (verifies the AssumeRole request
 *       and response mapping)
 *   <li>error / precondition handling
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
public class AliyunCredentialVendorTest {

  @Mock ServerProperties serverProperties;

  private static CredentialContext context(
      String path, Optional<CredentialDAO> dao, Privilege... privileges) {
    return CredentialContext.create(NormalizedURL.from(path), Set.of(privileges), dao);
  }

  private TemporaryCredentials vend(CredentialContext context) {
    AliyunCredentialVendor vendor = new AliyunCredentialVendor(serverProperties);
    CloudCredentialVendor cloud = new CloudCredentialVendor(null, null, null, vendor);
    return cloud.vendCredential(context);
  }

  @Test
  public void staticPerBucketCredentialReturnedVerbatim() {
    when(serverProperties.getOssConfigurations())
        .thenReturn(
            Map.of(
                NormalizedURL.from("oss://bucketBase"),
                OssStorageConfig.builder()
                    .accessKey("static-ak")
                    .secretKey("static-sk")
                    .securityToken("")
                    .build()));

    TemporaryCredentials creds =
        vend(context("oss://bucketBase/abc", Optional.empty(), Privilege.SELECT));

    assertThat(creds.getAliyunTempCredentials().getAccessKeyId()).isEqualTo("static-ak");
    assertThat(creds.getAliyunTempCredentials().getAccessKeySecret()).isEqualTo("static-sk");
    assertThat(creds.getAliyunTempCredentials().getSecurityToken()).isEmpty();
    // Static credentials never expire -> expiration is left unset.
    assertThat(creds.getExpirationTime()).isNull();
  }

  @Test
  public void staticCredentialFromExternalLocationCredential() {
    when(serverProperties.getOssConfigurations()).thenReturn(Map.of());

    CredentialDAO dao = mock(CredentialDAO.class);
    when(dao.getCredentialType()).thenReturn(CredentialDAO.CredentialType.ALIYUN_RAM_ROLE);
    when(dao.getAliyunRamRoleResponse())
        .thenReturn(new AliyunRamRoleResponse().accessKeyId("loc-ak").accessKeySecret("loc-sk"));

    TemporaryCredentials creds =
        vend(context("oss://bkt/path", Optional.of(dao), Privilege.SELECT));

    assertThat(creds.getAliyunTempCredentials().getAccessKeyId()).isEqualTo("loc-ak");
    assertThat(creds.getAliyunTempCredentials().getAccessKeySecret()).isEqualTo("loc-sk");
    assertThat(creds.getAliyunTempCredentials().getSecurityToken()).isEmpty();
    assertThat(creds.getExpirationTime()).isNull();
  }

  @Test
  public void externalLocationBoundToNonAliyunCredentialThrows() {
    CredentialDAO dao = mock(CredentialDAO.class);
    when(dao.getCredentialType()).thenReturn(CredentialDAO.CredentialType.AWS_IAM_ROLE);

    assertThatThrownBy(() -> vend(context("oss://bkt/path", Optional.of(dao), Privilege.SELECT)))
        .isInstanceOf(BaseException.class);
  }

  @Test
  public void externalLocationCredentialWithNeitherRoleNorKeysThrows() {
    CredentialDAO dao = mock(CredentialDAO.class);
    when(dao.getCredentialType()).thenReturn(CredentialDAO.CredentialType.ALIYUN_RAM_ROLE);
    when(dao.getAliyunRamRoleResponse()).thenReturn(new AliyunRamRoleResponse());

    assertThatThrownBy(() -> vend(context("oss://bkt/path", Optional.of(dao), Privilege.SELECT)))
        .isInstanceOf(BaseException.class);
  }

  @Test
  public void pluggableGeneratorYieldsStsShapedCredentials() {
    when(serverProperties.getOssConfigurations())
        .thenReturn(
            Map.of(
                NormalizedURL.from("oss://bucketBase"),
                OssStorageConfig.builder()
                    .credentialGenerator(StaticTestingAliyunCredentialGenerator.class.getName())
                    .build()));

    TemporaryCredentials creds =
        vend(context("oss://bucketBase/abc", Optional.empty(), Privilege.SELECT));

    assertThat(creds.getAliyunTempCredentials().getAccessKeyId())
        .isEqualTo(StaticTestingAliyunCredentialGenerator.ACCESS_KEY_ID);
    assertThat(creds.getAliyunTempCredentials().getSecurityToken())
        .isEqualTo(StaticTestingAliyunCredentialGenerator.SECURITY_TOKEN);
    // STS-shaped credentials carry an expiration; CloudCredentialVendor must pass it through.
    assertThat(creds.getExpirationTime())
        .isEqualTo(StaticTestingAliyunCredentialGenerator.EXPIRATION_EPOCH_MILLIS);
  }

  @Test
  public void missingBucketConfigThrows() {
    when(serverProperties.getOssConfigurations()).thenReturn(Map.of());

    assertThatThrownBy(() -> vend(context("oss://unknown/abc", Optional.empty(), Privilege.SELECT)))
        .isInstanceOf(BaseException.class);
  }

  // ----- Real STS generator with a mocked Aliyun client (no network) -----

  @Test
  public void stsGeneratorAssumesRoleAndMapsResponse() throws Exception {
    final String roleArn = "acs:ram::123456789012:role/uc-oss";
    final String iso = "2099-01-01T00:00:00Z";

    AssumeRoleResponse.Credentials credentials = new AssumeRoleResponse.Credentials();
    credentials.setAccessKeyId("sts-ak");
    credentials.setAccessKeySecret("sts-sk");
    credentials.setSecurityToken("sts-token");
    credentials.setExpiration(iso);
    AssumeRoleResponse response = new AssumeRoleResponse();
    response.setCredentials(credentials);

    IAcsClient acsClient = mock(IAcsClient.class);
    when(acsClient.getAcsResponse(any(AssumeRoleRequest.class))).thenReturn(response);

    AliyunCredentialGenerator.StsAliyunCredentialGenerator generator =
        new AliyunCredentialGenerator.StsAliyunCredentialGenerator(acsClient, roleArn);

    AliyunCredential cred =
        generator.generate(context("oss://bkt/data/tbl", Optional.empty(), Privilege.SELECT));

    // Response mapping.
    assertThat(cred.getAccessKeyId()).isEqualTo("sts-ak");
    assertThat(cred.getAccessKeySecret()).isEqualTo("sts-sk");
    assertThat(cred.getSecurityToken()).isEqualTo("sts-token");
    assertThat(cred.getExpirationTimeInEpochMillis()).isEqualTo(Instant.parse(iso).toEpochMilli());

    // Request construction: scoped role, 1h session, a non-empty OSS policy.
    ArgumentCaptor<AssumeRoleRequest> requestCaptor =
        ArgumentCaptor.forClass(AssumeRoleRequest.class);
    verify(acsClient).getAcsResponse(requestCaptor.capture());
    AssumeRoleRequest sentRequest = requestCaptor.getValue();
    assertThat(sentRequest.getRoleArn()).isEqualTo(roleArn);
    assertThat(sentRequest.getDurationSeconds()).isEqualTo(3600L);
    assertThat(sentRequest.getPolicy()).contains("oss:GetObject");
  }

  @Test
  public void stsGeneratorWithoutRoleArnThrows() {
    IAcsClient acsClient = mock(IAcsClient.class);
    AliyunCredentialGenerator.StsAliyunCredentialGenerator generator =
        new AliyunCredentialGenerator.StsAliyunCredentialGenerator(acsClient, null);

    assertThatThrownBy(
            () -> generator.generate(context("oss://bkt/data", Optional.empty(), Privilege.SELECT)))
        .isInstanceOf(BaseException.class);
  }
}
