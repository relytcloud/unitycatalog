package io.unitycatalog.server.sdk.access;

import static io.unitycatalog.server.utils.TestUtils.assertPermissionDenied;
import static org.assertj.core.api.Assertions.assertThat;

import io.unitycatalog.client.ApiClient;
import io.unitycatalog.client.api.CatalogsApi;
import io.unitycatalog.client.api.GrantsApi;
import io.unitycatalog.client.api.SchemasApi;
import io.unitycatalog.client.api.TablesApi;
import io.unitycatalog.client.model.CreateCatalog;
import io.unitycatalog.client.model.CreateSchema;
import io.unitycatalog.client.model.PermissionsChange;
import io.unitycatalog.client.model.Privilege;
import io.unitycatalog.client.model.SchemaInfo;
import io.unitycatalog.client.model.SecurableType;
import io.unitycatalog.client.model.TableInfo;
import io.unitycatalog.client.model.UpdatePermissions;
import io.unitycatalog.server.base.ServerConfig;
import io.unitycatalog.server.persist.model.Privileges;
import io.unitycatalog.server.utils.TestUtils;
import java.util.List;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

/**
 * SELECT granted on a schema or a catalog reaches every table beneath it, including tables created
 * afterwards, which is what a service account wants instead of a grant per table. It reaches
 * nothing outside that subtree: a sibling schema or another catalog stays closed. Because the reach
 * is wide, only a metastore owner may grant or revoke SELECT (and MODIFY) at those levels; owners
 * keep the table-level grants and the USE_* those need.
 */
public class SdkHierarchyGrantAccessControlTest extends SdkAccessControlBaseCRUDTest {

  private static final String CATALOG = "cat_pr1";
  private static final String SCHEMA_NAME = "sch_pr1";
  private static final String SCHEMA = CATALOG + "." + SCHEMA_NAME;

  @Test
  @SneakyThrows
  public void schemaSelectReachesEveryTableIncludingFutureOnes() {
    createCommonTestUsers();
    setupCommonCatalogAndSchema();
    ServerConfig principal1Config = createTestUserServerConfig(PRINCIPAL_1);
    ServerConfig regular1Config = createTestUserServerConfig(REGULAR_1);
    TablesApi principal1Tables = new TablesApi(TestUtils.createApiClient(principal1Config));
    TablesApi regular1Tables = new TablesApi(TestUtils.createApiClient(regular1Config));

    // The schema's owner creates a table; nobody else has anything on it.
    grantPermissions(PRINCIPAL_1, SecurableType.SCHEMA, SCHEMA, Privileges.USE_SCHEMA);
    createExternalTable(
        principal1Tables, CATALOG, SCHEMA_NAME, "t_before", testDirectoryRoot + "/t_before");
    assertThat(listAllTables(regular1Tables, CATALOG, SCHEMA_NAME)).isEmpty();

    // Read access at the schema: the two USE_* to reach it, and SELECT on the schema itself.
    grantPermissions(REGULAR_1, SecurableType.CATALOG, CATALOG, Privileges.USE_CATALOG);
    grantPermissions(
        REGULAR_1, SecurableType.SCHEMA, SCHEMA, Privileges.USE_SCHEMA, Privileges.SELECT);

    List<TableInfo> visible = listAllTables(regular1Tables, CATALOG, SCHEMA_NAME);
    assertThat(visible).extracting(TableInfo::getName).containsExactly("t_before");
    assertThat(getTable(regular1Tables, SCHEMA + ".t_before")).isNotNull();

    // A table created after the grant, with no grant of its own, is covered as well.
    createExternalTable(
        principal1Tables, CATALOG, SCHEMA_NAME, "t_after", testDirectoryRoot + "/t_after");
    assertThat(listAllTables(regular1Tables, CATALOG, SCHEMA_NAME))
        .extracting(TableInfo::getName)
        .containsExactlyInAnyOrder("t_before", "t_after");
    assertThat(getTable(regular1Tables, SCHEMA + ".t_after")).isNotNull();

    // Revoking the schema-level SELECT takes every table with it at once.
    grantsApi.update(SecurableType.SCHEMA, SCHEMA, remove(REGULAR_1, Privilege.SELECT));
    assertThat(listAllTables(regular1Tables, CATALOG, SCHEMA_NAME)).isEmpty();
    assertPermissionDenied(() -> regular1Tables.getTable(SCHEMA + ".t_after", false, false));
  }

