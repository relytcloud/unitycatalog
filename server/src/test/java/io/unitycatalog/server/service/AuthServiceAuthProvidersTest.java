package io.unitycatalog.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import io.unitycatalog.server.base.BaseServerTest;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * With authorization switched off the UI must not put itself behind a login page it cannot get
 * past: the providers endpoint tells it so, and is reachable without any token. The enabled-side
 * combinations are asserted in the admin-login and hosted-login test classes.
 */
public class AuthServiceAuthProvidersTest extends BaseServerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  public void reportsAuthorizationDisabledWithNoSignInMethods() throws IOException {
    WebClient client = WebClient.builder(serverConfig.getServerUrl()).build();

    AggregatedHttpResponse response =
        client
            .execute(RequestHeaders.of(HttpMethod.GET, "/api/1.0/unity-control/auth/providers"))
            .aggregate()
            .join();

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    JsonNode body = MAPPER.readTree(response.contentUtf8());
    assertThat(body.get("authorization_enabled").asBoolean()).isFalse();
    assertThat(body.get("hosted_login").asBoolean()).isFalse();
    assertThat(body.get("admin_login").asBoolean()).isFalse();
  }
}
