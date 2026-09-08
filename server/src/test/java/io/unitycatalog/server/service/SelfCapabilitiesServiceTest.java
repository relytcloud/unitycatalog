package io.unitycatalog.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import io.unitycatalog.server.base.auth.BaseAuthCRUDTest;
import java.io.IOException;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Metastore ownership is not observable through any other read endpoint, so a client that must
 * decide whether to offer an admin-only action has no way to ask. These tests pin the answer this
 * endpoint gives for the two identities that matter.
 */
public class SelfCapabilitiesServiceTest extends BaseAuthCRUDTest {

  private static final String CAPABILITIES_ENDPOINT = "/api/1.0/unity-control/auth/capabilities";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private WebClient client;

  @BeforeEach
  @Override
  public void setUp() {
    super.setUp();
    client = WebClient.builder(serverConfig.getServerUrl()).build();
  }

  @Test
  public void testAdminIsReportedAsMetastoreAdmin() throws IOException {
    AggregatedHttpResponse response = getCapabilities(securityContext.getServiceToken());

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(body(response).get("metastore_admin").asBoolean()).isTrue();
  }

  /** A valid token for someone who does not own the metastore must not claim admin. */
  @Test
  public void testNonAdminIsNotReportedAsMetastoreAdmin() throws IOException {
    String token =
        com.auth0
            .jwt
            .JWT
            .create()
            .withSubject("someone-else@example.com")
            .withIssuer(testIssuer)
            .withKeyId(testIssuerKeyId)
            .withAudience(TEST_AUDIENCE)
            .withIssuedAt(new Date())
            .withJWTId(UUID.randomUUID().toString())
            .sign(testIssuerAlgorithm);

    AggregatedHttpResponse response = getCapabilities(token);

    // Either the token is rejected outright or the caller is reported as a non-admin; what must
    // never happen is an unknown subject being handed admin capability.
    if (response.status() == HttpStatus.OK) {
      assertThat(body(response).get("metastore_admin").asBoolean()).isFalse();
    } else {
      assertThat(response.status().code()).isIn(401, 403);
    }
  }

  @Test
  public void testUnauthenticatedIsRejected() {
    AggregatedHttpResponse response =
        client.execute(RequestHeaders.of(HttpMethod.GET, CAPABILITIES_ENDPOINT)).aggregate().join();

    assertThat(response.status().code()).isIn(401, 403);
  }

  private AggregatedHttpResponse getCapabilities(String token) {
    return client
        .execute(
            RequestHeaders.builder(HttpMethod.GET, CAPABILITIES_ENDPOINT)
                .add(HttpHeaderNames.AUTHORIZATION, "Bearer " + token)
                .build())
        .aggregate()
        .join();
  }

  private JsonNode body(AggregatedHttpResponse response) throws IOException {
    return MAPPER.readTree(response.content().toStringUtf8());
  }
}