  @Test
  @SneakyThrows
  public void schemaSelectStopsAtSiblingSchemasAndOtherCatalogs() {
    createCommonTestUsers();
    setupCommonCatalogAndSchema();
    ApiClient principal1Client = TestUtils.createApiClient(createTestUserServerConfig(PRINCIPAL_1));
    ApiClient regular1Client = TestUtils.createApiClient(createTestUserServerConfig(REGULAR_1));
    TablesApi principal1Tables = new TablesApi(principal1Client);
    TablesApi regular1Tables = new TablesApi(regular1Client);
    SchemasApi regular1Schemas = new SchemasApi(regular1Client);

    createExternalTable(
        principal1Tables, CATALOG, SCHEMA_NAME, "t_in", testDirectoryRoot + "/t_in");
    grantPermissions(REGULAR_1, SecurableType.CATALOG, CATALOG, Privileges.USE_CATALOG);
    grantPermissions(
        REGULAR_1, SecurableType.SCHEMA, SCHEMA, Privileges.USE_SCHEMA, Privileges.SELECT);
    assertThat(listAllTables(regular1Tables, CATALOG, SCHEMA_NAME))
        .extracting(TableInfo::getName)
        .containsExactly("t_in");

    // A sibling schema created later in the same catalog, with a table in it.
    String sibling = "sch_sibling";
    createSchema(principal1Client, CATALOG, sibling);
    createExternalTable(principal1Tables, CATALOG, sibling, "t_sib", testDirectoryRoot + "/t_sib");

    // The schema-level read neither lists nor reads anything in the sibling ...
    assertThat(listSchemas(regular1Schemas, CATALOG)).containsExactly(SCHEMA_NAME);
    assertThat(listAllTables(regular1Tables, CATALOG, sibling)).isEmpty();
    assertPermissionDenied(() -> regular1Schemas.getSchema(CATALOG + "." + sibling));
    assertPermissionDenied(
        () -> regular1Tables.getTable(CATALOG + "." + sibling + ".t_sib", false, false));

    // ... and being let into the sibling (USE_SCHEMA) still reads none of its tables, because
    // the SELECT sits on sch_pr1 and does not travel sideways.
    grantPermissions(
        REGULAR_1, SecurableType.SCHEMA, CATALOG + "." + sibling, Privileges.USE_SCHEMA);
    assertThat(listSchemas(regular1Schemas, CATALOG))
        .containsExactlyInAnyOrder(SCHEMA_NAME, sibling);
    assertThat(listAllTables(regular1Tables, CATALOG, sibling)).isEmpty();
    assertPermissionDenied(
        () -> regular1Tables.getTable(CATALOG + "." + sibling + ".t_sib", false, false));

    // A brand-new catalog with its own schema and table is out of reach entirely.
    String other = "cat_other";
    createCatalog(principal1Client, other);
    createSchema(principal1Client, other, "sch_x");
    createExternalTable(principal1Tables, other, "sch_x", "t_x", testDirectoryRoot + "/t_x");
    assertThat(listSchemas(regular1Schemas, other)).isEmpty();
    assertThat(listAllTables(regular1Tables, other, "sch_x")).isEmpty();
    assertPermissionDenied(() -> regular1Schemas.getSchema(other + ".sch_x"));
    assertPermissionDenied(() -> regular1Tables.getTable(other + ".sch_x.t_x", false, false));
  }

