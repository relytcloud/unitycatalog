package io.unitycatalog.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.auth0.jwt.JWT;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import io.unitycatalog.server.base.auth.BaseAuthCRUDTest;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The server-hosted login flow (issue #15): /login sends the browser to the provider, /callback
 * redeems the code with the server-held client secret and turns the id_token into the UC session
 * cookie. The base class's mock IdP plays both the discovery/JWKS host and the token endpoint.
 */
public class AuthServiceHostedLoginTest extends BaseAuthCRUDTest {

  private static final String AUTH_PATH = "/api/1.0/unity-control/auth";
  private static final String CLIENT_ID = "uc-ui-client";
  private static final String CLIENT_SECRET = "s3cret-kept-on-the-server";

  private WebClient client;

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty("server.authorization-url", testIssuer + "/authorize");
    serverProperties.setProperty("server.token-url", testIssuer + "/token");
    serverProperties.setProperty("server.client-id", CLIENT_ID);
    serverProperties.setProperty("server.client-secret", CLIENT_SECRET);
    // Entra addresses id_tokens to the application's client id; audiences must list it.
    serverProperties.setProperty("server.audiences", TEST_AUDIENCE + "," + CLIENT_ID);
  }

  @BeforeEach
  @Override
  public void setUp() {
    super.setUp();
    tokenEndpointAudience = CLIENT_ID;
    tokenEndpointSubject = "admin";
    // No automatic redirect following: the 302s ARE the behaviour under test.
    client =
        WebClient.builder(serverConfig.getServerUrl()).factory(ClientFactory.ofDefault()).build();
  }

  @Test
  public void loginRedirectsToTheProviderWithStateAndServerCallback() {
    AggregatedHttpResponse response = get(AUTH_PATH + "/login?redirect=/data/demo", null);

    assertThat(response.status()).isEqualTo(HttpStatus.FOUND);
    String location = response.headers().get(HttpHeaderNames.LOCATION);
    assertThat(location).startsWith(testIssuer + "/authorize?");

    Map<String, String> query = queryOf(location);
    assertThat(query.get("client_id")).isEqualTo(CLIENT_ID);
    assertThat(query.get("response_type")).isEqualTo("code");
    assertThat(query.get("scope")).isEqualTo("openid profile email");
    assertThat(query.get("state")).isNotBlank();
    // Derived from the request's Host: the callback lives on THIS server.
    assertThat(query.get("redirect_uri"))
        .isEqualTo(serverConfig.getServerUrl() + AUTH_PATH + "/callback");

    // The state is bound to a cookie scoped to the auth mount path, so /callback receives it.
    String stateCookie = setCookieValue(response, "UC_OAUTH_STATE");
    assertThat(stateCookie).startsWith(query.get("state") + "|/data/demo");
    assertThat(setCookieHeader(response, "UC_OAUTH_STATE")).contains("Path=" + AUTH_PATH);
    // Lax, not Strict: the browser returns from the identity provider on a cross-site
    // navigation, and a Strict cookie is withheld on exactly that request -- /callback would
    // then find no state and refuse a sign-in that had in fact succeeded.
    assertThat(setCookieHeader(response, "UC_OAUTH_STATE")).contains("SameSite=Lax");
    // The client secret must never appear in anything sent to the browser.
    assertThat(location).doesNotContain(CLIENT_SECRET);
  }

  /**
   * An expired client secret fails here and nowhere else, after the person has already signed in at
   * the provider. Naming the provider's code is what tells it apart from a redirect URI that no
   * longer matches or a replayed code, which fail at the same place with the same status.
   */
  @Test
  public void callbackNamesWhyTheProviderRefusedTheCode() {
    tokenEndpointStatus = 401;
    tokenEndpointErrorBody =
        "{\"error\":\"invalid_client\",\"error_description\":\"AADSTS7000222: The provided client"
            + " secret keys for app '"
            + CLIENT_ID
            + "' are expired.\\r\\nTrace ID: 00000000-0000-0000-0000-000000000000\"}";
    try {
      AggregatedHttpResponse login = get(AUTH_PATH + "/login?redirect=/", null);
      String state = queryOf(login.headers().get(HttpHeaderNames.LOCATION)).get("state");
      String stateCookie = setCookieValue(login, "UC_OAUTH_STATE");

      AggregatedHttpResponse callback =
          get(
              AUTH_PATH + "/callback?code=the-code&state=" + state,
              "UC_OAUTH_STATE=" + stateCookie);

      assertThat(callback.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
      assertThat(callback.contentUtf8()).contains("invalid_client").contains("AADSTS7000222");
      // The description itself, with its correlation id, belongs in the log rather than the
      // browser.
      assertThat(callback.contentUtf8()).doesNotContain("Trace ID");
      assertThat(callback.headers().getAll(HttpHeaderNames.SET_COOKIE))
          .noneMatch(c -> c.startsWith("UC_TOKEN="));
    } finally {
      tokenEndpointErrorBody = null;
      tokenEndpointStatus = 200;
    }
  }

  @Test
  public void callbackRedeemsTheCodeAndSetsTheSessionCookie() {
    AggregatedHttpResponse login = get(AUTH_PATH + "/login?redirect=/data/demo", null);
    String state = queryOf(login.headers().get(HttpHeaderNames.LOCATION)).get("state");
    String stateCookie = setCookieValue(login, "UC_OAUTH_STATE");

    AggregatedHttpResponse callback =
        get(AUTH_PATH + "/callback?code=the-code&state=" + state, "UC_OAUTH_STATE=" + stateCookie);

    assertThat(callback.status()).isEqualTo(HttpStatus.FOUND);
    assertThat(callback.headers().get(HttpHeaderNames.LOCATION)).isEqualTo("/data/demo");

    // The code was redeemed server-side with the client secret, against the registered callback.
    assertThat(lastTokenRequestBody).contains("grant_type=authorization_code");
    assertThat(lastTokenRequestBody).contains("code=the-code");
    assertThat(lastTokenRequestBody).contains("client_id=" + CLIENT_ID);
    assertThat(lastTokenRequestBody).contains("client_secret=" + CLIENT_SECRET);
    assertThat(URLDecoder.decode(lastTokenRequestBody, StandardCharsets.UTF_8))
        .contains("redirect_uri=" + serverConfig.getServerUrl() + AUTH_PATH + "/callback");

    // The session cookie is a UC access token for the resolved local user ...
    String session = setCookieValue(callback, "UC_TOKEN");
    assertThat(JWT.decode(session).getSubject()).isEqualTo("admin");
    // ... and the one-time state cookie is cleared.
    assertThat(setCookieHeader(callback, "UC_OAUTH_STATE")).contains("Max-Age=0");
  }

  @Test
  public void callbackRejectsAMismatchedState() {
    AggregatedHttpResponse login = get(AUTH_PATH + "/login", null);
    String stateCookie = setCookieValue(login, "UC_OAUTH_STATE");

    AggregatedHttpResponse callback =
        get(
            AUTH_PATH + "/callback?code=the-code&state=someone-elses-state",
            "UC_OAUTH_STATE=" + stateCookie);

    assertThat(callback.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(callback.headers().getAll(HttpHeaderNames.SET_COOKIE))
        .noneMatch(c -> c.startsWith("UC_TOKEN="));
  }

  @Test
  public void callbackWithoutTheStateCookieIsRejected() {
    AggregatedHttpResponse callback = get(AUTH_PATH + "/callback?code=x&state=y", null);

    assertThat(callback.status()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  /** The id_token names a user UC does not know: same rule as the token exchange. */
  @Test
  public void callbackRejectsAnUnknownUser() {
    tokenEndpointSubject = "nobody@example.com";
    AggregatedHttpResponse login = get(AUTH_PATH + "/login", null);
    String state = queryOf(login.headers().get(HttpHeaderNames.LOCATION)).get("state");
    String stateCookie = setCookieValue(login, "UC_OAUTH_STATE");

    AggregatedHttpResponse callback =
        get(AUTH_PATH + "/callback?code=c&state=" + state, "UC_OAUTH_STATE=" + stateCookie);

    assertThat(callback.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(callback.contentUtf8()).contains("User not allowed: nobody@example.com");
  }

  @Test
  public void callbackSurfacesAProviderError() {
    AggregatedHttpResponse callback =
        get(AUTH_PATH + "/callback?error=access_denied&error_description=MFA+required", null);

    assertThat(callback.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(callback.contentUtf8()).contains("access_denied");
  }

  /** An absolute redirect would let a crafted login link send the user off-site. */
  @Test
  public void absoluteRedirectFallsBackToRoot() {
    AggregatedHttpResponse login = get(AUTH_PATH + "/login?redirect=https://evil.example/", null);
    String state = queryOf(login.headers().get(HttpHeaderNames.LOCATION)).get("state");
    String stateCookie = setCookieValue(login, "UC_OAUTH_STATE");

    AggregatedHttpResponse callback =
        get(AUTH_PATH + "/callback?code=c&state=" + state, "UC_OAUTH_STATE=" + stateCookie);

    assertThat(callback.status()).isEqualTo(HttpStatus.FOUND);
    assertThat(callback.headers().get(HttpHeaderNames.LOCATION)).isEqualTo("/");
  }

  @Test
  public void schemeRelativeRedirectFallsBackToRoot() {
    AggregatedHttpResponse login = get(AUTH_PATH + "/login?redirect=//evil.example/x", null);
    String state = queryOf(login.headers().get(HttpHeaderNames.LOCATION)).get("state");
    String stateCookie = setCookieValue(login, "UC_OAUTH_STATE");

    AggregatedHttpResponse callback =
        get(AUTH_PATH + "/callback?code=c&state=" + state, "UC_OAUTH_STATE=" + stateCookie);

    assertThat(callback.headers().get(HttpHeaderNames.LOCATION)).isEqualTo("/");
  }

  /** Behind the UI's reverse proxy the browser-facing origin comes from X-Forwarded-*. */
  @Test
  public void callbackUrlHonoursForwardedHeaders() {
    RequestHeaders headers =
        RequestHeaders.builder(HttpMethod.GET, AUTH_PATH + "/login")
            .add(HttpHeaderNames.X_FORWARDED_PROTO, "https")
            .add(HttpHeaderNames.X_FORWARDED_HOST, "uc.example.com")
            .build();
    AggregatedHttpResponse response = client.execute(headers).aggregate().join();

    String redirectUri =
        queryOf(response.headers().get(HttpHeaderNames.LOCATION)).get("redirect_uri");
    assertThat(redirectUri).isEqualTo("https://uc.example.com" + AUTH_PATH + "/callback");
  }

  /** The UI shows the Microsoft button only because the server says the flow is configured. */
  @Test
  public void providersAdvertiseHostedLoginOnly() throws Exception {
    AggregatedHttpResponse response = get(AUTH_PATH + "/providers", null);

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    com.fasterxml.jackson.databind.JsonNode body =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.contentUtf8());
    assertThat(body.get("authorization_enabled").asBoolean()).isTrue();
    assertThat(body.get("hosted_login").asBoolean()).isTrue();
    assertThat(body.get("admin_login").asBoolean()).isFalse();
  }

  private AggregatedHttpResponse get(String path, String cookieHeader) {
    var builder = RequestHeaders.builder(HttpMethod.GET, path);
    if (cookieHeader != null) {
      builder.add(HttpHeaderNames.COOKIE, cookieHeader);
    }
    return client.execute(builder.build()).aggregate().join();
  }

  private static Map<String, String> queryOf(String url) {
    Map<String, String> query = new HashMap<>();
    String raw = URI.create(url).getRawQuery();
    if (raw == null) {
      return query;
    }
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
      String value =
          eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      query.put(key, value);
    }
    return query;
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
