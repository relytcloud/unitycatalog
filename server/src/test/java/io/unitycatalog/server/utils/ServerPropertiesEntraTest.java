package io.unitycatalog.server.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
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
  public void derivedTrustListsStayNullTolerant() throws Exception {
    // A subject token with no 'iss' claim decodes to a null issuer, and AuthService consults the
    // allow-list with it. List.copyOf(...).contains(null) throws NullPointerException where the
    // configured list's returns false, so appending a derived value must not change that: an
    // unauthenticated caller is the one who decides whether this lookup happens with a null, and
    // the answer has to be a 401, not a 500.
    ServerProperties props =
        propertiesWith(
            "server.entra.tenant-id=" + TENANT,
            "server.client-id=entra-client-id",
            "server.allowed-issuers=https://existing-issuer",
            "server.audiences=existing-audience");

    assertThat(props.getAllowedIssuers().contains(null)).isFalse();
    assertThat(props.getAudiences().contains(null)).isFalse();
  }

  @Test
  public void onlyEntraIssuersAreRecognisedAsEntra() throws Exception {
    ServerProperties props = propertiesWith("server.entra.tenant-id=" + TENANT);

    assertThat(props.isEntraIssuer(ENTRA_ISSUER)).isTrue();
    // A different tenant is still Entra: an operator may trust it via server.allowed-issuers
    // alone, without setting a tenant id.
    assertThat(props.isEntraIssuer("https://login.microsoftonline.com/other-tenant/v2.0")).isTrue();
    assertThat(props.isEntraIssuer("relyt-instance-known")).isFalse();
    assertThat(props.isEntraIssuer(null)).isFalse();
  }

  @Test
  public void blankTenantLineInAPropertiesFileLeavesThePropertyAbsent() throws Exception {
    // Why blankTenantIsTreatedAsUnset below does not use a file. readPropertiesFromFile strips
    // every blank-valued entry -- the server.properties shipped in etc/conf is full of them, for
    // documentation -- so "server.entra.tenant-id=" in a file makes the property ABSENT. A test
    // written that way takes nothingIsDerivedWithoutATenant's path and never executes the
    // isBlank() half of the guard it is named after.
    ServerProperties props =
        propertiesWith("server.entra.tenant-id=", "server.client-id=some-client-id");

    assertThat(props.getEntraTenantId()).isNull();
  }

  @Test
  public void blankTenantIsTreatedAsUnset() {
    // The blank value is reachable: the Properties constructor is a bare putAll with no
    // stripping, and it is what BaseServerTest -- and so every server-level test, and anything
    // else building ServerProperties in memory -- uses.
    Properties raw = new Properties();
    raw.setProperty("server.entra.tenant-id", "");
    raw.setProperty("server.client-id", "some-client-id");
    ServerProperties props = new ServerProperties(raw);

    // Present and blank. Without this the test would silently drift back onto the null path and
    // pass for the wrong reason, which is exactly what it did before.
    assertThat(props.getEntraTenantId()).isEmpty();

    assertThat(props.getEntraIssuer()).isNull();
    assertThat(props.getAudiences()).isEmpty();
    assertThat(props.getAllowedIssuers()).isEmpty();
  }

  @Test
  public void whitespaceOnlyTenantIsTreatedAsUnset() {
    // Otherwise the derived issuer would be the nonsense https://login.microsoftonline.com/ /v2.0
    // and it would be unioned into the trusted issuers.
    Properties raw = new Properties();
    raw.setProperty("server.entra.tenant-id", "   ");
    ServerProperties props = new ServerProperties(raw);

    assertThat(props.getEntraIssuer()).isNull();
    assertThat(props.getAllowedIssuers()).isEmpty();
  }
}