  @Test
  @SneakyThrows
  public void catalogSelectReachesEverySchemaAndTableIncludingFutureSchemas() {
    createCommonTestUsers();
    setupCommonCatalogAndSchema();
    ApiClient principal1Client = TestUtils.createApiClient(createTestUserServerConfig(PRINCIPAL_1));
    ApiClient regular2Client = TestUtils.createApiClient(createTestUserServerConfig(REGULAR_2));
    TablesApi principal1Tables = new TablesApi(principal1Client);
    TablesApi regular2Tables = new TablesApi(regular2Client);
    SchemasApi regular2Schemas = new SchemasApi(regular2Client);

    createExternalTable(principal1Tables, CATALOG, SCHEMA_NAME, "t1", testDirectoryRoot + "/t1");

    // Read access at the catalog. USE_SCHEMA is granted on the catalog too: the entry right
    // is checked per schema, and granted this high it inherits to every schema, present and
    // future, exactly like SELECT does.
    grantPermissions(
        REGULAR_2,
        SecurableType.CATALOG,
        CATALOG,
        Privileges.USE_CATALOG,
        Privileges.USE_SCHEMA,
        Privileges.SELECT);

    assertThat(listSchemas(regular2Schemas, CATALOG)).containsExactly(SCHEMA_NAME);
    assertThat(listAllTables(regular2Tables, CATALOG, SCHEMA_NAME))
        .extracting(TableInfo::getName)
        .containsExactly("t1");
    assertThat(getTable(regular2Tables, SCHEMA + ".t1")).isNotNull();

    // A schema and table created after the grant, with no grant of their own.
    String later = "sch_later";
    createSchema(principal1Client, CATALOG, later);
    createExternalTable(
        principal1Tables, CATALOG, later, "t_later", testDirectoryRoot + "/t_later");
    assertThat(listSchemas(regular2Schemas, CATALOG)).containsExactlyInAnyOrder(SCHEMA_NAME, later);
    assertThat(listAllTables(regular2Tables, CATALOG, later))
        .extracting(TableInfo::getName)
        .containsExactly("t_later");
    assertThat(getTable(regular2Tables, CATALOG + "." + later + ".t_later")).isNotNull();

    // Revoking the catalog-level SELECT closes every table in every schema at once; the USE_*
    // stay, so the schemas themselves remain listed (an empty room, not a locked door).
    grantsApi.update(SecurableType.CATALOG, CATALOG, remove(REGULAR_2, Privilege.SELECT));
    assertThat(listSchemas(regular2Schemas, CATALOG)).containsExactlyInAnyOrder(SCHEMA_NAME, later);
    assertThat(listAllTables(regular2Tables, CATALOG, SCHEMA_NAME)).isEmpty();
    assertThat(listAllTables(regular2Tables, CATALOG, later)).isEmpty();
    assertPermissionDenied(() -> regular2Tables.getTable(SCHEMA + ".t1", false, false));
    assertPermissionDenied(
        () -> regular2Tables.getTable(CATALOG + "." + later + ".t_later", false, false));
  }

  @Test
  @SneakyThrows
  public void catalogSelectStopsAtOtherCatalogs() {
    createCommonTestUsers();
    setupCommonCatalogAndSchema();
    ApiClient principal1Client = TestUtils.createApiClient(createTestUserServerConfig(PRINCIPAL_1));
    ApiClient regular2Client = TestUtils.createApiClient(createTestUserServerConfig(REGULAR_2));
    TablesApi principal1Tables = new TablesApi(principal1Client);
    TablesApi regular2Tables = new TablesApi(regular2Client);
    SchemasApi regular2Schemas = new SchemasApi(regular2Client);

    grantPermissions(
        REGULAR_2,
        SecurableType.CATALOG,
        CATALOG,
        Privileges.USE_CATALOG,
        Privileges.USE_SCHEMA,
        Privileges.SELECT);

    String other = "cat_other";
    createCatalog(principal1Client, other);
    createSchema(principal1Client, other, "sch_x");
    createExternalTable(principal1Tables, other, "sch_x", "t_x", testDirectoryRoot + "/t_x");

    assertThat(listSchemas(regular2Schemas, other)).isEmpty();
    assertThat(listAllTables(regular2Tables, other, "sch_x")).isEmpty();
    assertPermissionDenied(() -> regular2Schemas.getSchema(other + ".sch_x"));
    assertPermissionDenied(() -> regular2Tables.getTable(other + ".sch_x.t_x", false, false));
    // The catalog the grant was made on is unaffected by the new neighbour.
    assertThat(listSchemas(regular2Schemas, CATALOG)).containsExactly(SCHEMA_NAME);
  }

