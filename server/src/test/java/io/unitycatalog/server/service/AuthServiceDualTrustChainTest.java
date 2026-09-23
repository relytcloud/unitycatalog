package io.unitycatalog.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import io.unitycatalog.server.base.auth.BaseAuthCRUDTest;
import io.unitycatalog.server.base.auth.JwksTestUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Both trust chains configured on one server (issue #15): a Relyt instance whose key sits in the
 * static external JWKS file, and an OIDC provider reached through discovery (the base class's mock
 * IdP, listed in {@code server.allowed-issuers}). The file used to be consulted unconditionally
 * whenever it was configured, so the OIDC token never reached discovery. Each chain must verify its
 * own tokens and neither chain's key may vouch for the other's issuer.
 */
public class AuthServiceDualTrustChainTest extends BaseAuthCRUDTest {

  private static final String TOKEN_ENDPOINT = "/api/1.0/unity-control/auth/tokens";
  private static final String LOCAL_ISSUER = "relyt-instance-local";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private WebClient client;
  private Algorithm localIssuerAlgorithm;
  private String localIssuerKeyId;

  @Override
  protected void setUpProperties() {
    // Mock OIDC issuer in allowed-issuers + audiences.
    super.setUpProperties();
    // Plus a bare-identifier issuer registered in the static external JWKS file -- the default
    // shape of a Relyt deployment.
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var keyPair = generator.generateKeyPair();
      RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
      localIssuerAlgorithm = Algorithm.RSA512(publicKey, (RSAPrivateKey) keyPair.getPrivate());
      localIssuerKeyId = UUID.randomUUID().toString();

      Path jwksFile = Files.createTempFile("uc-dual-jwks", ".json");
      Files.writeString(
          jwksFile, JwksTestUtils.issuerBoundJwks(publicKey, localIssuerKeyId, LOCAL_ISSUER));
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
  public void localIssuerIsVerifiedFromTheFile() throws IOException {
    AggregatedHttpResponse response =
        exchangeToken(signedToken(LOCAL_ISSUER, localIssuerAlgorithm, localIssuerKeyId));

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(MAPPER.readTree(response.contentUtf8()).get("access_token").asText()).isNotEmpty();
  }

  /** The regression: with the file configured, an OIDC issuer must still reach discovery. */
  @Test
  public void oidcIssuerIsVerifiedViaDiscoveryWhileTheFileIsConfigured() throws IOException {
    AggregatedHttpResponse response =
        exchangeToken(signedToken(testIssuer, testIssuerAlgorithm, testIssuerKeyId));

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(MAPPER.readTree(response.contentUtf8()).get("access_token").asText()).isNotEmpty();
  }

  /** A key registered for the local issuer must not sign tokens claiming the OIDC issuer. */
  @Test
  public void localKeyDoesNotVouchForTheOidcIssuer() {
    AggregatedHttpResponse response =
        exchangeToken(signedToken(testIssuer, localIssuerAlgorithm, localIssuerKeyId));

    assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  /** Nor may the OIDC provider's key sign tokens claiming the local issuer. */
  @Test
  public void oidcKeyDoesNotVouchForTheLocalIssuer() {
    AggregatedHttpResponse response =
        exchangeToken(signedToken(LOCAL_ISSUER, testIssuerAlgorithm, testIssuerKeyId));

    assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  private static String signedToken(String issuer, Algorithm algorithm, String keyId) {
    return JWT.create()
        .withSubject("admin")
        .withIssuer(issuer)
        .withAudience(TEST_AUDIENCE)
        .withIssuedAt(new Date())
        .withKeyId(keyId)
        .withJWTId(UUID.randomUUID().toString())
        .sign(algorithm);
  }

  private AggregatedHttpResponse exchangeToken(String subjectToken) {
    String form =
        "grant_type=urn:ietf:params:oauth:grant-type:token-exchange"
            + "&requested_token_type=urn:ietf:params:oauth:token-type:access_token"
            + "&subject_token_type=urn:ietf:params:oauth:token-type:id_token"
            + "&subject_token="
            + subjectToken;
    RequestHeaders headers =
        RequestHeaders.builder()
            .method(HttpMethod.POST)
            .path(TOKEN_ENDPOINT)
            .contentType(MediaType.FORM_DATA)
            .build();
    return client.execute(headers, HttpData.ofUtf8(form)).aggregate().join();
  }
}
