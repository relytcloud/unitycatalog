package io.unitycatalog.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import io.unitycatalog.server.base.auth.BaseAuthCRUDTest;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.model.CreateUser;
import io.unitycatalog.server.utils.ServerProperties;
import java.io.IOException;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How the token exchange maps an identity provider's claims to a local user (issue #15). Entra ID
 * omits {@code email} unless configured as an optional claim, so the sign-in name claims must also
 * identify the account. Whatever claim matched, the exchanged token must carry the LOCAL user's
 * email as {@code sub}, because that is the only key the request path looks users up by.
 */
public class AuthServiceIdentityResolutionTest extends BaseAuthCRUDTest {

  private static final String TOKEN_ENDPOINT = "/api/1.0/unity-control/auth/tokens";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String USER_EMAIL = "alice@example.com";

  private WebClient client;

  @BeforeEach
  @Override
  public void setUp() {
    super.setUp();
    client = WebClient.builder(serverConfig.getServerUrl()).build();

    Repositories repositories =
        new Repositories(
            hibernateConfigurator.getSessionFactory(), new ServerProperties(serverProperties));
    repositories
        .getUserRepository()
        .createUser(CreateUser.builder().name("Alice").email(USER_EMAIL).build());
  }

  @Test
  public void emailClaimResolvesTheUser() throws IOException {
    AggregatedHttpResponse response =
        exchange(token().withClaim("email", USER_EMAIL).withSubject(UUID.randomUUID().toString()));

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(exchangedSubject(response)).isEqualTo(USER_EMAIL);
  }

  /** Entra ID without the optional email claim: the sign-in name still identifies the user. */
  @Test
  public void preferredUsernameResolvesTheUserWhenEmailIsAbsent() throws IOException {
    AggregatedHttpResponse response =
        exchange(
            token()
                .withClaim("preferred_username", USER_EMAIL)
                .withSubject(UUID.randomUUID().toString()));

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(exchangedSubject(response)).isEqualTo(USER_EMAIL);
  }

  @Test
  public void upnResolvesTheUserWhenEmailIsAbsent() throws IOException {
    AggregatedHttpResponse response =
        exchange(token().withClaim("upn", USER_EMAIL).withSubject(UUID.randomUUID().toString()));

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(exchangedSubject(response)).isEqualTo(USER_EMAIL);
  }

  /** Relyt instances and the bootstrap admin carry the principal in sub alone. */
  @Test
  public void subAsEmailStillWorks() throws IOException {
    AggregatedHttpResponse response = exchange(token().withSubject("admin"));

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(exchangedSubject(response)).isEqualTo("admin");
  }

  /** No just-in-time provisioning: an identity with no matching user is rejected. */
  @Test
  public void unknownIdentityIsRejected() {
    AggregatedHttpResponse response =
        exchange(
            token()
                .withClaim("email", "nobody@example.com")
                .withSubject(UUID.randomUUID().toString()));

    assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.contentUtf8()).contains("User not allowed: nobody@example.com");
  }

  private JWTCreator.Builder token() {
    return JWT.create()
        .withIssuer(testIssuer)
        .withAudience(TEST_AUDIENCE)
        .withIssuedAt(new Date())
        .withKeyId(testIssuerKeyId)
        .withJWTId(UUID.randomUUID().toString());
  }

  private AggregatedHttpResponse exchange(JWTCreator.Builder token) {
    String form =
        "grant_type=urn:ietf:params:oauth:grant-type:token-exchange"
            + "&requested_token_type=urn:ietf:params:oauth:token-type:access_token"
            + "&subject_token_type=urn:ietf:params:oauth:token-type:id_token"
            + "&subject_token="
            + token.sign(testIssuerAlgorithm);
    RequestHeaders headers =
        RequestHeaders.builder()
            .method(HttpMethod.POST)
            .path(TOKEN_ENDPOINT)
            .contentType(MediaType.FORM_DATA)
            .build();
    return client.execute(headers, HttpData.ofUtf8(form)).aggregate().join();
  }

  /** The {@code sub} of the UC access token the exchange handed back. */
  private static String exchangedSubject(AggregatedHttpResponse response) throws IOException {
    JsonNode body = MAPPER.readTree(response.contentUtf8());
    return JWT.decode(body.get("access_token").asText()).getSubject();
  }
}
