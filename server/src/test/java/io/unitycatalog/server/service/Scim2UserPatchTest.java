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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Changing a user's {@code active} state over SCIM PATCH.
 *
 * <p>RFC 7644 §3.5.2.1 allows a {@code replace} to be written two ways, and both reach this server
 * in practice: without a path, carrying the attributes as an object, and with {@code active} named
 * in the path, carrying the bare value. The UI and Okta send the first; other identity providers
 * send the second. Neither had a test, and the UI shipped a third shape — pathless with a scalar —
 * which the SCIM library refuses while parsing, so the user saw a JSON parse failure instead of a
 * deactivated account.
 */
public class Scim2UserPatchTest extends BaseAuthCRUDTest {

  private static final String CONTROL_PATH = "/api/1.0/unity-control";
  private static final String PASSWORD = "correct horse battery staple";
  private static final String TARGET_EMAIL = "patch-target@example.com";
  private static final String OUTSIDER_EMAIL = "outsider@example.com";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String PATHLESS_DEACTIVATE =
      "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
          + "\"Operations\":[{\"op\":\"replace\",\"value\":{\"active\":false}}]}";
  private static final String PATHLESS_REACTIVATE =
      "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
          + "\"Operations\":[{\"op\":\"replace\",\"value\":{\"active\":true}}]}";

  private WebClient client;
  private String adminToken;
  private String userId;

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
    adminToken = signInAsAdmin();
    userId = createUser(TARGET_EMAIL);
  }

  /** The form the UI and Okta send. */
  @Test
  public void pathlessReplaceWithObjectValueDeactivatesTheUser() throws IOException {
    assertThat(patch(PATHLESS_DEACTIVATE).status()).isEqualTo(HttpStatus.OK);
    assertThat(isActive()).isFalse();
  }

  /** And it is reversible, which is the whole point of deactivating rather than purging. */
  @Test
  public void theSameFormReactivates() throws IOException {
    patch(PATHLESS_DEACTIVATE);
    assertThat(isActive()).isFalse();

    assertThat(patch(PATHLESS_REACTIVATE).status()).isEqualTo(HttpStatus.OK);
    assertThat(isActive()).isTrue();
  }

  /** The other form RFC 7644 allows: the attribute named in the path, the value bare. */
  @Test
  public void replaceWithActiveInThePathDeactivatesTheUser() throws IOException {
    AggregatedHttpResponse response =
        patch(
            "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                + "\"Operations\":[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]}");

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(isActive()).isFalse();
  }

  /** SCIM attribute names are case-insensitive, in the path and in a pathless object alike. */
  @Test
  public void theAttributeNameIsMatchedWithoutRegardToCase() throws IOException {
    assertThat(
            patch(
                    "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                        + "\"Operations\":[{\"op\":\"replace\",\"path\":\"Active\","
                        + "\"value\":false}]}")
                .status())
        .isEqualTo(HttpStatus.OK);
    assertThat(isActive()).isFalse();

    assertThat(
            patch(
                    "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                        + "\"Operations\":[{\"op\":\"replace\","
                        + "\"value\":{\"ACTIVE\":true}}]}")
                .status())
        .isEqualTo(HttpStatus.OK);
    assertThat(isActive()).isTrue();
  }

  /**
   * The regression that reached a user: a pathless replace carrying a scalar. The status is only
   * asserted as "not success" on purpose — the refusal currently surfaces as a 500 from the
   * library's deserialiser, and turning that into a 400 would be an improvement this test should
   * not stand in the way of. What must not change is that the account is left alone.
   */
  @Test
  public void pathlessReplaceWithScalarValueIsRefusedAndChangesNothing() throws IOException {
    AggregatedHttpResponse response =
        patch(
            "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                + "\"Operations\":[{\"op\":\"replace\",\"value\":false}]}");

    assertThat(response.status().isSuccess()).isFalse();
    assertThat(isActive()).isTrue();
  }

  /** An attribute this endpoint does not manage is declined rather than silently ignored. */
  @Test
  public void replaceOfSomeOtherAttributeIsNotImplemented() throws IOException {
    AggregatedHttpResponse response =
        patch(
            "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                + "\"Operations\":[{\"op\":\"replace\",\"path\":\"displayName\","
                + "\"value\":\"Renamed\"}]}");

    assertThat(response.status()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    assertThat(isActive()).isTrue();
  }

  /**
   * The endpoint carried no authorization annotation until this was fixed, and UnityAccessDecorator
   * lets a method without one through — so any signed-in account could deactivate any other, an
   * administrator included.
   */
  @Test
  public void anOrdinaryUserCannotDeactivateAnyone() throws IOException {
    createUser(OUTSIDER_EMAIL);
    String outsiderToken = securityContext.createAccessToken(OUTSIDER_EMAIL, null);

    AggregatedHttpResponse response =
        send(
            HttpMethod.PATCH,
            CONTROL_PATH + "/scim2/Users/" + userId,
            PATHLESS_DEACTIVATE,
            outsiderToken);

    assertThat(response.status().isSuccess()).isFalse();
    assertThat(isActive()).isTrue();
  }

  // ---------------------------------------------------------------- helpers

  private String signInAsAdmin() {
    String form = "username=admin&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8);
    AggregatedHttpResponse response =
        client
            .execute(
                RequestHeaders.builder()
                    .method(HttpMethod.POST)
                    .path(CONTROL_PATH + "/auth/admin/login")
                    .contentType(MediaType.FORM_DATA)
                    .build(),
                HttpData.ofUtf8(form))
            .aggregate()
            .join();
    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    try {
      return MAPPER.readTree(response.contentUtf8()).get("access_token").asText();
    } catch (IOException e) {
      throw new RuntimeException("admin sign-in did not return an access token", e);
    }
  }

  private String createUser(String email) {
    AggregatedHttpResponse response =
        send(
            HttpMethod.POST,
            CONTROL_PATH + "/scim2/Users",
            "{\"displayName\":\""
                + email
                + "\",\"emails\":[{\"primary\":true,\"value\":\""
                + email
                + "\"}]}",
            adminToken);
    assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
    try {
      return MAPPER.readTree(response.contentUtf8()).get("id").asText();
    } catch (IOException e) {
      throw new RuntimeException("user creation did not return an id", e);
    }
  }

  private AggregatedHttpResponse patch(String body) {
    return send(HttpMethod.PATCH, CONTROL_PATH + "/scim2/Users/" + userId, body, adminToken);
  }

  private boolean isActive() throws IOException {
    AggregatedHttpResponse response =
        client
            .execute(
                RequestHeaders.builder(HttpMethod.GET, CONTROL_PATH + "/scim2/Users/" + userId)
                    .add(HttpHeaderNames.AUTHORIZATION, "Bearer " + adminToken)
                    .build())
            .aggregate()
            .join();
    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    JsonNode active = MAPPER.readTree(response.contentUtf8()).get("active");
    // SCIM may omit `active` when it is true; absent means not deactivated.
    return active == null || active.asBoolean();
  }

  private AggregatedHttpResponse send(
      HttpMethod method, String path, String body, String bearerToken) {
    return client
        .execute(
            RequestHeaders.builder()
                .method(method)
                .path(path)
                .contentType(MediaType.JSON)
                .add(HttpHeaderNames.AUTHORIZATION, "Bearer " + bearerToken)
                .build(),
            HttpData.ofUtf8(body))
        .aggregate()
        .join();
  }
}
