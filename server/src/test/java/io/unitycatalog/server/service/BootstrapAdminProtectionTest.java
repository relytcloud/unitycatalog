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
import io.unitycatalog.server.persist.dao.UserDAO;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What may not be done to the bootstrap administrator.
 *
 * <p>The {@code admin} account is the one the password sign-in accepts, which is the way back in
 * when the identity provider is unavailable. Upstream treats it as a throwaway bootstrap account
 * and says so in UnityAccessUtil#initializeAdmin — that note predates the password sign-in this
 * fork added, which gave the same account a second job.
 *
 * <p>Purging it was already refused. Deactivating it was not, by any of the three routes that can,
 * and neither was taking its administrator privilege away — which no restart repairs. A second
 * administrator could therefore close the door behind everyone, which is what prompted these tests.
 */
public class BootstrapAdminProtectionTest extends BaseAuthCRUDTest {

  private static final String CONTROL_PATH = "/api/1.0/unity-control";
  private static final String PASSWORD = "correct horse battery staple";
  private static final String ADMIN = "admin";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private WebClient client;
  private String adminToken;
  private String adminUserId;

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
    adminToken = signIn();
    adminUserId = idOf(ADMIN);
  }

  /** The route the UI takes. */
  @Test
  public void patchCannotDeactivateTheBootstrapAdministrator() throws IOException {
    AggregatedHttpResponse response =
        send(
            HttpMethod.PATCH,
            CONTROL_PATH + "/scim2/Users/" + adminUserId,
            "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                + "\"Operations\":[{\"op\":\"replace\",\"value\":{\"active\":false}}]}");

    assertThat(response.status().isSuccess()).isFalse();
    assertThat(isActive(adminUserId)).isTrue();
  }

  /** DELETE without purge means deactivate, so it needs the same guard. */
  @Test
  public void deleteCannotDeactivateTheBootstrapAdministrator() throws IOException {
    AggregatedHttpResponse response =
        send(HttpMethod.DELETE, CONTROL_PATH + "/scim2/Users/" + adminUserId, "");

    assertThat(response.status().isSuccess()).isFalse();
    assertThat(isActive(adminUserId)).isTrue();
  }

  /** A PUT writes `active` back like any other field; it is the third route in. */
  @Test
  public void putCannotDeactivateTheBootstrapAdministrator() throws IOException {
    AggregatedHttpResponse response =
        send(
            HttpMethod.PUT,
            CONTROL_PATH + "/scim2/Users/" + adminUserId,
            "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"],"
                + "\"id\":\""
                + adminUserId
                + "\",\"displayName\":\"Admin\",\"active\":false,"
                + "\"emails\":[{\"primary\":true,\"value\":\""
                + ADMIN
                + "\"}]}");

    assertThat(response.status().isSuccess()).isFalse();
    assertThat(isActive(adminUserId)).isTrue();
  }

  /** Purging was already refused; this keeps it that way. */
  @Test
  public void purgeCannotRemoveTheBootstrapAdministrator() throws IOException {
    AggregatedHttpResponse response =
        send(
            HttpMethod.DELETE,
            CONTROL_PATH + "/scim2/Users/" + adminUserId + "?purge=true&confirm_principal=" + ADMIN,
            "");

    assertThat(response.status().isSuccess()).isFalse();
    assertThat(isActive(adminUserId)).isTrue();
  }

  /**
   * Taking the privilege away leaves an account that signs in and administers nothing, and
   * UnityAccessUtil#initializeAdmin only re-grants OWNER when the account is missing entirely — so
   * a surviving row that has lost it never gets it back.
   */
  @Test
  public void theBootstrapAdministratorCannotLoseAdministratorStatus() {
    AggregatedHttpResponse response =
        send(HttpMethod.DELETE, CONTROL_PATH + "/metastore/admins/" + ADMIN, "");

    assertThat(response.status().isSuccess()).isFalse();
    assertThat(admins()).contains(ADMIN);
  }

  /**
   * Defence in depth for the guards above: if the account is deactivated anyway — by an older
   * build, or by something reaching the database directly — the password sign-in must say so.
   *
   * <p>It used to hand out a session regardless, because it checks the password against
   * configuration and never consults the user table. Every request made with that session was then
   * refused by AuthDecorator, so the sign-in looked like it worked and nothing worked afterwards.
   */
  @Test
  public void signingInIsRefusedWhenTheAdminAccountIsDeactivated() {
    deactivateAdminBehindTheApi();

    AggregatedHttpResponse response = attemptSignIn();

    assertThat(response.status().isSuccess()).isFalse();
    assertThat(response.contentUtf8()).contains("deactivated");
  }

  // ---------------------------------------------------------------- helpers

  /** Reaches past the service guards, the way an older build or a DBA could. */
  private void deactivateAdminBehindTheApi() {
    try (Session session = hibernateConfigurator.getSessionFactory().openSession()) {
      session.beginTransaction();
      UserDAO dao =
          session
              .createQuery("FROM UserDAO WHERE email = :email", UserDAO.class)
              .setParameter("email", ADMIN)
              .uniqueResult();
      dao.setState("DISABLED");
      session.merge(dao);
      session.getTransaction().commit();
    }
  }

  private AggregatedHttpResponse attemptSignIn() {
    String form = "username=admin&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8);
    return client
        .execute(
            RequestHeaders.builder()
                .method(HttpMethod.POST)
                .path(CONTROL_PATH + "/auth/admin/login")
                .contentType(MediaType.FORM_DATA)
                .build(),
            HttpData.ofUtf8(form))
        .aggregate()
        .join();
  }

  private String signIn() {
    AggregatedHttpResponse response = attemptSignIn();
    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    try {
      return MAPPER.readTree(response.contentUtf8()).get("access_token").asText();
    } catch (IOException e) {
      throw new RuntimeException("admin sign-in did not return an access token", e);
    }
  }

  private String idOf(String principal) {
    AggregatedHttpResponse response = send(HttpMethod.GET, CONTROL_PATH + "/scim2/Users", "");
    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    try {
      for (JsonNode user : MAPPER.readTree(response.contentUtf8()).get("Resources")) {
        if (principal.equals(user.path("userName").asText())) {
          return user.get("id").asText();
        }
      }
    } catch (IOException e) {
      throw new RuntimeException("could not list users", e);
    }
    throw new IllegalStateException("no user named " + principal);
  }

  private boolean isActive(String userId) throws IOException {
    AggregatedHttpResponse response =
        send(HttpMethod.GET, CONTROL_PATH + "/scim2/Users/" + userId, "");
    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    JsonNode active = MAPPER.readTree(response.contentUtf8()).get("active");
    return active == null || active.asBoolean();
  }

  private java.util.List<String> admins() {
    AggregatedHttpResponse response = send(HttpMethod.GET, CONTROL_PATH + "/metastore/admins", "");
    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    try {
      java.util.List<String> names = new java.util.ArrayList<>();
      MAPPER.readTree(response.contentUtf8()).get("admins").forEach(n -> names.add(n.asText()));
      return names;
    } catch (IOException e) {
      throw new RuntimeException("could not list administrators", e);
    }
  }

  private AggregatedHttpResponse send(HttpMethod method, String path, String body) {
    RequestHeaders headers =
        RequestHeaders.builder()
            .method(method)
            .path(path)
            .contentType(MediaType.JSON)
            .add(HttpHeaderNames.AUTHORIZATION, "Bearer " + adminToken)
            .build();
    return client.execute(headers, HttpData.ofUtf8(body)).aggregate().join();
  }
}
