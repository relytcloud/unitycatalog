package io.unitycatalog.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import io.unitycatalog.server.base.BaseServerTest;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The two ways a principal can fail must read differently. A subject token with no "email" claim
 * falls back to "sub" -- for an Entra token that is an opaque GUID -- and used to fail as "User not
 * allowed: &lt;guid&gt;", which reads like an authorization decision rather than a missing optional
 * claim on the app registration.
 */
public class AuthServicePrincipalErrorsTest extends BaseServerTest {

  private static final String TEST_AUDIENCE = "unity-catalog";
  private static final String TOKEN_ENDPOINT = "/api/1.0/unity-control/auth/tokens";
  private static final String ISSUER = "relyt-instance-known";
  private static final String NON_ADMIN_SUB = "00000000-1111-2222-3333-444444444444";

  private WebClient client;
  private Algorithm algorithm;
  private String keyId;

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(Property.AUTHORIZATION_ENABLED.getKey(), "enable");
    serverProperties.setProperty("server.audiences", TEST_AUDIENCE);
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var keyPair = generator.generateKeyPair();
      RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
      algorithm = Algorithm.RSA512(publicKey, (RSAPrivateKey) keyPair.getPrivate());
      keyId = UUID.randomUUID().toString();
      Path jwksFile = Files.createTempFile("uc-jwks", ".json");
      Files.writeString(jwksFile, buildJwksJson(publicKey, keyId, ISSUER));
      serverProperties.setProperty("server.external-jwks-file", jwksFile.toString());
    } catch (Exception e) {
      throw new RuntimeException("Failed to set up external JWKS file", e);
    }
  }

  @BeforeEach
  @Override
  public void setUp() {
    super.setUp();
    client = WebClient.builder(serverConfig.getServerUrl()).build();
  }

  @Test
  public void tokenWithoutEmailClaimNamesTheMissingClaim() {
    AggregatedHttpResponse response = exchangeToken(tokenFor(NON_ADMIN_SUB, null));

    assertThat(response.contentUtf8()).contains("'email' claim");
    assertThat(response.contentUtf8()).contains("optional claim");
  }

  @Test
  public void tokenWithUnknownEmailSaysNotProvisioned() {
    AggregatedHttpResponse response = exchangeToken(tokenFor(NON_ADMIN_SUB, "nobody@example.com"));

    assertThat(response.contentUtf8()).contains("not provisioned");
    assertThat(response.contentUtf8()).contains("nobody@example.com");
  }

  /** A token signed by the registered key. The email claim is included only when non-null. */
  private String tokenFor(String subject, String email) {
    var builder =
        JWT.create()
            .withSubject(subject)
            .withIssuer(ISSUER)
            .withAudience(TEST_AUDIENCE)
            .withIssuedAt(new Date())
            .withKeyId(keyId)
            .withJWTId(UUID.randomUUID().toString());
    if (email != null) {
      builder.withClaim("email", email);
    }
    return builder.sign(algorithm);
  }

  private AggregatedHttpResponse exchangeToken(String identityToken) {
    String formBody =
        "grant_type=urn:ietf:params:oauth:grant-type:token-exchange"
            + "&requested_token_type=urn:ietf:params:oauth:token-type:access_token"
            + "&subject_token_type=urn:ietf:params:oauth:token-type:id_token"
            + "&subject_token="
            + identityToken;

    RequestHeaders headers =
        RequestHeaders.builder()
            .method(HttpMethod.POST)
            .path(TOKEN_ENDPOINT)
            .contentType(MediaType.FORM_DATA)
            .build();

    return client.execute(headers, HttpData.ofUtf8(formBody)).aggregate().join();
  }

  /** Builds a JWKS JSON string with a single RSA key carrying an {@code issuer} member. */
  private static String buildJwksJson(RSAPublicKey publicKey, String keyId, String issuer) {
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String n = encoder.encodeToString(toUnsignedBytes(publicKey.getModulus()));
    String e = encoder.encodeToString(toUnsignedBytes(publicKey.getPublicExponent()));
    return String.format(
        "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS512\",\"kid\":\"%s\","
            + "\"n\":\"%s\",\"e\":\"%s\",\"issuer\":\"%s\"}]}",
        keyId, n, e, issuer);
  }

  /** Converts a BigInteger to unsigned big-endian bytes (no leading zero padding). */
  private static byte[] toUnsignedBytes(BigInteger value) {
    byte[] bytes = value.toByteArray();
    if (bytes[0] == 0) {
      byte[] trimmed = new byte[bytes.length - 1];
      System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
      return trimmed;
    }
    return bytes;
  }
}
