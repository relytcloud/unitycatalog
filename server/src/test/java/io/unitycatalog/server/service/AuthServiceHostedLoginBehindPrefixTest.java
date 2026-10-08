package io.unitycatalog.server.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The hosted login behind a gateway that serves the server under a path prefix and strips it before
 * forwarding: the browser is on /unitycatalog/api/1.0/unity-control/auth/..., the server sees
 * /api/1.0/unity-control/auth/.... The state cookie must be scoped to the browser's path, taken
 * from server.external-url, or the browser never sends it to /callback.
 */
public class AuthServiceHostedLoginBehindPrefixTest extends BaseAuthCRUDTest {

  private static final String AUTH_PATH = "/api/1.0/unity-control/auth";
  private static final String EXTERNAL_URL = "https://api.example.com/unitycatalog";
  private static final String CLIENT_ID = "uc-ui-client";

  private WebClient client;

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty("server.authorization-url", testIssuer + "/authorize");
    serverProperties.setProperty("server.token-url", testIssuer + "/token");
    serverProperties.setProperty("server.client-id", CLIENT_ID);
    serverProperties.setProperty("server.client-secret", "s3cret");
    serverProperties.setProperty("server.audiences", TEST_AUDIENCE + "," + CLIENT_ID);
    // Trailing slash on purpose: it must not produce "//api" in the cookie path.
    serverProperties.setProperty("server.external-url", EXTERNAL_URL + "/");
  }

  @BeforeEach
  @Override
  public void setUp() {
    super.setUp();
    tokenEndpointAudience = CLIENT_ID;
    tokenEndpointSubject = "admin";
    client =
        WebClient.builder(serverConfig.getServerUrl()).factory(ClientFactory.ofDefault()).build();
  }

  @Test
  public void stateCookieIsScopedToThePathTheBrowserUses() {
    AggregatedHttpResponse login = get(AUTH_PATH + "/login?redirect=/", null);

    assertThat(login.status()).isEqualTo(HttpStatus.FOUND);
    String redirectUri = queryParam(login.headers().get(HttpHeaderNames.LOCATION), "redirect_uri");
    assertThat(redirectUri).isEqualTo(EXTERNAL_URL + AUTH_PATH + "/callback");
    // Scoped to the redirect URI's own directory, so the browser sends it to /callback.
    assertThat(setCookieHeader(login, "UC_OAUTH_STATE"))
        .contains("Path=/unitycatalog" + AUTH_PATH + ";");
  }

  @Test
  public void callbackClearsTheStateCookieOnTheSamePath() {
    AggregatedHttpResponse login = get(AUTH_PATH + "/login?redirect=/", null);
    String state = queryParam(login.headers().get(HttpHeaderNames.LOCATION), "state");
    String stateCookie = setCookieValue(login, "UC_OAUTH_STATE");

    // The gateway has stripped the prefix by the time the callback reaches the server.
    AggregatedHttpResponse callback =
        get(AUTH_PATH + "/callback?code=c&state=" + state, "UC_OAUTH_STATE=" + stateCookie);

    assertThat(callback.status()).isEqualTo(HttpStatus.FOUND);
    // A deletion only removes the cookie the browser holds if it names the same path.
    assertThat(setCookieHeader(callback, "UC_OAUTH_STATE"))
        .contains("Max-Age=0")
        .contains("Path=/unitycatalog" + AUTH_PATH + ";");
    // The session cookie stays host-wide, as without a prefix.
    assertThat(setCookieHeader(callback, "UC_TOKEN")).contains("Path=/;");
  }

  private AggregatedHttpResponse get(String path, String cookieHeader) {
    var builder = RequestHeaders.builder(HttpMethod.GET, path);
    if (cookieHeader != null) {
      builder.add(HttpHeaderNames.COOKIE, cookieHeader);
    }
    return client.execute(builder.build()).aggregate().join();
  }

  private static String queryParam(String url, String name) {
    return Arrays.stream(URI.create(url).getRawQuery().split("&"))
        .filter(pair -> pair.startsWith(name + "="))
        .map(pair -> URLDecoder.decode(pair.substring(name.length() + 1), StandardCharsets.UTF_8))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + name + " in " + url));
  }

  private static String setCookieHeader(AggregatedHttpResponse response, String name) {
    List<String> cookies = response.headers().getAll(HttpHeaderNames.SET_COOKIE);
    return cookies.stream()
        .filter(c -> c.startsWith(name + "="))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no Set-Cookie for " + name + " in " + cookies));
  }

  private static String setCookieValue(AggregatedHttpResponse response, String name) {
    String afterName = setCookieHeader(response, name).substring(name.length() + 1);
    int semicolon = afterName.indexOf(';');
    return semicolon < 0 ? afterName : afterName.substring(0, semicolon);
  }
}
