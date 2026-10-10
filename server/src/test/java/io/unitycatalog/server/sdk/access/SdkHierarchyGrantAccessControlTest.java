package io.unitycatalog.server.sdk.access;

import static io.unitycatalog.server.utils.TestUtils.assertPermissionDenied;
import static org.assertj.core.api.Assertions.assertThat;

import io.unitycatalog.client.api.GrantsApi;
import io.unitycatalog.client.api.TablesApi;
import io.unitycatalog.client.model.PermissionsChange;
import io.unitycatalog.client.model.Privilege;
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
 * afterwards, which is what a service account wants instead of a grant per table. Because that
 * reach is wide, only a metastore owner may grant or revoke SELECT (and MODIFY) at those levels;
 * owners keep the table-level grants and the USE_* those need.
 */
public class SdkHierarchyGrantAccessControlTest extends SdkAccessControlBaseCRUDTest {

  private static final String CATALOG = "cat_pr1";
  private static final String SCHEMA = CATALOG + ".sch_pr1";

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
        principal1Tables, CATALOG, "sch_pr1", "t_before", testDirectoryRoot + "/t_before");
    assertThat(listAllTables(regular1Tables, CATALOG, "sch_pr1")).isEmpty();

    // Read access at the schema: the two USE_* to reach it, and SELECT on the schema itself.
    grantPermissions(REGULAR_1, SecurableType.CATALOG, CATALOG, Privileges.USE_CATALOG);
    grantPermissions(
        REGULAR_1, SecurableType.SCHEMA, SCHEMA, Privileges.USE_SCHEMA, Privileges.SELECT);

    List<TableInfo> visible = listAllTables(regular1Tables, CATALOG, "sch_pr1");
    assertThat(visible).extracting(TableInfo::getName).containsExactly("t_before");
    assertThat(getTable(regular1Tables, SCHEMA + ".t_before")).isNotNull();

    // A table created after the grant, with no grant of its own, is covered as well.
    createExternalTable(
        principal1Tables, CATALOG, "sch_pr1", "t_after", testDirectoryRoot + "/t_after");
    assertThat(listAllTables(regular1Tables, CATALOG, "sch_pr1"))
        .extracting(TableInfo::getName)
        .containsExactlyInAnyOrder("t_before", "t_after");
    assertThat(getTable(regular1Tables, SCHEMA + ".t_after")).isNotNull();

    // Revoking the schema-level SELECT takes every table with it at once.
    grantsApi.update(
        SecurableType.SCHEMA,
        SCHEMA,
        new UpdatePermissions()
            .changes(
                List.of(
                    new PermissionsChange()
                        .principal(REGULAR_1)
                        .add(List.of())
                        .remove(List.of(Privilege.SELECT)))));
    assertThat(listAllTables(regular1Tables, CATALOG, "sch_pr1")).isEmpty();
    assertPermissionDenied(() -> regular1Tables.getTable(SCHEMA + ".t_after", false, false));
  }

  @Test
  @SneakyThrows
  public void catalogSelectReachesEverySchemaAndTable() {
    createCommonTestUsers();
    setupCommonCatalogAndSchema();
    ServerConfig principal1Config = createTestUserServerConfig(PRINCIPAL_1);
    ServerConfig regular2Config = createTestUserServerConfig(REGULAR_2);
    TablesApi principal1Tables = new TablesApi(TestUtils.createApiClient(principal1Config));
    TablesApi regular2Tables = new TablesApi(TestUtils.createApiClient(regular2Config));

    grantPermissions(PRINCIPAL_1, SecurableType.SCHEMA, SCHEMA, Privileges.USE_SCHEMA);
    createExternalTable(principal1Tables, CATALOG, "sch_pr1", "t1", testDirectoryRoot + "/t1");

    // USE_SCHEMA is still needed to enter the schema; SELECT comes from the catalog.
    grantPermissions(
        REGULAR_2, SecurableType.CATALOG, CATALOG, Privileges.USE_CATALOG, Privileges.SELECT);
    grantPermissions(REGULAR_2, SecurableType.SCHEMA, SCHEMA, Privileges.USE_SCHEMA);

    assertThat(listAllTables(regular2Tables, CATALOG, "sch_pr1"))
        .extracting(TableInfo::getName)
        .containsExactly("t1");
    assertThat(getTable(regular2Tables, SCHEMA + ".t1")).isNotNull();
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
    createExternalTable(principal1Tables, CATALOG, "sch_pr1", "mine", testDirectoryRoot + "/mine");

    // The usual one-table read: USE_* on the ancestors plus SELECT on the table, all by the owner.
    principal1Grants.update(SecurableType.CATALOG, CATALOG, add(REGULAR_2, Privilege.USE_CATALOG));
    principal1Grants.update(SecurableType.SCHEMA, SCHEMA, add(REGULAR_2, Privilege.USE_SCHEMA));
    principal1Grants.update(
        SecurableType.TABLE, SCHEMA + ".mine", add(REGULAR_2, Privilege.SELECT));

    assertThat(listAllTables(regular2Tables, CATALOG, "sch_pr1"))
        .extracting(TableInfo::getName)
        .containsExactly("mine");
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
