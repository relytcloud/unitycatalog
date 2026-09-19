package io.unitycatalog.server.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Entra configuration is three operator values; the issuer and audience are derived from them and
 * unioned into the configured trust lists. With no tenant configured nothing is derived at all,
 * which is the guarantee that existing DWSU deployments are unaffected.
 */
public class ServerPropertiesEntraTest {

  private static final String TENANT = "11111111-2222-3333-4444-555555555555";
  private static final String ENTRA_ISSUER =
      "https://login.microsoftonline.com/11111111-2222-3333-4444-555555555555/v2.0";

  private ServerProperties propertiesWith(String... lines) throws Exception {
    Path file = Files.createTempFile("server", ".properties");
    Files.writeString(file, String.join("\n", lines) + "\n");
    return new ServerProperties(file.toString());
  }

  @Test
  public void nothingIsDerivedWithoutATenant() throws Exception {
    ServerProperties props =
        propertiesWith(
            "server.allowed-issuers=https://existing-issuer",
            "server.audiences=existing-audience",
            "server.client-id=some-client-id");

    assertThat(props.getEntraIssuer()).isNull();
    assertThat(props.getAllowedIssuers()).containsExactly("https://existing-issuer");
    assertThat(props.getAudiences()).containsExactly("existing-audience");
  }

  @Test
  public void issuerIsDerivedFromTheTenantId() throws Exception {
    ServerProperties props = propertiesWith("server.entra.tenant-id=" + TENANT);

    assertThat(props.getEntraIssuer()).isEqualTo(ENTRA_ISSUER);
  }

  @Test
  public void derivedIssuerAndAudienceAreUnionedWithConfiguredValues() throws Exception {
    ServerProperties props =
        propertiesWith(
            "server.entra.tenant-id=" + TENANT,
            "server.client-id=entra-client-id",
            "server.allowed-issuers=https://existing-issuer",
            "server.audiences=existing-audience");

    assertThat(props.getAllowedIssuers())
        .containsExactlyInAnyOrder("https://existing-issuer", ENTRA_ISSUER);
    assertThat(props.getAudiences())
        .containsExactlyInAnyOrder("existing-audience", "entra-client-id");
  }

  @Test
  public void derivedValuesAreNotDuplicatedWhenAlsoConfigured() throws Exception {
    ServerProperties props =
        propertiesWith(
            "server.entra.tenant-id=" + TENANT,
            "server.client-id=entra-client-id",
            "server.allowed-issuers=" + ENTRA_ISSUER,
            "server.audiences=entra-client-id");

    assertThat(props.getAllowedIssuers()).containsExactly(ENTRA_ISSUER);
    assertThat(props.getAudiences()).containsExactly("entra-client-id");
  }

  @Test
  public void clientIdAloneDoesNotBecomeAnAudience() throws Exception {
    // server.client-id exists upstream for the CLI's code flow. It must only become an accepted
    // audience when an Entra tenant is actually configured.
    ServerProperties props = propertiesWith("server.client-id=some-client-id");

    assertThat(props.getAudiences()).isEmpty();
  }

  @Test
  public void blankTenantIsTreatedAsUnset() throws Exception {
    ServerProperties props =
        propertiesWith("server.entra.tenant-id=", "server.client-id=some-client-id");

    assertThat(props.getEntraIssuer()).isNull();
    assertThat(props.getAudiences()).isEmpty();
  }
}
