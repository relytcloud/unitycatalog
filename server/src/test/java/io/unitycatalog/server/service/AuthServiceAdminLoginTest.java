package io.unitycatalog.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.auth0.jwt.JWT;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The administrator's password sign-in for the UI (issue #15): a hidden entry point that turns the
 * password configured in server.properties into the same UC_TOKEN cookie a Microsoft sign-in ends
 * with, so the rest of the UI does not care how the session was opened.
 */
public class AuthServiceAdminLoginTest extends BaseAuthCRUDTest {

  private static final String AUTH_PATH = "/api/1.0/unity-control/auth";
  private static final String PASSWORD = "correct horse battery staple";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private WebClient client;

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty("server.admin-password", PASSWORD);
  }

  @BeforeEach
  @Override
  public void setUp() {
    super.setUp();
    client = WebClient.builder(serverConfig.getServerUrl()).build();
  }

  @Test
  public void correctPasswordOpensAnAdminSession() throws IOException {
    AggregatedHttpResponse response = login("admin", PASSWORD);

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    JsonNode body = MAPPER.readTree(response.contentUtf8());
    assertThat(body.get("access_token").asText()).isNotEmpty();

    String session = setCookieValue(response, AuthDecorator.UC_TOKEN_KEY);
    assertThat(JWT.decode(session).getSubject()).isEqualTo("admin");

    // The cookie alone must open a protected endpoint as the administrator.
    AggregatedHttpResponse me =
        client
            .execute(
                RequestHeaders.builder(HttpMethod.GET, "/api/1.0/unity-control/scim2/Me")
                    .add(HttpHeaderNames.COOKIE, AuthDecorator.UC_TOKEN_KEY + "=" + session)
                    .build())
            .aggregate()
            .join();
    assertThat(me.status()).isEqualTo(HttpStatus.OK);
    assertThat(MAPPER.readTree(me.contentUtf8()).get("userName").asText()).isEqualTo("admin");
  }

  /**
   * A browser silently drops a Secure cookie that arrives over plain HTTP, which is how the UI is
   * reached in a local or internal-network deployment: the sign-in would look like it worked and
   * leave no session. Strict would be dropped on the way back from an identity provider.
   */
  @Test
  public void theSessionCookieSurvivesAPlainHttpDeployment() {
    AggregatedHttpResponse response = login("admin", PASSWORD);

    String setCookie = setCookieHeader(response, AuthDecorator.UC_TOKEN_KEY);
    assertThat(setCookie).doesNotContain("Secure").contains("SameSite=Lax").contains("HTTPOnly");
  }

  /** Behind a TLS-terminating proxy the cookie must be Secure again. */
  @Test
  public void theSessionCookieIsSecureWhenTheBrowserIsOnHttps() {
    String form = "username=admin&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8);
    AggregatedHttpResponse response =
        client
            .execute(
                RequestHeaders.builder()
                    .method(HttpMethod.POST)
                    .path(AUTH_PATH + "/admin/login")
                    .contentType(MediaType.FORM_DATA)
                    .add(HttpHeaderNames.X_FORWARDED_PROTO, "https")
                    .build(),
                HttpData.ofUtf8(form))
            .aggregate()
            .join();

    assertThat(setCookieHeader(response, AuthDecorator.UC_TOKEN_KEY)).contains("Secure");
  }

  @Test
  public void wrongPasswordIsRejectedWithoutASession() {
    AggregatedHttpResponse response = login("admin", "not the password");

    assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.headers().getAll(HttpHeaderNames.SET_COOKIE))
        .noneMatch(c -> c.startsWith(AuthDecorator.UC_TOKEN_KEY + "="));
  }

  /** Only the built-in administrator signs in this way; other accounts use their provider. */
  @Test
  public void otherUsernamesAreRejectedEvenWithTheRightPassword() {
    AggregatedHttpResponse response = login("alice@example.com", PASSWORD);

    assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  public void providersAdvertiseAdminLoginOnly() throws IOException {
    AggregatedHttpResponse response =
        client
            .execute(RequestHeaders.of(HttpMethod.GET, AUTH_PATH + "/providers"))
            .aggregate()
            .join();

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    JsonNode body = MAPPER.readTree(response.contentUtf8());
    assertThat(body.get("authorization_enabled").asBoolean()).isTrue();
    assertThat(body.get("admin_login").asBoolean()).isTrue();
    assertThat(body.get("hosted_login").asBoolean()).isFalse();
  }

  private AggregatedHttpResponse login(String username, String password) {
    String form =
        "username="
            + URLEncoder.encode(username, StandardCharsets.UTF_8)
            + "&password="
            + URLEncoder.encode(password, StandardCharsets.UTF_8);
    RequestHeaders headers =
        RequestHeaders.builder()
            .method(HttpMethod.POST)
            .path(AUTH_PATH + "/admin/login")
            .contentType(MediaType.FORM_DATA)
            .build();
    return client.execute(headers, HttpData.ofUtf8(form)).aggregate().join();
  }

  private static String setCookieHeader(AggregatedHttpResponse response, String name) {
    List<String> cookies = response.headers().getAll(HttpHeaderNames.SET_COOKIE);
    return cookies.stream()
        .filter(c -> c.startsWith(name + "="))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no Set-Cookie for " + name + " in " + cookies));
  }

  private static String setCookieValue(AggregatedHttpResponse response, String name) {
    String header = setCookieHeader(response, name);
    String afterName = header.substring(name.length() + 1);
    int semicolon = afterName.indexOf(';');
    return semicolon < 0 ? afterName : afterName.substring(0, semicolon);
  }
}
