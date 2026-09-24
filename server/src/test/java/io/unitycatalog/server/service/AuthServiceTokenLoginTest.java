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
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Signing in with an access token this server issued (issue #15). It is how whoever runs the server
 * reaches the UI with the token from etc/conf/token.txt, and it replaced the UI proxy's blanket
 * token injection -- so what matters here is that it accepts only this server's own valid tokens,
 * and that the session it opens is the token itself rather than a fresh one.
 */
public class AuthServiceTokenLoginTest extends BaseAuthCRUDTest {

  private static final String AUTH_PATH = "/api/1.0/unity-control/auth";
  private static final String PASSWORD = "correct horse battery staple";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private WebClient client;

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    // Only to obtain a genuine token to present; the sign-in under test needs no password.
    serverProperties.setProperty("server.admin-password", PASSWORD);
  }

  @BeforeEach
  @Override
  public void setUp() {
    super.setUp();
    client = WebClient.builder(serverConfig.getServerUrl()).build();
  }

  @Test
  public void tokenIssuedByThisServerOpensASession() throws IOException {
    String token = anAdminToken();

    AggregatedHttpResponse response = signIn(token);

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    // The session is the presented token, not a new one: it cannot outlive what was presented.
    assertThat(MAPPER.readTree(response.contentUtf8()).get("access_token").asText())
        .isEqualTo(token);
    assertThat(setCookieValue(response, AuthDecorator.UC_TOKEN_KEY)).isEqualTo(token);

    AggregatedHttpResponse me =
        client
            .execute(
                RequestHeaders.builder(HttpMethod.GET, "/api/1.0/unity-control/scim2/Me")
                    .add(HttpHeaderNames.COOKIE, AuthDecorator.UC_TOKEN_KEY + "=" + token)
                    .build())
            .aggregate()
            .join();
    assertThat(me.status()).isEqualTo(HttpStatus.OK);
    assertThat(MAPPER.readTree(me.contentUtf8()).get("userName").asText()).isEqualTo("admin");
  }

  /** A token whose payload was edited no longer matches its signature. */
  @Test
  public void anAlteredTokenIsRejected() throws IOException {
    String token = anAdminToken();
    String[] parts = token.split("\\.");
    String tampered = parts[0] + "." + parts[1] + "." + alterInTheMiddle(parts[2]);

    AggregatedHttpResponse response = signIn(tampered);

    assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.headers().getAll(HttpHeaderNames.SET_COOKIE))
        .noneMatch(c -> c.startsWith(AuthDecorator.UC_TOKEN_KEY + "="));
  }

  /**
   * An identity provider's token is not a session here even when the provider is trusted: it goes
   * to /auth/tokens, which checks issuer and audience and exchanges it.
   */
  @Test
  public void tokenFromAnotherIssuerIsRejected() {
    String foreign =
        JWT.create()
            .withIssuer(testIssuer)
            .withSubject("admin")
            .withAudience(TEST_AUDIENCE)
            .withKeyId(testIssuerKeyId)
            .withExpiresAt(new Date(System.currentTimeMillis() + 60_000))
            .sign(testIssuerAlgorithm);

    AggregatedHttpResponse response = signIn(foreign);

    assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  public void gibberishIsRejected() {
    AggregatedHttpResponse response = signIn("not-a-token");

    assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  public void anEmptyTokenIsRefused() {
    AggregatedHttpResponse response = signIn("");

    assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  /**
   * The session belongs to whoever the token names, not to whoever pasted it.
   *
   * <p>Worth stating outright because this address is reached by pasting a credential: if the
   * identity ever collapsed to the administrator, anyone holding any valid token would arrive as
   * one. The reason the operator's own sign-in shows "admin" is simply that etc/conf/token.txt
   * names admin — not that this endpoint confers it.
   */
  @Test
  public void theSessionIsWhoeverTheTokenNames() throws IOException {
    createUser("someone@example.com");
    String theirToken = securityContext.createAccessToken("someone@example.com", null);

    AggregatedHttpResponse response = signIn(theirToken);

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    AggregatedHttpResponse me =
        client
            .execute(
                RequestHeaders.builder(HttpMethod.GET, "/api/1.0/unity-control/scim2/Me")
                    .add(HttpHeaderNames.COOKIE, AuthDecorator.UC_TOKEN_KEY + "=" + theirToken)
                    .build())
            .aggregate()
            .join();
    assertThat(me.status()).isEqualTo(HttpStatus.OK);
    assertThat(MAPPER.readTree(me.contentUtf8()).get("userName").asText())
        .isEqualTo("someone@example.com");
  }

  /** A token naming somebody the metastore does not know opens nothing. */
  @Test
  public void tokenNamingNobodyIsRejected() {
    String orphan = securityContext.createAccessToken("ghost@example.com", null);

    AggregatedHttpResponse response = signIn(orphan);

    assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  private void createUser(String email) throws IOException {
    AggregatedHttpResponse response =
        client
            .execute(
                RequestHeaders.builder()
                    .method(HttpMethod.POST)
                    .path("/api/1.0/unity-control/scim2/Users")
                    .contentType(MediaType.JSON)
                    .add(HttpHeaderNames.AUTHORIZATION, "Bearer " + anAdminToken())
                    .build(),
                HttpData.ofUtf8(
                    "{\"displayName\":\""
                        + email
                        + "\",\"emails\":[{\"primary\":true,\"value\":\""
                        + email
                        + "\"}]}"))
            .aggregate()
            .join();
    assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
  }

  /** The administrator password sign-in is only a convenient source of a real token here. */
  private String anAdminToken() throws IOException {
    String form = "username=admin&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8);
    AggregatedHttpResponse response =
        client
            .execute(
                RequestHeaders.builder()
                    .method(HttpMethod.POST)
                    .path(AUTH_PATH + "/admin/login")
                    .contentType(MediaType.FORM_DATA)
                    .build(),
                HttpData.ofUtf8(form))
            .aggregate()
            .join();
    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    JsonNode body = MAPPER.readTree(response.contentUtf8());
    return body.get("access_token").asText();
  }

  private AggregatedHttpResponse signIn(String token) {
    RequestHeaders headers =
        RequestHeaders.builder()
            .method(HttpMethod.POST)
            .path(AUTH_PATH + "/token/login")
            .contentType(MediaType.FORM_DATA)
            .build();
    String form = "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    return client.execute(headers, HttpData.ofUtf8(form)).aggregate().join();
  }

  /**
   * Alters a character in the middle: base64url's final character carries only the leftover bits of
   * the signature, some of which are discarded, so changing it can leave the same bytes behind.
   */
  private static String alterInTheMiddle(String value) {
    int at = value.length() / 2;
    char replacement = value.charAt(at) == 'A' ? 'B' : 'A';
    return value.substring(0, at) + replacement + value.substring(at + 1);
  }

  private static String setCookieValue(AggregatedHttpResponse response, String name) {
    List<String> cookies = response.headers().getAll(HttpHeaderNames.SET_COOKIE);
    String header =
        cookies.stream()
            .filter(c -> c.startsWith(name + "="))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no Set-Cookie for " + name + " in " + cookies));
    String afterName = header.substring(name.length() + 1);
    int semicolon = afterName.indexOf(';');
    return semicolon < 0 ? afterName : afterName.substring(0, semicolon);
  }
}
