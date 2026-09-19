package io.unitycatalog.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import io.unitycatalog.server.base.BaseServerTest;
import io.unitycatalog.server.utils.ServerProperties.Property;
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
 * A tenant id, and nothing else that could confer trust.
 *
 * <p>{@link AuthServiceEntraTest} has to declare the Entra issuer in the static JWKS file, because
 * that is the only way a configured tenant's keys resolve without reaching out to
 * login.microsoftonline.com -- but declaring it there ALSO makes the issuer trusted, via {@code
 * JwksOperations.knownIssuers()}. So that test cannot show the derived issuer reaching
 * AuthService's trust lists at all.
 *
 * <p>Here the JWKS file holds no keys, so {@code knownIssuers()} is empty and {@code
 * server.allowed-issuers} and {@code server.audiences} are unset. Every trusted issuer and every
 * accepted audience the server has is therefore derived from the tenant id, and whether the
 * derivation happened is visible in which refusal comes back: the exchange is rejected outright as
 * unconfigured when the lists are empty, and only judges the token when they are not. No key lookup
 * is reached, so nothing leaves the process.
 */
public class AuthServiceEntraTrustDerivationTest extends BaseServerTest {

  private static final String TOKEN_ENDPOINT = "/api/1.0/unity-control/auth/tokens";
  private static final String TENANT = "11111111-2222-3333-4444-555555555555";
  private static final String CLIENT_ID = "api://unity-catalog-entra-client-id";

  private WebClient client;
  private Algorithm algorithm;

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(Property.AUTHORIZATION_ENABLED.getKey(), "enable");
    serverProperties.setProperty("server.entra.tenant-id", TENANT);
    serverProperties.setProperty(Property.CLIENT_ID.getKey(), CLIENT_ID);
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var keyPair = generator.generateKeyPair();
      algorithm =
          Algorithm.RSA512(
              (RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
      // An empty key set: configured, readable, and conferring no trust on anyone.
      Path jwksFile = Files.createTempFile("uc-jwks", ".json");
      Files.writeString(jwksFile, "{\"keys\":[]}");
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
  public void tenantIdAloneConfiguresATrustedIssuerAndAnAcceptedAudience() {
    // Were nothing derived, both lists would be empty and this would come back 400 "No trusted
    // issuers configured" -- the server refusing to judge the token at all. Getting a 401 that
    // names the issuer means the derived values reached both startup guards and the allow-list.
    AggregatedHttpResponse response = exchangeToken(tokenFrom("https://some-other-idp.example"));

    assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.contentUtf8()).contains("Invalid issuer");
    assertThat(response.contentUtf8()).doesNotContain("No trusted issuers configured");
    assertThat(response.contentUtf8()).doesNotContain("No audiences configured");
  }

  private String tokenFrom(String issuer) {
    return JWT.create()
        .withSubject("someone@example.com")
        .withIssuer(issuer)
        .withAudience(CLIENT_ID)
        .withIssuedAt(new Date())
        .withKeyId(UUID.randomUUID().toString())
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
}
