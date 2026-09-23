package io.unitycatalog.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.auth0.jwt.JWT;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import io.unitycatalog.server.base.auth.BaseAuthCRUDTest;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every token exchange used to fetch the issuer's discovery document and JWKS afresh -- two
 * round-trips to a third party per login (issue #15). Both are now resolved once per issuer and
 * reused, while an unseen kid still triggers a JWKS fetch so key rotation keeps working.
 */
public class AuthServiceDiscoveryCachingTest extends BaseAuthCRUDTest {

  private static final String TOKEN_ENDPOINT = "/api/1.0/unity-control/auth/tokens";
  /** Trusted, but nothing listens there: connection refused, immediately. */
  private static final String UNREACHABLE_ISSUER = "http://127.0.0.1:1";

  private WebClient client;

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty("server.allowed-issuers", testIssuer + "," + UNREACHABLE_ISSUER);
  }

  @BeforeEach
  @Override
  public void setUp() {
    super.setUp();
    client = WebClient.builder(serverConfig.getServerUrl()).build();
  }

  @Test
  public void discoveryAndJwksAreFetchedOnceAcrossExchanges() {
    assertThat(exchange(testIssuerKeyId).status()).isEqualTo(HttpStatus.OK);
    assertThat(exchange(testIssuerKeyId).status()).isEqualTo(HttpStatus.OK);
    assertThat(exchange(testIssuerKeyId).status()).isEqualTo(HttpStatus.OK);

    assertThat(discoveryRequests.get()).isEqualTo(1);
    assertThat(jwksRequests.get()).isEqualTo(1);
  }

  /** Rotation: a kid the cache has not seen must reach the JWKS endpoint again. */
  @Test
  public void unseenKidRefetchesTheJwks() {
    assertThat(exchange(testIssuerKeyId).status()).isEqualTo(HttpStatus.OK);
    assertThat(jwksRequests.get()).isEqualTo(1);

    assertThat(exchange(UUID.randomUUID().toString()).status()).isEqualTo(HttpStatus.UNAUTHORIZED);

    assertThat(jwksRequests.get()).isEqualTo(2);
    // The discovery document itself is not re-read for a kid miss.
    assertThat(discoveryRequests.get()).isEqualTo(1);
  }

  /**
   * A trusted issuer that cannot be reached is an authentication failure for that token, not a
   * server fault: the client must get a 401 with a JSON error, never a bodyless 500 from an escaped
   * CompletionException.
   */
  @Test
  public void unreachableIssuerIsRejectedAsUnauthenticated() throws java.io.IOException {
    AggregatedHttpResponse response = exchange(UNREACHABLE_ISSUER, testIssuerKeyId);

    assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    com.fasterxml.jackson.databind.JsonNode body =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.contentUtf8());
    assertThat(body.get("error_code").asText()).isEqualTo("UNAUTHENTICATED");
    assertThat(body.get("message").asText()).contains(UNREACHABLE_ISSUER);
    // Nothing was cached for the failed issuer; a later attempt would try again.
    assertThat(discoveryRequests.get()).isEqualTo(0);
  }

  private AggregatedHttpResponse exchange(String keyId) {
    return exchange(testIssuer, keyId);
  }

  private AggregatedHttpResponse exchange(String issuer, String keyId) {
    String token =
        JWT.create()
            .withSubject("admin")
            .withIssuer(issuer)
            .withAudience(TEST_AUDIENCE)
            .withIssuedAt(new Date())
            .withKeyId(keyId)
            .withJWTId(UUID.randomUUID().toString())
            .sign(testIssuerAlgorithm);
    String form =
        "grant_type=urn:ietf:params:oauth:grant-type:token-exchange"
            + "&requested_token_type=urn:ietf:params:oauth:token-type:access_token"
            + "&subject_token_type=urn:ietf:params:oauth:token-type:id_token"
            + "&subject_token="
            + token;
    RequestHeaders headers =
        RequestHeaders.builder()
            .method(HttpMethod.POST)
            .path(TOKEN_ENDPOINT)
            .contentType(MediaType.FORM_DATA)
            .build();
    return client.execute(headers, HttpData.ofUtf8(form)).aggregate().join();
  }
}
