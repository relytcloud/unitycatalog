package io.unitycatalog.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import io.unitycatalog.server.base.auth.BaseAuthCRUDTest;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The deployment template ships server.admin-password blank, which is how the administrator entry
 * point is switched off. It must then refuse every attempt -- including one that guesses an empty
 * password -- and say so through the providers endpoint so the UI does not offer the form.
 */
public class AuthServiceAdminLoginDisabledTest extends BaseAuthCRUDTest {

  private static final String AUTH_PATH = "/api/1.0/unity-control/auth";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private WebClient client;

  @BeforeEach
  @Override
  public void setUp() {
    super.setUp();
    client = WebClient.builder(serverConfig.getServerUrl()).build();
  }

  @Test
  public void signInIsRefusedWhenNoPasswordIsConfigured() {
    AggregatedHttpResponse response = login("admin", "anything");

    assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.contentUtf8()).contains("Administrator sign-in is not configured");
    assertThat(response.headers().getAll(HttpHeaderNames.SET_COOKIE))
        .noneMatch(c -> c.startsWith(AuthDecorator.UC_TOKEN_KEY + "="));
  }

  /** A blank property must not become a blank password that anyone can send. */
  @Test
  public void anEmptyPasswordDoesNotOpenASession() {
    AggregatedHttpResponse response = login("admin", "");

    assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.headers().getAll(HttpHeaderNames.SET_COOKIE))
        .noneMatch(c -> c.startsWith(AuthDecorator.UC_TOKEN_KEY + "="));
  }

  @Test
  public void providersReportAdminLoginOff() throws IOException {
    AggregatedHttpResponse response =
        client
            .execute(RequestHeaders.of(HttpMethod.GET, AUTH_PATH + "/providers"))
            .aggregate()
            .join();

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    JsonNode body = MAPPER.readTree(response.contentUtf8());
    assertThat(body.get("authorization_enabled").asBoolean()).isTrue();
    assertThat(body.get("admin_login").asBoolean()).isFalse();
  }

  private AggregatedHttpResponse login(String username, String password) {
    RequestHeaders headers =
        RequestHeaders.builder()
            .method(HttpMethod.POST)
            .path(AUTH_PATH + "/admin/login")
            .contentType(MediaType.FORM_DATA)
            .build();
    return client
        .execute(headers, HttpData.ofUtf8("username=" + username + "&password=" + password))
        .aggregate()
        .join();
  }
}
