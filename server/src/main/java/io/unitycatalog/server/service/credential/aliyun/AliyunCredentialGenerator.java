package io.unitycatalog.server.service.credential.aliyun;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.profile.IClientProfile;
import com.aliyuncs.sts.model.v20150401.AssumeRoleRequest;
import com.aliyuncs.sts.model.v20150401.AssumeRoleResponse;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.model.AliyunRamRoleResponse;
import io.unitycatalog.server.persist.dao.CredentialDAO;
import io.unitycatalog.server.service.credential.CredentialContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Generates Aliyun OSS credentials based on the provided {@link CredentialContext}. The Aliyun
 * counterpart of {@link io.unitycatalog.server.service.credential.aws.AwsCredentialGenerator}.
 *
 * <p>Two implementations are provided, sharing the same {@link AliyunCredential} return type so the
 * caller is agnostic to the scheme:
 *
 * <ul>
 *   <li>{@link StaticAliyunCredentialGenerator}: returns fixed long-lived AK/SK from configuration.
 *       No STS call, no expiration.
 *   <li>{@link StsAliyunCredentialGenerator}: assumes a RAM role via Aliyun STS to obtain scoped,
 *       short-lived credentials.
 * </ul>
 */
public interface AliyunCredentialGenerator {
  AliyunCredential generate(CredentialContext ctx);

  class StaticAliyunCredentialGenerator implements AliyunCredentialGenerator {
    private final String accessKeyId;
    private final String accessKeySecret;
    private final String securityToken;

    public StaticAliyunCredentialGenerator(OssStorageConfig config) {
      this.accessKeyId = config.getAccessKey();
      this.accessKeySecret = config.getSecretKey();
      this.securityToken = config.getSecurityToken();
    }

    @Override
    public AliyunCredential generate(CredentialContext ctx) {
      return AliyunCredential.builder()
          .accessKeyId(accessKeyId)
          .accessKeySecret(accessKeySecret)
          .securityToken(securityToken)
          // Static credentials do not expire; 0 signals "no expiration" to the caller.
          .expirationTimeInEpochMillis(0L)
          .build();
    }
  }

  class StsAliyunCredentialGenerator implements AliyunCredentialGenerator {
    private static final int SESSION_DURATION_SECONDS = (int) Duration.ofHours(1).toSeconds();

    private final IAcsClient acsClient;
    // Fallback RAM role ARN for the per-bucket STS config (oss.ramRoleArn.N). Used only when the
    // request carries no external-location credential; when a CredentialDAO is present, the role
    // from the credential securable wins (see generate()). Null for the master-role generator, and
    // unused by deployments that vend via external-location credentials (e.g. the per-user scheme).
    private final String staticRamRoleArn;

    public StsAliyunCredentialGenerator(OssStorageConfig config) {
      String region = config.getRegion();
      if (region == null || region.isEmpty()) {
        throw new BaseException(
            ErrorCode.FAILED_PRECONDITION, "OSS region is required for the STS credential scheme.");
      }
      IClientProfile profile =
          DefaultProfile.getProfile(region, config.getAccessKey(), config.getSecretKey());
      this.acsClient = new DefaultAcsClient(profile);
      this.staticRamRoleArn = config.getRamRoleArn();
    }

    /**
     * Visible for testing: inject a pre-built (mock) STS client and role ARN, bypassing real client
     * construction so the assume-role / policy / response-mapping logic can be unit-tested without
     * calling Aliyun.
     */
    StsAliyunCredentialGenerator(IAcsClient acsClient, String staticRamRoleArn) {
      this.acsClient = acsClient;
      this.staticRamRoleArn = staticRamRoleArn;
    }

    @Override
    public AliyunCredential generate(CredentialContext ctx) {
      Optional<AliyunRamRoleResponse> ramRole =
          ctx.getCredentialDAO().map(CredentialDAO::getAliyunRamRoleResponse);
      String roleArn = ramRole.map(AliyunRamRoleResponse::getRoleArn).orElse(staticRamRoleArn);
      if (roleArn == null || roleArn.isEmpty()) {
        throw new BaseException(
            ErrorCode.FAILED_PRECONDITION, "No Aliyun RAM role ARN available to assume.");
      }

      String policy = AliyunPolicyGenerator.generatePolicy(ctx.getPrivileges(), ctx.getLocations());

      AssumeRoleRequest request = new AssumeRoleRequest();
      request.setRoleArn(roleArn);
      request.setRoleSessionName("uc-%s".formatted(UUID.randomUUID()));
      request.setDurationSeconds((long) SESSION_DURATION_SECONDS);
      request.setPolicy(policy);

      try {
        AssumeRoleResponse response = acsClient.getAcsResponse(request);
        AssumeRoleResponse.Credentials credentials = response.getCredentials();
        return AliyunCredential.builder()
            .accessKeyId(credentials.getAccessKeyId())
            .accessKeySecret(credentials.getAccessKeySecret())
            .securityToken(credentials.getSecurityToken())
            // Aliyun returns an ISO-8601 UTC string, e.g. 2026-06-10T03:30:00Z.
            .expirationTimeInEpochMillis(Instant.parse(credentials.getExpiration()).toEpochMilli())
            .build();
      } catch (com.aliyuncs.exceptions.ClientException e) {
        throw translateAssumeRoleFailure(roleArn, e);
      }
    }

    /**
     * Maps an Aliyun STS {@link com.aliyuncs.exceptions.ClientException} to a {@link BaseException}
     * with an appropriate error code. A misconfigured credential -- role ARN that does not exist,
     * is malformed, or cannot be assumed by this identity -- is a user/admin error and must surface
     * as a 4xx; the previous blanket {@link ErrorCode#INTERNAL} (500) reads as a server bug,
     * misleads callers into retrying, and pollutes server error metrics. Genuinely
     * transient/server-side failures stay 500.
     */
    private static BaseException translateAssumeRoleFailure(
        String roleArn, com.aliyuncs.exceptions.ClientException e) {
      String errCode = e.getErrCode();
      String detail = e.getErrMsg() != null ? e.getErrMsg() : e.getMessage();
      String message =
          "Failed to assume Aliyun RAM role '"
              + roleArn
              + "'"
              + (errCode == null ? "" : " (Aliyun error " + errCode + ")")
              + ": "
              + detail;
      if (errCode == null) {
        // No structured error code (e.g. network/timeout): treat as a transient server-side
        // failure.
        return new BaseException(ErrorCode.INTERNAL, message, e);
      }
      if (errCode.startsWith("EntityNotExist")
          || errCode.startsWith("InvalidParameter")
          || errCode.equals("InvalidAccessKeyId.NotFound")
          || errCode.equals("SignatureDoesNotMatch")) {
        // Role missing / ARN malformed / server credential wrong: a misconfiguration, not a bug.
        return new BaseException(ErrorCode.FAILED_PRECONDITION, message, e);
      }
      if (errCode.equals("NoPermission")
          || errCode.startsWith("NoPermission.")
          || errCode.equals("AccessDenied")) {
        // Role exists but cannot be assumed with the current identity / trust policy.
        return new BaseException(ErrorCode.PERMISSION_DENIED, message, e);
      }
      // Throttling / ServiceUnavailable / InternalError / unknown: transient or server-side.
      return new BaseException(ErrorCode.INTERNAL, message, e);
    }
  }
}
