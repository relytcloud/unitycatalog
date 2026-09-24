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
import java.util.ArrayList;
import java.util.List;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Who administers the metastore, and the one rule that governs changing it.
 *
 * <p>Being an administrator is holding OWNER on the metastore, and the invariant is that at least
 * one <em>enabled</em> account always holds it. Without that a metastore can be left with nobody
 * able to administer it and no supported way back: UnityAccessUtil#initializeAdmin re-grants OWNER
 * only when the {@code admin} account is missing entirely, so a surviving row that lost the
 * privilege is never repaired by a restart.
 */
public class MetastoreAdminsTest extends BaseAuthCRUDTest {

  private static final String CONTROL_PATH = "/api/1.0/unity-control";
  private static final String ADMINS_PATH = CONTROL_PATH + "/metastore/admins";
  private static final String PASSWORD = "correct horse battery staple";
  private static final String ALICE = "alice@example.com";
  private static final String BOB = "bob@example.com";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private WebClient client;
  private String adminToken;

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
  }

  @Test
  public void administratorsCanBeAppointedAndStoodDown() throws IOException {
    createUser(ALICE);
    assertThat(admins()).containsExactly("admin");

    assertThat(grant(ALICE, adminToken).status()).isEqualTo(HttpStatus.OK);
    assertThat(admins()).containsExactlyInAnyOrder("admin", ALICE);

    assertThat(revoke(ALICE, adminToken).status()).isEqualTo(HttpStatus.OK);
    assertThat(admins()).containsExactly("admin");
  }

  /**
   * The server refuses to grant it to a deactivated account: the privilege would be held by
   * somebody who cannot sign in, which counts towards nothing and only looks like cover.
   */
  @Test
  public void appointingADeactivatedAccountIsRefused() throws IOException {
    String bobId = createUser(BOB);
    deactivate(bobId);

    AggregatedHttpResponse response = grant(BOB, adminToken);

    assertThat(response.status().isSuccess()).isFalse();
    assertThat(admins()).doesNotContain(BOB);
  }

  /**
   * The invariant itself. The bootstrap administrator is deactivated here behind the API — which
   * the service now refuses, and which is exactly why this has to be set up directly — to reach the
   * state where one ordinary administrator is all that is left.
   */
  @Test
  public void theLastEnabledAdministratorCannotStandDown() throws IOException {
    createUser(ALICE);
    assertThat(grant(ALICE, adminToken).status()).isEqualTo(HttpStatus.OK);
    String aliceToken = securityContext.createAccessToken(ALICE, null);

    deactivateBehindTheApi("admin");

    // admin still holds OWNER, but a deactivated holder does not keep the metastore administrable,
    // so Alice is the last one that counts.
    AggregatedHttpResponse response = revoke(ALICE, aliceToken);

    assertThat(response.status().isSuccess()).isFalse();
    assertThat(response.contentUtf8()).contains("administrator");
    // Asked as Alice: the admin account is deactivated, so its own token no longer opens anything.
    assertThat(admins(aliceToken)).contains(ALICE);
  }

  /**
   * Handing over is a real thing to want, so revoking your own is allowed while another remains.
   */
  @Test
  public void anAdministratorMayStandThemselvesDown() throws IOException {
    createUser(ALICE);
    assertThat(grant(ALICE, adminToken).status()).isEqualTo(HttpStatus.OK);
    String aliceToken = securityContext.createAccessToken(ALICE, null);

    assertThat(revoke(ALICE, aliceToken).status()).isEqualTo(HttpStatus.OK);
    assertThat(admins()).containsExactly("admin");
  }

  /** Appointing administrators is itself an administrator's privilege. */
  @Test
  public void anOrdinaryUserCannotAppointAdministrators() throws IOException {
    createUser(ALICE);
    createUser(BOB);
    String bobToken = securityContext.createAccessToken(BOB, null);

    assertThat(grant(ALICE, bobToken).status().isSuccess()).isFalse();
    assertThat(admins()).doesNotContain(ALICE);
  }

  /** And so is seeing the list: OWNER is filtered out of /permissions, so this is the only view. */
  @Test
  public void anOrdinaryUserCannotSeeTheAdministrators() throws IOException {
    createUser(BOB);
    String bobToken = securityContext.createAccessToken(BOB, null);

    AggregatedHttpResponse response = send(HttpMethod.GET, ADMINS_PATH, "", bobToken);

    assertThat(response.status().isSuccess()).isFalse();
  }

  // ---------------------------------------------------------------- helpers

  private AggregatedHttpResponse grant(String email, String token) {
    return send(HttpMethod.PUT, ADMINS_PATH + "/" + email, "", token);
  }

  private AggregatedHttpResponse revoke(String email, String token) {
    return send(HttpMethod.DELETE, ADMINS_PATH + "/" + email, "", token);
  }

  private List<String> admins() throws IOException {
    return admins(adminToken);
  }

  /**
   * Who is listed, as seen by a given administrator — the caller matters once admin is disabled.
   */
  private List<String> admins(String token) throws IOException {
    AggregatedHttpResponse response = send(HttpMethod.GET, ADMINS_PATH, "", token);
    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    List<String> names = new ArrayList<>();
    for (JsonNode name : MAPPER.readTree(response.contentUtf8()).get("admins")) {
      names.add(name.asText());
    }
    return names;
  }

  private void deactivate(String id) {
    AggregatedHttpResponse response =
        send(
            HttpMethod.PATCH,
            CONTROL_PATH + "/scim2/Users/" + id,
            "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                + "\"Operations\":[{\"op\":\"replace\",\"value\":{\"active\":false}}]}",
            adminToken);
    assertThat(response.status()).isEqualTo(HttpStatus.OK);
  }

  /** The service refuses to deactivate the bootstrap administrator, so this goes around it. */
  private void deactivateBehindTheApi(String email) {
    try (Session session = hibernateConfigurator.getSessionFactory().openSession()) {
      session.beginTransaction();
      UserDAO dao =
          session
              .createQuery("FROM UserDAO WHERE email = :email", UserDAO.class)
              .setParameter("email", email)
              .uniqueResult();
      dao.setState("DISABLED");
      session.merge(dao);
      session.getTransaction().commit();
    }
  }

  private String signIn() {
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
      throw new IllegalStateException("admin sign-in did not return an access token", e);
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
      throw new IllegalStateException("user creation did not return an id", e);
    }
  }

  private AggregatedHttpResponse send(
      HttpMethod method, String path, String body, String bearerToken) {
    RequestHeaders headers =
        RequestHeaders.builder()
            .method(method)
            .path(path)
            .contentType(MediaType.JSON)
            .add(HttpHeaderNames.AUTHORIZATION, "Bearer " + bearerToken)
            .build();
    return client.execute(headers, HttpData.ofUtf8(body)).aggregate().join();
  }
}
