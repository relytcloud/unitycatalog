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
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the "local static JWKS" trust path (issue #4): with {@code server.allowed-issuers} left
 * empty, the set of trusted issuers is derived entirely from the hot-reloaded external JWKS file
 * (its keys' {@code issuer} members). A key registered in the JWKS is accepted without any
 * allow-list entry; an issuer with no key in the JWKS is rejected. This complements {@link
 * AuthServiceTest}, which exercises the OIDC-discovery path via {@code allowed-issuers}.
 */
public class AuthServiceExternalJwksTest extends BaseServerTest {

  private static final String TEST_AUDIENCE = "unity-catalog";
  private static final String TOKEN_ENDPOINT = "/api/1.0/unity-control/auth/tokens";
  private static final String KNOWN_ISSUER = "relyt-instance-known";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private WebClient client;

  // The identity key registered in the external JWKS file (its public half), used to sign tokens
  // claiming KNOWN_ISSUER.
  private Algorithm knownIssuerAlgorithm;
  private String knownIssuerKeyId;

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(Property.AUTHORIZATION_ENABLED.getKey(), "enable");
    serverProperties.setProperty("server.audiences", TEST_AUDIENCE);
    // server.allowed-issuers intentionally left unset (empty) -> trust is governed entirely by the
    // JWKS-derived set (issue #4).
    try {
      KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
      keyPairGenerator.initialize(2048);
      var keyPair = keyPairGenerator.generateKeyPair();
      RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
      knownIssuerAlgorithm = Algorithm.RSA512(publicKey, (RSAPrivateKey) keyPair.getPrivate());
      knownIssuerKeyId = UUID.randomUUID().toString();

      // Static external JWKS file: one key bound to KNOWN_ISSUER via its "issuer" member.
      Path jwksFile = Files.createTempFile("uc-jwks", ".json");
      Files.writeString(jwksFile, buildJwksJson(publicKey, knownIssuerKeyId, KNOWN_ISSUER));
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
  public void tokenExchangeSucceedsForJwksDerivedIssuerWithEmptyAllowList() throws IOException {
    // Issuer is trusted purely because its key is in the JWKS file; allowed-issuers is empty.
    String token =
        createIdentityToken(KNOWN_ISSUER, TEST_AUDIENCE, knownIssuerAlgorithm, knownIssuerKeyId);

    AggregatedHttpResponse response = exchangeToken(token);

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    JsonNode body = MAPPER.readTree(response.contentUtf8());
    assertThat(body.has("access_token")).isTrue();
    assertThat(body.get("access_token").asText()).isNotEmpty();
  }

  @Test
  public void tokenExchangeRejectsIssuerNotInJwks() throws NoSuchAlgorithmException {
    // A different issuer whose key is not in the JWKS (nor the empty allow-list) is rejected.
    KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
    keyPairGenerator.initialize(2048);
    var foreignKeyPair = keyPairGenerator.generateKeyPair();
    Algorithm foreignAlgorithm =
        Algorithm.RSA512(
            (RSAPublicKey) foreignKeyPair.getPublic(), (RSAPrivateKey) foreignKeyPair.getPrivate());

    String token =
        createIdentityToken(
            "relyt-instance-unknown",
            TEST_AUDIENCE,
            foreignAlgorithm,
            UUID.randomUUID().toString());

    AggregatedHttpResponse response = exchangeToken(token);

    assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  public void tokenExchangeRejectsUnknownKeyIdForTrustedIssuer() throws IOException {
    // Issue #7: the issuer is trusted (its key is in the JWKS), but the token carries a kid that is
    // not present in the JWKS. The JWKS lookup throws a com.auth0.jwk exception (a checked, non-
    // RuntimeException sibling of JWTVerificationException). It must surface as 401 with a JSON
    // body, not a bodyless HTTP 500 from Armeria's fallthrough handler.
    String token =
        createIdentityToken(
            KNOWN_ISSUER, TEST_AUDIENCE, knownIssuerAlgorithm, UUID.randomUUID().toString());

    AggregatedHttpResponse response = exchangeToken(token);

    assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    JsonNode body = MAPPER.readTree(response.contentUtf8());
    assertThat(body.has("error_code")).isTrue();
    assertThat(body.get("error_code").asText()).isEqualTo("UNAUTHENTICATED");
  }

  private String createIdentityToken(
      String issuer, String audience, Algorithm algorithm, String keyId) {
    var builder =
        JWT.create()
            .withSubject("admin")
            .withIssuer(issuer)
            .withIssuedAt(new Date())
            .withKeyId(keyId)
            .withJWTId(UUID.randomUUID().toString());
    if (audience != null) {
      builder.withAudience(audience);
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
