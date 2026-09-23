package io.unitycatalog.server.utils;

import static io.unitycatalog.server.utils.JwksOperations.issuerMatchesConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Entra ID's multi-tenant discovery documents advertise {@code {tenantid}} where the token carries
 * the real tenant GUID (issue #15). The comparison must accept exactly that substitution and
 * nothing looser, and must leave single-tenant (exact) configurations untouched.
 */
public class JwksOperationsIssuerMatchTest {

  private static final String TENANT = "72f988bf-86f1-41af-91ab-2d7cd011db47";
  private static final String TEMPLATE = "https://login.microsoftonline.com/{tenantid}/v2.0";
  private static final String TENANT_ISSUER =
      "https://login.microsoftonline.com/" + TENANT + "/v2.0";

  @Test
  public void exactIssuerStillMatchesExactly() {
    assertThat(issuerMatchesConfiguration(TENANT_ISSUER, TENANT_ISSUER)).isTrue();
    assertThat(
            issuerMatchesConfiguration(
                "https://accounts.google.com", "https://accounts.google.com"))
        .isTrue();
  }

  @Test
  public void exactIssuerRejectsAnyDifference() {
    assertThat(
            issuerMatchesConfiguration(
                TENANT_ISSUER, "https://login.microsoftonline.com/other-tenant/v2.0"))
        .isFalse();
    assertThat(
            issuerMatchesConfiguration(
                "https://accounts.google.com", "https://accounts.google.com/"))
        .isFalse();
  }

  @Test
  public void tenantTemplateMatchesTheRealTenantGuid() {
    assertThat(issuerMatchesConfiguration(TEMPLATE, TENANT_ISSUER)).isTrue();
  }

  @Test
  public void tenantTemplateOnlyReplacesThatSegment() {
    // Different host: the template must not turn into a wildcard for the whole URL.
    assertThat(issuerMatchesConfiguration(TEMPLATE, "https://evil.example.com/" + TENANT + "/v2.0"))
        .isFalse();
    // Different version segment.
    assertThat(
            issuerMatchesConfiguration(
                TEMPLATE, "https://login.microsoftonline.com/" + TENANT + "/v1.0"))
        .isFalse();
    // Different scheme.
    assertThat(
            issuerMatchesConfiguration(
                TEMPLATE, "http://login.microsoftonline.com/" + TENANT + "/v2.0"))
        .isFalse();
  }

  @Test
  public void tenantTemplateRejectsMissingOrExtraSegments() {
    assertThat(issuerMatchesConfiguration(TEMPLATE, "https://login.microsoftonline.com//v2.0"))
        .isFalse();
    assertThat(
            issuerMatchesConfiguration(
                TEMPLATE, "https://login.microsoftonline.com/" + TENANT + "/extra/v2.0"))
        .isFalse();
    assertThat(issuerMatchesConfiguration(TEMPLATE, "https://login.microsoftonline.com/v2.0"))
        .isFalse();
  }

  @Test
  public void nullsNeverMatch() {
    assertThat(issuerMatchesConfiguration(null, TENANT_ISSUER)).isFalse();
    assertThat(issuerMatchesConfiguration(TEMPLATE, null)).isFalse();
  }
}
