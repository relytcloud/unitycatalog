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
import io.unitycatalog.server.persist.dao.CatalogInfoDAO;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Permanently deleting a user.
 *
 * <p>Purging is the one irreversible thing this service does, and the only thing that frees an
 * email for reuse — deactivating leaves the row, so the address stays taken. Everything that makes
 * it safe is checked in the service rather than trusted to the caller, and none of it had a test.
 */
public class Scim2UserPurgeTest extends BaseAuthCRUDTest {

  private static final String CONTROL_PATH = "/api/1.0/unity-control";
  private static final String CATALOG_PATH = "/api/2.1/unity-catalog";
  private static final String PASSWORD = "correct horse battery staple";
  private static final String TARGET = "target@example.com";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private WebClient client;
  private String adminToken;
  private String targetId;

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
    targetId = createUser(TARGET);
  }

  /**
   * Typing the principal back is the only check that catches "right button, wrong row" — and the
   * user list mixes people with Relyt instance principals, where the wrong row takes a whole
   * compute instance off Unity Catalog.
   */
  @Test
  public void purgingWithoutConfirmingThePrincipalIsRefused() throws IOException {
    deactivate(targetId);

    AggregatedHttpResponse response =
        send(HttpMethod.DELETE, CONTROL_PATH + "/scim2/Users/" + targetId + "?purge=true", "");

    assertThat(response.status().isSuccess()).isFalse();
    assertThat(response.contentUtf8()).contains("confirm_principal");
    assertThat(exists(TARGET)).isTrue();
  }

  @Test
  public void purgingWithTheWrongPrincipalIsRefused() throws IOException {
    deactivate(targetId);

    AggregatedHttpResponse response = purge(targetId, "someone.else@example.com", null);

    assertThat(response.status().isSuccess()).isFalse();
    assertThat(exists(TARGET)).isTrue();
  }

  /**
   * Deactivation first is not ceremony: it puts the irreversible step behind a state an
   * administrator can observe, so a principal that turns out to still be in use says so while the
   * change is still one click from being undone.
   */
  @Test
  public void purgingAnActiveUserIsRefused() throws IOException {
    AggregatedHttpResponse response = purge(targetId, TARGET, null);

    assertThat(response.status().isSuccess()).isFalse();
    assertThat(response.contentUtf8()).contains("deactivated");
    assertThat(exists(TARGET)).isTrue();
  }

  @Test
  public void purgingYourOwnAccountIsRefused() throws IOException {
    // The administrator signs in as "admin", so that is the account it may not purge.
    String adminId = idOf("admin");

    AggregatedHttpResponse response = purge(adminId, "admin", null);

    assertThat(response.status().isSuccess()).isFalse();
    assertThat(exists("admin")).isTrue();
  }

  /** What purging is for: the address becomes available again, which deactivating never does. */
  @Test
  public void purgingFreesTheEmailForReuse() throws IOException {
    deactivate(targetId);

    assertThat(purge(targetId, TARGET, null).status()).isEqualTo(HttpStatus.OK);

    assertThat(exists(TARGET)).isFalse();
    // Creating the same address again is the proof: before the purge this answers 409.
    assertThat(createUser(TARGET)).isNotEqualTo(targetId);
  }

  /**
   * Objects outlive the account that made them. Purging without saying who takes them would leave
   * securables owned by a principal that no longer exists — nobody could then administer them, and
   * nothing in the UI would explain why.
   */
  @Test
  public void purgingSomebodyWhoOwnsThingsNeedsAnHeir() throws IOException {
    createCatalog("inherited_catalog");
    giveOwnership("inherited_catalog", TARGET);
    deactivate(targetId);

    AggregatedHttpResponse refused = purge(targetId, TARGET, null);
    assertThat(refused.status().isSuccess()).isFalse();
    assertThat(refused.contentUtf8()).contains("reassign_to");
    assertThat(exists(TARGET)).isTrue();

    AggregatedHttpResponse accepted = purge(targetId, TARGET, "admin");
    assertThat(accepted.status()).isEqualTo(HttpStatus.OK);
    assertThat(exists(TARGET)).isFalse();
    assertThat(ownerOf("inherited_catalog")).isEqualTo("admin");
  }

  /** The list an administrator is meant to consult before deciding. */
  @Test
  public void ownedObjectsAreListedBeforeTheDecision() throws IOException {
    createCatalog("inherited_catalog");
    giveOwnership("inherited_catalog", TARGET);

    AggregatedHttpResponse response =
        send(HttpMethod.GET, CONTROL_PATH + "/scim2/Users/" + targetId + "/ownedObjects", "");

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    JsonNode body = MAPPER.readTree(response.contentUtf8());
    assertThat(body.get("principal").asText()).isEqualTo(TARGET);
    assertThat(body.get("owned").toString()).contains("inherited_catalog");
  }

  // ---------------------------------------------------------------- helpers

  private AggregatedHttpResponse purge(String id, String confirm, String reassignTo) {
    String query =
        "?purge=true&confirm_principal=" + URLEncoder.encode(confirm, StandardCharsets.UTF_8);
    if (reassignTo != null) {
      query += "&reassign_to=" + URLEncoder.encode(reassignTo, StandardCharsets.UTF_8);
    }
    return send(HttpMethod.DELETE, CONTROL_PATH + "/scim2/Users/" + id + query, "");
  }

  private void deactivate(String id) {
    AggregatedHttpResponse response =
        send(
            HttpMethod.PATCH,
            CONTROL_PATH + "/scim2/Users/" + id,
            "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                + "\"Operations\":[{\"op\":\"replace\",\"value\":{\"active\":false}}]}");
    assertThat(response.status()).isEqualTo(HttpStatus.OK);
  }

  private void createCatalog(String name) {
    AggregatedHttpResponse response =
        send(HttpMethod.POST, CATALOG_PATH + "/catalogs", "{\"name\":\"" + name + "\"}");
    assertThat(response.status().code()).isIn(200, 201);
  }

  /** Ownership is an email on the row, so this is all it takes to stand one up. */
  private void giveOwnership(String catalogName, String email) {
    try (Session session = hibernateConfigurator.getSessionFactory().openSession()) {
      session.beginTransaction();
      CatalogInfoDAO dao =
          session
              .createQuery("FROM CatalogInfoDAO WHERE name = :name", CatalogInfoDAO.class)
              .setParameter("name", catalogName)
              .uniqueResult();
      dao.setOwner(email);
      session.merge(dao);
      session.getTransaction().commit();
    }
  }

  private String ownerOf(String catalogName) {
    try (Session session = hibernateConfigurator.getSessionFactory().openSession()) {
      return session
          .createQuery("FROM CatalogInfoDAO WHERE name = :name", CatalogInfoDAO.class)
          .setParameter("name", catalogName)
          .uniqueResult()
          .getOwner();
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
                + "\"}]}");
    assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
    try {
      return MAPPER.readTree(response.contentUtf8()).get("id").asText();
    } catch (IOException e) {
      throw new IllegalStateException("user creation did not return an id", e);
    }
  }

  private boolean exists(String principal) throws IOException {
    return findUser(principal) != null;
  }

  private String idOf(String principal) throws IOException {
    JsonNode user = findUser(principal);
    if (user == null) {
      throw new IllegalStateException("no user named " + principal);
    }
    return user.get("id").asText();
  }

  private JsonNode findUser(String principal) throws IOException {
    AggregatedHttpResponse response = send(HttpMethod.GET, CONTROL_PATH + "/scim2/Users", "");
    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    for (JsonNode user : MAPPER.readTree(response.contentUtf8()).get("Resources")) {
      if (principal.equals(user.path("userName").asText())) {
        return user;
      }
    }
    return null;
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