  @Test
  @SneakyThrows
  public void onlyAMetastoreOwnerMayGrantSelectOnASchemaOrCatalog() {
    createCommonTestUsers();
    setupCommonCatalogAndSchema();
    ServerConfig principal1Config = createTestUserServerConfig(PRINCIPAL_1);
    GrantsApi principal1Grants = new GrantsApi(TestUtils.createApiClient(principal1Config));

    // principal-1 owns both the catalog and the schema, and may still grant what does not
    // reach down: USE_SCHEMA and CREATE_TABLE on the schema, USE_CATALOG on the catalog.
    principal1Grants.update(
        SecurableType.SCHEMA, SCHEMA, add(REGULAR_1, Privilege.USE_SCHEMA, Privilege.CREATE_TABLE));
    principal1Grants.update(SecurableType.CATALOG, CATALOG, add(REGULAR_1, Privilege.USE_CATALOG));

    // SELECT and MODIFY at those levels are the metastore owner's alone.
    assertPermissionDenied(
        () ->
            principal1Grants.update(
                SecurableType.SCHEMA, SCHEMA, add(REGULAR_1, Privilege.SELECT)));
    assertPermissionDenied(
        () ->
            principal1Grants.update(
                SecurableType.SCHEMA, SCHEMA, add(REGULAR_1, Privilege.MODIFY)));
    assertPermissionDenied(
        () ->
            principal1Grants.update(
                SecurableType.CATALOG, CATALOG, add(REGULAR_1, Privilege.SELECT)));
    // ... including taking it away again.
    grantPermissions(REGULAR_1, SecurableType.SCHEMA, SCHEMA, Privileges.SELECT);
    assertPermissionDenied(
        () ->
            principal1Grants.update(
                SecurableType.SCHEMA, SCHEMA, remove(REGULAR_1, Privilege.SELECT)));

    // The metastore owner (admin) may, and the owner's other grants were not disturbed.
    grantsApi.update(SecurableType.SCHEMA, SCHEMA, remove(REGULAR_1, Privilege.SELECT));
  }

  @Test
  @SneakyThrows
  public void tableOwnerStillGrantsReadOnTheirOwnTable() {
    createCommonTestUsers();
    setupCommonCatalogAndSchema();
    ServerConfig principal1Config = createTestUserServerConfig(PRINCIPAL_1);
    ServerConfig regular2Config = createTestUserServerConfig(REGULAR_2);
    TablesApi principal1Tables = new TablesApi(TestUtils.createApiClient(principal1Config));
    TablesApi regular2Tables = new TablesApi(TestUtils.createApiClient(regular2Config));
    GrantsApi principal1Grants = new GrantsApi(TestUtils.createApiClient(principal1Config));

    grantPermissions(PRINCIPAL_1, SecurableType.SCHEMA, SCHEMA, Privileges.USE_SCHEMA);
    createExternalTable(
        principal1Tables, CATALOG, SCHEMA_NAME, "mine", testDirectoryRoot + "/mine");

    // The usual one-table read: USE_* on the ancestors plus SELECT on the table, all by the owner.
    principal1Grants.update(SecurableType.CATALOG, CATALOG, add(REGULAR_2, Privilege.USE_CATALOG));
    principal1Grants.update(SecurableType.SCHEMA, SCHEMA, add(REGULAR_2, Privilege.USE_SCHEMA));
    principal1Grants.update(
        SecurableType.TABLE, SCHEMA + ".mine", add(REGULAR_2, Privilege.SELECT));

    assertThat(listAllTables(regular2Tables, CATALOG, SCHEMA_NAME))
        .extracting(TableInfo::getName)
        .containsExactly("mine");
  }

  // principal-1 holds CREATE CATALOG on the metastore (common setup) and owns what it creates,
  // so it can go on to create schemas and tables inside; the admin's metastore OWNER does not
  // satisfy the create expressions, which look for the catalog / schema owner or USE_* +
  // CREATE_*.
  @SneakyThrows
  private static void createCatalog(ApiClient client, String name) {
    new CatalogsApi(client).createCatalog(new CreateCatalog().name(name));
  }

  @SneakyThrows
  private static void createSchema(ApiClient client, String catalog, String name) {
    new SchemasApi(client).createSchema(new CreateSchema().name(name).catalogName(catalog));
  }

  @SneakyThrows
  private static List<String> listSchemas(SchemasApi schemasApi, String catalog) {
    return schemasApi.listSchemas(catalog, null, null).getSchemas().stream()
        .map(SchemaInfo::getName)
        .toList();
  }

  private static UpdatePermissions add(String principal, Privilege... privileges) {
    return new UpdatePermissions()
        .changes(
            List.of(
                new PermissionsChange()
                    .principal(principal)
                    .add(List.of(privileges))
                    .remove(List.of())));
  }

  private static UpdatePermissions remove(String principal, Privilege... privileges) {
    return new UpdatePermissions()
        .changes(
            List.of(
                new PermissionsChange()
                    .principal(principal)
                    .add(List.of())
                    .remove(List.of(privileges))));
  }
}
