package io.unitycatalog.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
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
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Microsoft Entra ID configured the way an operator is meant to configure it: a tenant id and a
 * client id, and nothing in {@code server.allowed-issuers} or {@code server.audiences} at all.
 *
 * <p>The pieces were unit-tested in isolation -- ServerPropertiesEntraTest derives the issuer and
 * audience, JwksOperationsTest routes between the static file and discovery -- but nothing drove
 * AuthService with a tenant configured, so nothing showed that the derived issuer actually reaches
 * the allow-list check, that the derived audience actually reaches {@code withAnyOfAudience}, or
 * that a configured tenant's issuer still resolves from the static JWKS file. Each of those is one
 * line away from being silently inert.
 *
 * <p>Everything here runs offline. The JWKS file declares the DERIVED issuer, so resolution takes
 * the file route; had it fallen through to OIDC discovery the exchange would have gone to
 * login.microsoftonline.com and could not have returned a token signed by this test's key.
 */
public class AuthServiceEntraTest extends BaseServerTest {

  private static final String TOKEN_ENDPOINT = "/api/1.0/unity-control/auth/tokens";
  private static final String TENANT = "11111111-2222-3333-4444-555555555555";
  private static final String ENTRA_ISSUER =
      "https://login.microsoftonline.com/" + TENANT + "/v2.0";
  private static final String OTHER_TENANT_ISSUER =
      "https://login.microsoftonline.com/99999999-8888-7777-6666-555555555555/v2.0";
  private static final String CLIENT_ID = "api://unity-catalog-entra-client-id";
  private static final String USER_EMAIL = "entra.user@example.com";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private WebClient client;
  private Algorithm algorithm;
  private String keyId;

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(Property.AUTHORIZATION_ENABLED.getKey(), "enable");
    // The whole Entra configuration: a tenant and a client id. server.allowed-issuers and
    // server.audiences are deliberately left unset, so the exchange can only work if BOTH the
    // issuer and the audience are derived from the tenant.
    serverProperties.setProperty("server.entra.tenant-id", TENANT);
    serverProperties.setProperty(Property.CLIENT_ID.getKey(), CLIENT_ID);
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var keyPair = generator.generateKeyPair();
      RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
      algorithm = Algorithm.RSA512(publicKey, (RSAPrivateKey) keyPair.getPrivate());
      keyId = UUID.randomUUID().toString();
      Path jwksFile = Files.createTempFile("uc-jwks", ".json");
      Files.writeString(jwksFile, "{\"keys\":[" + jwk(publicKey, keyId, ENTRA_ISSUER) + "]}");
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
  public void entraTokenIsExchangedWithOnlyATenantAndClientIdConfigured() {
    provisionUser(USER_EMAIL);

    AggregatedHttpResponse response = exchangeToken(entraToken(ENTRA_ISSUER, CLIENT_ID));

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    // The principal admitted is the one stamped into the issued token.
    assertThat(subjectOfIssuedToken(response)).isEqualTo(USER_EMAIL);
  }

  @Test
  public void derivedAudienceIsEnforcedNotJustSatisfied() {
    // The derived audience is what makes the exchange above work, so it has to be checked as well
    // as present: a token for a different resource in the same tenant must not be accepted.
    provisionUser(USER_EMAIL);

    AggregatedHttpResponse response =
        exchangeToken(entraToken(ENTRA_ISSUER, "api://some-other-application"));

    assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  public void anotherTenantUnderTheEntraAuthorityIsNotTrusted() {
    // Deriving one tenant's issuer must not admit the whole of login.microsoftonline.com. The
    // rejection also happens before any key lookup, so no request leaves the process.
    provisionUser(USER_EMAIL);

    AggregatedHttpResponse response = exchangeToken(entraToken(OTHER_TENANT_ISSUER, CLIENT_ID));

    assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.contentUtf8()).contains("Invalid issuer");
  }

  @Test
  public void unprovisionedEntraUserIsRejectedWithEntraSpecificAdvice() {
    // No provisionUser call: the token verifies, and only the principal lookup fails.
    AggregatedHttpResponse response = exchangeToken(entraToken(ENTRA_ISSUER, CLIENT_ID));

    assertThat(response.status()).isNotEqualTo(HttpStatus.OK);
    assertThat(response.contentUtf8()).contains(USER_EMAIL);
    assertThat(response.contentUtf8()).contains("not provisioned");
  }

  @SneakyThrows
  private String subjectOfIssuedToken(AggregatedHttpResponse response) {
    JsonNode body = MAPPER.readTree(response.contentUtf8());
    return JWT.decode(body.get("access_token").asText()).getClaim("sub").asString();
  }

  /** An Entra v2.0-shaped token: an opaque GUID 'sub' plus the 'email' the user is known by. */
  private String entraToken(String issuer, String audience) {
    return JWT.create()
        .withSubject("00000000-1111-2222-3333-444444444444")
        .withIssuer(issuer)
        .withAudience(audience)
        .withClaim("email", USER_EMAIL)
        .withIssuedAt(new Date())
        .withKeyId(keyId)
        .withJWTId(UUID.randomUUID().toString())
        .sign(algorithm);
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

  /** A single JWKS entry carrying the {@code issuer} member that scopes the key to one issuer. */
  private static String jwk(RSAPublicKey publicKey, String keyId, String issuer) {
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String n = encoder.encodeToString(toUnsignedBytes(publicKey.getModulus()));
    String e = encoder.encodeToString(toUnsignedBytes(publicKey.getPublicExponent()));
    return String.format(
        "{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS512\",\"kid\":\"%s\","
            + "\"n\":\"%s\",\"e\":\"%s\",\"issuer\":\"%s\"}",
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
