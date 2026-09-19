# Microsoft Entra ID Server Trust Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user holding a Microsoft Entra ID token exchange it for a Unity Catalog access token, without breaking the existing DWSU static-JWKS trust path.

**Architecture:** JWKS resolution becomes per-issuer: the static external JWKS file is authoritative only for the issuers it *declares* (via each key's `issuer` member), and everything else resolves by OIDC discovery. Entra configuration is three operator values (tenant id, client id, client secret), from which the issuer and audience are derived inside `ServerProperties` and the OAuth URLs are derived at render time in `deploy-uc.sh`. Discovery gets caching, rate limiting, timeouts, and honest status codes.

**Tech Stack:** Java 17, sbt, Armeria (server + `WebClient`), `com.auth0:java-jwt:4.4.0`, `com.auth0:jwks-rsa:0.22.1`, JUnit 5, AssertJ, Mockito.

**Spec:** `docs/superpowers/specs/2026-09-19-entra-idp-server-trust-design.md`

## Global Constraints

- **No behavior change when `server.entra.tenant-id` is unset.** Every derivation is gated on it. This is the guarantee for live DWSU deployments and is explicitly tested.
- **The static JWKS file path stays `.cached(false)`.** Hot key onboarding without restart is a documented feature (`deploy/README.md`, "Onboarding a new DWSU"). Only the discovery path gets caching.
- **Entra authority is fixed at `https://login.microsoftonline.com`.** Sovereign clouds (China, US Gov) are out of scope; do not add an authority-host key.
- **Fixed tuning values, in code, not configurable:** key cache 10 entries / 24 hour TTL; rate limit 10 fetches per minute; discovery-document TTL 24 hours; HTTP timeout 5 seconds (connect and read).
- **Entra issuer format:** `https://login.microsoftonline.com/<tenant-id>/v2.0` (v2.0 endpoint, not v1.0 `sts.windows.net`).
- **Derivation composes, never replaces.** Derived values are unioned into configured ones; an explicitly configured value wins.
- **Run a single test class:** `build/sbt "server/testOnly <fully.qualified.ClassName>"`
- **Run the whole server suite:** `build/sbt server/test`
- Commit after every task. Do not push.

---

### Task 1: Derive the Entra issuer and audience in ServerProperties

The three operator values land in `server.properties`. The server derives the issuer from the tenant id and the audience from the client id, and unions both into the existing trust lists. Nothing is derived when the tenant id is absent.

**Files:**
- Modify: `server/src/main/java/io/unitycatalog/server/utils/ServerProperties.java` (add getters near `getAllowedIssuers()` at :510 and `getAudiences()` at :522)
- Test: `server/src/test/java/io/unitycatalog/server/utils/ServerPropertiesEntraTest.java` (create)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `public String getEntraTenantId()` — raw `server.entra.tenant-id`, or null.
  - `public String getEntraIssuer()` — `https://login.microsoftonline.com/<tenant>/v2.0`, or null when no tenant configured.
  - `public List<String> getAllowedIssuers()` — existing signature, now unioned with the derived issuer.
  - `public List<String> getAudiences()` — existing signature, now unioned with `server.client-id` when a tenant is configured.

Note: `server.entra.tenant-id` is read with the private `getProperty(String)` helper rather than being added to the `Property` enum. That matches how this fork already added `server.external-jwks-file` and `server.access-token-ttl`.

- [ ] **Step 1: Write the failing test**

Create `server/src/test/java/io/unitycatalog/server/utils/ServerPropertiesEntraTest.java`:

```java
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `build/sbt "server/testOnly io.unitycatalog.server.utils.ServerPropertiesEntraTest"`
Expected: FAIL to compile — `getEntraIssuer()` and `getEntraTenantId()` do not exist.

- [ ] **Step 3: Write the implementation**

In `ServerProperties.java`, add the constant near the other static fields:

```java
  /**
   * Fixed Microsoft Entra ID authority. Sovereign clouds (Azure China, US Gov) are deliberately
   * unsupported; deployments are on the global cloud.
   */
  private static final String ENTRA_AUTHORITY = "https://login.microsoftonline.com";
```

Add these methods next to `getAudiences()`:

```java
  /**
   * Microsoft Entra ID tenant id. When set, the Entra issuer and audience are derived from it and
   * unioned into {@link #getAllowedIssuers()} and {@link #getAudiences()}. When unset, nothing is
   * derived and trust is exactly what the file configures.
   */
  public String getEntraTenantId() {
    return getProperty("server.entra.tenant-id");
  }

  /**
   * The Entra v2.0 issuer derived from {@code server.entra.tenant-id}, or null when no tenant is
   * configured. This is the exact string Entra puts in the {@code iss} claim of a v2.0 token.
   */
  public String getEntraIssuer() {
    String tenantId = getEntraTenantId();
    if (tenantId == null || tenantId.isBlank()) {
      return null;
    }
    return ENTRA_AUTHORITY + "/" + tenantId.trim() + "/v2.0";
  }

  /**
   * Union a configured list with a derived value. The derived value is appended only when it is
   * present and not already configured, so derivation composes with explicit configuration rather
   * than replacing it.
   */
  private static List<String> unionWithDerived(List<String> configured, String derived) {
    if (derived == null || derived.isBlank() || configured.contains(derived)) {
      return configured;
    }
    List<String> combined = new ArrayList<>(configured);
    combined.add(derived);
    return List.copyOf(combined);
  }
```

Change the two existing getters' bodies:

```java
  public List<String> getAllowedIssuers() {
    return unionWithDerived(getCommaSeparatedList("server.allowed-issuers"), getEntraIssuer());
  }

  public List<String> getAudiences() {
    // The client id is only an accepted audience when an Entra tenant is configured; it exists
    // upstream for the CLI's authorization-code flow and must not widen trust on its own.
    String entraAudience = getEntraIssuer() == null ? null : getProperty("server.client-id");
    return unionWithDerived(getCommaSeparatedList("server.audiences"), entraAudience);
  }
```

Add the import `java.util.ArrayList` to the import block.

- [ ] **Step 4: Run the test to verify it passes**

Run: `build/sbt "server/testOnly io.unitycatalog.server.utils.ServerPropertiesEntraTest"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Run the existing suite to confirm nothing regressed**

Run: `build/sbt server/test`
Expected: PASS. `AuthServiceTest` and `AuthServiceExternalJwksTest` in particular must be unaffected, since neither configures a tenant.

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/io/unitycatalog/server/utils/ServerProperties.java \
        server/src/test/java/io/unitycatalog/server/utils/ServerPropertiesEntraTest.java
git commit -m "feat(server): derive the Entra issuer and audience from the tenant id"
```

---

### Task 2: Route JWKS resolution per issuer

Today the static JWKS file swallows every external issuer because the branch tests whether the file *exists*. Change it to test whether the file *declares* the issuer, so anything else falls through to OIDC discovery.

**Files:**
- Modify: `server/src/main/java/io/unitycatalog/server/utils/JwksOperations.java:110-115`
- Create: `server/src/test/java/io/unitycatalog/server/utils/DiscoveryTestServer.java`
- Modify: `server/src/test/java/io/unitycatalog/server/utils/JwksOperationsTest.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces:
  - `DiscoveryTestServer implements AutoCloseable` with `String issuer()`, `int discoveryHits()`, `int jwksHits()`, `void failDiscoveryWith(HttpStatus)`, `void delayDiscoveryBy(Duration)`. Tasks 3 and 5 reuse it.
  - `JwksOperations.loadJwkProvider(String issuer)` — unchanged signature, new routing.

- [ ] **Step 1: Write the test discovery server**

Create `server/src/test/java/io/unitycatalog/server/utils/DiscoveryTestServer.java`:

```java
package io.unitycatalog.server.utils;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.Server;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local OIDC provider for tests: serves {@code /.well-known/openid-configuration} and a JWKS at
 * {@code /keys}, counts requests, and can be made to fail. Armeria is already a main dependency, so
 * the real discovery path is exercised over real HTTP with no network access and no new test
 * dependency.
 *
 * <p>The issuer is {@code http://127.0.0.1:<port>}. {@code JwksOperations} only prefixes a scheme
 * when one is absent, so an explicit {@code http://} issuer reaches this server unmodified.
 */
public final class DiscoveryTestServer implements AutoCloseable {

  private final Server server;
  private final String jwksJson;
  private final AtomicInteger discoveryHits = new AtomicInteger();
  private final AtomicInteger jwksHits = new AtomicInteger();
  private volatile HttpStatus discoveryStatus = HttpStatus.OK;
  private volatile Duration discoveryDelay = Duration.ZERO;

  public DiscoveryTestServer(String jwksJson) {
    this.jwksJson = jwksJson;
    this.server =
        Server.builder()
            .http(0)
            .service(
                "/.well-known/openid-configuration",
                (ctx, req) -> {
                  discoveryHits.incrementAndGet();
                  HttpResponse response =
                      discoveryStatus.equals(HttpStatus.OK)
                          ? HttpResponse.of(MediaType.JSON, discoveryDocument())
                          : HttpResponse.of(discoveryStatus);
                  // Delayed rather than slept: the handler runs on an event loop and must not block.
                  return discoveryDelay.isZero()
                      ? response
                      : HttpResponse.delayed(response, discoveryDelay);
                })
            .service(
                "/keys",
                (ctx, req) -> {
                  jwksHits.incrementAndGet();
                  return HttpResponse.of(MediaType.JSON, this.jwksJson);
                })
            .build();
    server.start().join();
  }

  /** The issuer identifier this server answers for. */
  public String issuer() {
    return "http://127.0.0.1:" + server.activeLocalPort();
  }

  private String discoveryDocument() {
    return String.format(
        "{\"issuer\":\"%s\",\"jwks_uri\":\"%s/keys\"}", issuer(), issuer());
  }

  /** Make subsequent discovery requests fail with the given status. */
  public void failDiscoveryWith(HttpStatus status) {
    this.discoveryStatus = status;
  }

  /** Delay subsequent discovery responses, to exercise the client-side timeout. */
  public void delayDiscoveryBy(Duration delay) {
    this.discoveryDelay = delay;
  }

  public int discoveryHits() {
    return discoveryHits.get();
  }

  public int jwksHits() {
    return jwksHits.get();
  }

  @Override
  public void close() {
    server.stop().join();
  }
}
```

- [ ] **Step 2: Write the failing routing tests**

Add to `JwksOperationsTest.java` (imports needed: `io.unitycatalog.server.utils.DiscoveryTestServer` is same-package so no import; add `com.linecorp.armeria.common.HttpStatus` only if used here — it is not):

The file already has a `opsForJwks(String jwksJson)` helper that writes a temp JWKS and builds a
`JwksOperations` around it. Reuse it — do not add a second one.

```java
  @Test
  public void issuerDeclaredInTheFileResolvesFromTheFile() throws Exception {
    // A reachable discovery server exists for this issuer, but the file declares it, so the file
    // wins and no discovery request is made.
    try (DiscoveryTestServer idp =
        new DiscoveryTestServer("{\"keys\":[" + entry("kidRemote", X_B, Y_B, null) + "]}")) {
      JwksOperations ops =
          opsForJwks("{\"keys\":[" + entry("kidLocal", X_A, Y_A, idp.issuer()) + "]}");

      JwkProvider provider = ops.loadJwkProvider(idp.issuer());

      assertThat(provider.get("kidLocal").getId()).isEqualTo("kidLocal");
      assertThat(idp.discoveryHits()).isZero();
    }
  }

  @Test
  public void issuerNotDeclaredInTheFileResolvesByDiscovery() throws Exception {
    // The file exists and declares a different issuer. Under the old all-or-nothing behavior this
    // issuer would have been forced through the file and failed; it must reach discovery.
    try (DiscoveryTestServer idp =
        new DiscoveryTestServer("{\"keys\":[" + entry("kidRemote", X_B, Y_B, null) + "]}")) {
      JwksOperations ops =
          opsForJwks("{\"keys\":[" + entry("kidLocal", X_A, Y_A, "some-other-issuer") + "]}");

      JwkProvider provider = ops.loadJwkProvider(idp.issuer());

      assertThat(provider.get("kidRemote").getId()).isEqualTo("kidRemote");
      assertThat(idp.discoveryHits()).isEqualTo(1);
    }
  }
```

Also adapt the existing `keyWithoutIssuerMemberIsRejected` test. Its current JWKS declares no issuer at all, so under the new routing `issuer-a` would go to discovery instead of the file, changing what the test exercises. Replace its body so the issuer is declared by a second key, keeping the test's actual intent — a key carrying no `issuer` member is unusable:

```java
  @Test
  public void keyWithoutIssuerMemberIsRejected() throws Exception {
    // kidA declares issuer-a, so resolution routes to the file. The key with no "issuer" member
    // must still be refused for that issuer.
    String jwks =
        "{\"keys\":["
            + entry("kidA", X_A, Y_A, "issuer-a")
            + ","
            + entry("kidNoIssuer", X_B, Y_B, null)
            + "]}";

    JwkProvider provider = providerFor("issuer-a", jwks);

    assertThatThrownBy(() -> provider.get("kidNoIssuer")).isInstanceOf(JwkException.class);
  }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `build/sbt "server/testOnly io.unitycatalog.server.utils.JwksOperationsTest"`
Expected: FAIL. `issuerNotDeclaredInTheFileResolvesByDiscovery` fails because the file provider is returned for every external issuer and `kidRemote` is not in the file.

- [ ] **Step 4: Write the implementation**

In `JwksOperations.loadJwkProvider`, replace the whole `if (externalJwksFile != null && !externalJwksFile.isBlank()) { ... }` block (`:109-121`, ending just before the OIDC-discovery comment) with:

```java
      // Route per issuer. The static file is authoritative only for the issuers it DECLARES (each
      // key carries an "issuer" member; see IssuerScopedJwkProvider). Anything else -- notably
      // Microsoft Entra ID, whose keys rotate and cannot live in a hand-maintained file -- is
      // resolved by OIDC discovery. Testing "does the file declare this issuer" rather than "does
      // the file exist" is what makes the two trust sources coexist in one deployment.
      if (knownIssuers().contains(issuer)) {
        Path jwksPath = Path.of(serverProperties.getExternalJwksFile());
        LOGGER.debug("Issuer '{}': resolving keys from static JWKS file '{}'", issuer, jwksPath);
        JwkProvider fileProvider =
            new JwkProviderBuilder(jwksPath.toUri().toURL()).cached(false).build();
        return new IssuerScopedJwkProvider(fileProvider, issuer);
      }

      LOGGER.debug("Issuer '{}': resolving keys by OIDC discovery", issuer);
```

`knownIssuers()` already returns an empty set when the file is unconfigured, missing, or unreadable, so the previous null and existence guards are subsumed — and a non-empty result guarantees `serverProperties` and the file are both present. Remove the now-unused local `String externalJwksFile = ...` declaration.

The two `LOGGER.debug` lines are the resolution-path logging the spec calls for: without them a typo'd `issuer` member silently reroutes to discovery with nothing in the log to say so.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `build/sbt "server/testOnly io.unitycatalog.server.utils.JwksOperationsTest"`
Expected: PASS, all tests including the two new ones.

- [ ] **Step 6: Run the full suite**

Run: `build/sbt server/test`
Expected: PASS. `AuthServiceExternalJwksTest` proves the DWSU path is intact.

- [ ] **Step 7: Commit**

```bash
git add server/src/main/java/io/unitycatalog/server/utils/JwksOperations.java \
        server/src/test/java/io/unitycatalog/server/utils/JwksOperationsTest.java \
        server/src/test/java/io/unitycatalog/server/utils/DiscoveryTestServer.java
git commit -m "feat(server): route JWKS resolution per issuer, file only for issuers it declares"
```

---

### Task 3: Harden the discovery-document fetch

The discovery call has no timeout and never checks the response status, so a slow IdP blocks a thread and a 500 surfaces as a confusing JSON parse error. Make failures honest: 503 for unreachable or non-2xx, 504 for a timeout.

**Files:**
- Modify: `server/src/main/java/io/unitycatalog/server/utils/JwksOperations.java:36` (the `webClient` field) and `:137-152` (the fetch and parse)
- Test: `server/src/test/java/io/unitycatalog/server/utils/JwksOperationsTest.java`

**Interfaces:**
- Consumes: `DiscoveryTestServer` from Task 2.
- Produces: no new signatures. `loadJwkProvider` now throws `OAuthInvalidRequestException` carrying `ErrorCode.UNAVAILABLE` (503) or `ErrorCode.DEADLINE_EXCEEDED` (504).

- [ ] **Step 1: Write the failing tests**

Add to `JwksOperationsTest.java`:

```java
  @Test
  public void discoveryNon2xxIsReportedAsUnavailable() throws Exception {
    try (DiscoveryTestServer idp = new DiscoveryTestServer("{\"keys\":[]}")) {
      idp.failDiscoveryWith(HttpStatus.INTERNAL_SERVER_ERROR);
      JwksOperations ops =
          opsForJwks("{\"keys\":[" + entry("kidLocal", X_A, Y_A, "some-other-issuer") + "]}");

      assertThatThrownBy(() -> ops.loadJwkProvider(idp.issuer()))
          .isInstanceOf(BaseException.class)
          .extracting(e -> ((BaseException) e).getErrorCode())
          .isEqualTo(ErrorCode.UNAVAILABLE);
    }
  }

  @Test
  public void discoveryOnAnUnreachableHostIsReportedAsUnavailable() throws Exception {
    // Port 1 on loopback refuses connections immediately, so this fails fast without waiting for
    // the timeout.
    JwksOperations ops =
        opsForJwks("{\"keys\":[" + entry("kidLocal", X_A, Y_A, "some-other-issuer") + "]}");

    assertThatThrownBy(() -> ops.loadJwkProvider("http://127.0.0.1:1"))
        .isInstanceOf(BaseException.class)
        .extracting(e -> ((BaseException) e).getErrorCode())
        .isEqualTo(ErrorCode.UNAVAILABLE);
  }
```

```java
  @Test
  public void discoveryTimeoutIsReportedAsDeadlineExceeded() throws Exception {
    // Takes ~5 seconds by design: the timeout is a fixed constant, so the test waits it out rather
    // than reaching into the class to shorten it.
    try (DiscoveryTestServer idp = new DiscoveryTestServer("{\"keys\":[]}")) {
      idp.delayDiscoveryBy(Duration.ofSeconds(30));
      JwksOperations ops =
          opsForJwks("{\"keys\":[" + entry("kidLocal", X_A, Y_A, "some-other-issuer") + "]}");

      assertThatThrownBy(() -> ops.loadJwkProvider(idp.issuer()))
          .isInstanceOf(BaseException.class)
          .extracting(e -> ((BaseException) e).getErrorCode())
          .isEqualTo(ErrorCode.DEADLINE_EXCEEDED);
    }
  }
```

Add these imports to the test file:

```java
import com.linecorp.armeria.common.HttpStatus;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import java.time.Duration;
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `build/sbt "server/testOnly io.unitycatalog.server.utils.JwksOperationsTest"`
Expected: FAIL. The 500 case throws a Jackson parse error rather than a `BaseException`; the unreachable and delayed cases throw a `CompletionException`.

- [ ] **Step 3: Write the implementation**

Change the `webClient` field declaration at the top of `JwksOperations`:

```java
  /**
   * Timeout for reaching a remote identity provider. Applies to the discovery document here and,
   * via {@code JwkProviderBuilder.timeouts}, to the JWKS fetch. Without it a slow IdP blocks the
   * calling thread indefinitely.
   */
  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

  private final WebClient webClient = WebClient.builder().responseTimeout(HTTP_TIMEOUT).build();
```

Replace the fetch (`String response = webClient.get(path).aggregate().join().contentUtf8();`) with:

```java
      AggregatedHttpResponse discoveryResponse;
      try {
        discoveryResponse = webClient.get(path).aggregate().join();
      } catch (CompletionException e) {
        if (e.getCause() instanceof ResponseTimeoutException) {
          throw new OAuthInvalidRequestException(
              ErrorCode.DEADLINE_EXCEEDED,
              "Timed out fetching the OIDC configuration for issuer " + issuer);
        }
        throw new OAuthInvalidRequestException(
            ErrorCode.UNAVAILABLE,
            "Could not reach the identity provider for issuer " + issuer,
            e);
      }

      if (!discoveryResponse.status().isSuccess()) {
        throw new OAuthInvalidRequestException(
            ErrorCode.UNAVAILABLE,
            String.format(
                "Identity provider returned HTTP %d for the OIDC configuration of issuer %s",
                discoveryResponse.status().code(), issuer));
      }

      String response = discoveryResponse.contentUtf8();
```

Change the existing empty-configuration check from `ErrorCode.ABORTED` to `ErrorCode.UNAVAILABLE`, since a malformed discovery document is the same class of upstream failure:

```java
      if (configMap == null || configMap.isEmpty()) {
        throw new OAuthInvalidRequestException(ErrorCode.UNAVAILABLE,
            "Could not get issuer configuration");
      }
```

Leave the `"Issuer doesn't match configuration"` and `"JWKS configuration missing"` checks on `ErrorCode.ABORTED` — those are trust/configuration mismatches, not upstream outages, and re-coding them is outside this spec.

Add these imports:

```java
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletionException;
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `build/sbt "server/testOnly io.unitycatalog.server.utils.JwksOperationsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/io/unitycatalog/server/utils/JwksOperations.java \
        server/src/test/java/io/unitycatalog/server/utils/JwksOperationsTest.java
git commit -m "fix(server): time out and status-check OIDC discovery, report outages as 503/504"
```

---

### Task 4: Stop reporting IdP outages as 401

`NetworkException extends SigningKeyNotFoundException extends JwkException`, and `GlobalExceptionHandler.java:36` maps every `JwkException` to 401 "Invalid signing key". So a failure fetching the *JWKS* — as opposed to the discovery document handled in Task 3 — still reports as a rejected token. Handle the two upstream-failure subtypes before the generic branch.

**Files:**
- Modify: `server/src/main/java/io/unitycatalog/server/exception/GlobalExceptionHandler.java:36`
- Test: `server/src/test/java/io/unitycatalog/server/exception/GlobalExceptionHandlerJwkTest.java` (create)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: no new signatures; `handleException` gains two branches.

- [ ] **Step 1: Write the failing test**

Create `server/src/test/java/io/unitycatalog/server/exception/GlobalExceptionHandlerJwkTest.java`:

```java
package io.unitycatalog.server.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.auth0.jwk.NetworkException;
import com.auth0.jwk.RateLimitReachedException;
import com.auth0.jwk.SigningKeyNotFoundException;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import org.junit.jupiter.api.Test;

/**
 * An identity provider we cannot reach is not a bad token. NetworkException extends
 * SigningKeyNotFoundException, so without explicit branches both would be caught by the generic
 * JwkException handler and reported as 401, telling the caller their token was rejected when the
 * truth is that Microsoft was unreachable.
 */
public class GlobalExceptionHandlerJwkTest {

  private HttpStatus statusFor(Throwable cause) {
    HttpResponse response =
        new GlobalExceptionHandler()
            .handleException(mock(ServiceRequestContext.class), mock(HttpRequest.class), cause);
    return response.aggregate().join().status();
  }

  @Test
  public void unknownSigningKeyIsStillUnauthorized() {
    assertThat(statusFor(new SigningKeyNotFoundException("no such kid", null)))
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  public void unreachableIdpIsServiceUnavailable() {
    assertThat(statusFor(new NetworkException("connection refused", new RuntimeException())))
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  public void rateLimitedKeyLookupIsServiceUnavailable() {
    assertThat(statusFor(new RateLimitReachedException(1000L)))
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `build/sbt "server/testOnly io.unitycatalog.server.exception.GlobalExceptionHandlerJwkTest"`
Expected: FAIL — `unreachableIdpIsServiceUnavailable` and `rateLimitedKeyLookupIsServiceUnavailable` get 401.

- [ ] **Step 3: Write the implementation**

In `GlobalExceptionHandler.handleException`, insert two branches immediately **before** the existing `else if (cause instanceof JwkException)` branch. Order matters: `NetworkException` is a subclass of `SigningKeyNotFoundException`, which is a subclass of `JwkException`, so a later branch would never be reached.

```java
    } else if (cause instanceof NetworkException) {
      // NetworkException extends SigningKeyNotFoundException, so it must be checked before the
      // generic JwkException branch below. The identity provider being unreachable is an upstream
      // outage, not a rejected token, and the caller should retry rather than discard credentials.
      return HttpResponse.ofJson(
          HttpStatus.SERVICE_UNAVAILABLE,
          createErrorResponse(
              ErrorCode.UNAVAILABLE,
              "Could not reach the identity provider to fetch signing keys.",
              cause,
              new HashMap<>()));
    } else if (cause instanceof RateLimitReachedException) {
      return HttpResponse.ofJson(
          HttpStatus.SERVICE_UNAVAILABLE,
          createErrorResponse(
              ErrorCode.UNAVAILABLE,
              "Too many signing-key lookups; retry shortly.",
              cause,
              new HashMap<>()));
    } else if (cause instanceof JwkException) {
```

Add these imports:

```java
import com.auth0.jwk.NetworkException;
import com.auth0.jwk.RateLimitReachedException;
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `build/sbt "server/testOnly io.unitycatalog.server.exception.GlobalExceptionHandlerJwkTest"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/io/unitycatalog/server/exception/GlobalExceptionHandler.java \
        server/src/test/java/io/unitycatalog/server/exception/GlobalExceptionHandlerJwkTest.java
git commit -m "fix(server): report an unreachable or rate-limited IdP as 503, not 401"
```

---

### Task 5: Cache and rate-limit the discovery path

`.cached(false)` on the remote provider plus a fresh discovery fetch per exchange puts a round trip to Microsoft on every token exchange. Cache the resolved `jwks_uri` per issuer, and build the remote provider with caching, rate limiting and timeouts. The file path stays uncached.

**Files:**
- Modify: `server/src/main/java/io/unitycatalog/server/utils/JwksOperations.java` (discovery branch and the provider built at `:164`)
- Test: `server/src/test/java/io/unitycatalog/server/utils/JwksOperationsTest.java`

**Interfaces:**
- Consumes: `DiscoveryTestServer` from Task 2.
- Produces: no new public signatures. Internally adds a private `record CachedDiscovery(String jwksUri, Instant fetchedAt)` and a `ConcurrentHashMap<String, CachedDiscovery> discoveryCache` field.

- [ ] **Step 1: Write the failing test**

Add to `JwksOperationsTest.java`:

```java
  @Test
  public void discoveryDocumentIsFetchedOncePerIssuer() throws Exception {
    try (DiscoveryTestServer idp =
        new DiscoveryTestServer("{\"keys\":[" + entry("kidRemote", X_B, Y_B, null) + "]}")) {
      JwksOperations ops =
          opsForJwks("{\"keys\":[" + entry("kidLocal", X_A, Y_A, "some-other-issuer") + "]}");

      ops.loadJwkProvider(idp.issuer()).get("kidRemote");
      ops.loadJwkProvider(idp.issuer()).get("kidRemote");
      ops.loadJwkProvider(idp.issuer()).get("kidRemote");

      assertThat(idp.discoveryHits()).isEqualTo(1);
    }
  }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `build/sbt "server/testOnly io.unitycatalog.server.utils.JwksOperationsTest"`
Expected: FAIL — `expected 1 but was 3`.

- [ ] **Step 3: Write the implementation**

Add the tuning constants and cache field near `HTTP_TIMEOUT`:

```java
  /** Remote key cache: entries and lifetime. A cache miss on an unknown kid still triggers a
   * fetch, which is what lets key rotation work; the rate limit is what stops that being abused. */
  private static final long KEY_CACHE_SIZE = 10;
  private static final long KEY_CACHE_TTL_HOURS = 24;
  private static final long RATE_LIMIT_BUCKET = 10;
  private static final long RATE_LIMIT_PER_MINUTE = 10;
  /** How long a resolved jwks_uri is reused before the discovery document is re-read. */
  private static final Duration DISCOVERY_TTL = Duration.ofHours(24);

  private record CachedDiscovery(String jwksUri, Instant fetchedAt) {}

  private final Map<String, CachedDiscovery> discoveryCache = new ConcurrentHashMap<>();
```

At the start of the discovery branch (right after the `LOGGER.debug("Issuer '{}': resolving keys by OIDC discovery", issuer)` line added in Task 2), serve from cache when fresh:

```java
      CachedDiscovery cached = discoveryCache.get(issuer);
      if (cached != null && Duration.between(cached.fetchedAt(), Instant.now()).compareTo(DISCOVERY_TTL) < 0) {
        return remoteProvider(cached.jwksUri());
      }
```

At the end of the discovery branch, replace the final `return new JwkProviderBuilder(URI.create(configJwksUri).toURL()).cached(false).build();` with:

```java
      discoveryCache.put(issuer, new CachedDiscovery(configJwksUri, Instant.now()));
      return remoteProvider(configJwksUri);
```

Add the helper method:

```java
  /**
   * A provider for a remote JWKS endpoint. Unlike the static file — which stays uncached so a newly
   * appended DWSU key takes effect without a restart — a remote provider is cached, rate limited
   * and given explicit timeouts, because it is a network dependency on every token exchange.
   */
  @SneakyThrows
  private JwkProvider remoteProvider(String jwksUri) {
    int timeoutMillis = (int) HTTP_TIMEOUT.toMillis();
    return new JwkProviderBuilder(URI.create(jwksUri).toURL())
        .cached(KEY_CACHE_SIZE, KEY_CACHE_TTL_HOURS, TimeUnit.HOURS)
        .rateLimited(RATE_LIMIT_BUCKET, RATE_LIMIT_PER_MINUTE, TimeUnit.MINUTES)
        .timeouts(timeoutMillis, timeoutMillis)
        .build();
  }
```

Add these imports:

```java
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
```

Note: `JwkProviderBuilder` in jwks-rsa 0.22.1 exposes exactly `cached(long, long, TimeUnit)`, `rateLimited(long, long, TimeUnit)` and `timeouts(int, int)` — verified against the published artifact.

- [ ] **Step 4: Run the test to verify it passes**

Run: `build/sbt "server/testOnly io.unitycatalog.server.utils.JwksOperationsTest"`
Expected: PASS, including the earlier routing tests whose `discoveryHits()` assertions still expect exactly 1.

- [ ] **Step 5: Run the full suite**

Run: `build/sbt server/test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/io/unitycatalog/server/utils/JwksOperations.java \
        server/src/test/java/io/unitycatalog/server/utils/JwksOperationsTest.java
git commit -m "perf(server): cache, rate-limit and time-bound remote JWKS resolution"
```

---

### Task 6: Tell the two principal failures apart

An Entra token without an `email` claim falls back to `sub` and fails as `User not allowed: <guid>`, which reads like an authorization decision rather than a missing optional claim. Split the message by cause without changing the fallback, which DWSU tokens legitimately rely on.

**Files:**
- Modify: `server/src/main/java/io/unitycatalog/server/service/AuthService.java:246-272` (`verifyPrincipal`)
- Test: `server/src/test/java/io/unitycatalog/server/service/AuthServicePrincipalErrorsTest.java` (create)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: no signature change. `verifyPrincipal` throws `OAuthInvalidRequestException` with one of two distinct messages.

- [ ] **Step 1: Write the failing test**

Create `server/src/test/java/io/unitycatalog/server/service/AuthServicePrincipalErrorsTest.java`. This mirrors the setup in `AuthServiceExternalJwksTest` (same `BaseServerTest` base, same RSA key generation, same JWKS builder, same form-encoded exchange), differing only in that the tokens carry a non-admin `sub` so they reach the user lookup instead of taking the `admin` shortcut:

```java
package io.unitycatalog.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import io.unitycatalog.server.base.BaseServerTest;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The two ways a principal can fail must read differently. A subject token with no "email" claim
 * falls back to "sub" -- for an Entra token that is an opaque GUID -- and used to fail as
 * "User not allowed: &lt;guid&gt;", which reads like an authorization decision rather than a missing
 * optional claim on the app registration.
 */
public class AuthServicePrincipalErrorsTest extends BaseServerTest {

  private static final String TEST_AUDIENCE = "unity-catalog";
  private static final String TOKEN_ENDPOINT = "/api/1.0/unity-control/auth/tokens";
  private static final String ISSUER = "relyt-instance-known";
  private static final String NON_ADMIN_SUB = "00000000-1111-2222-3333-444444444444";

  private WebClient client;
  private Algorithm algorithm;
  private String keyId;

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(Property.AUTHORIZATION_ENABLED.getKey(), "enable");
    serverProperties.setProperty("server.audiences", TEST_AUDIENCE);
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var keyPair = generator.generateKeyPair();
      RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
      algorithm = Algorithm.RSA512(publicKey, (RSAPrivateKey) keyPair.getPrivate());
      keyId = UUID.randomUUID().toString();
      Path jwksFile = Files.createTempFile("uc-jwks", ".json");
      Files.writeString(jwksFile, buildJwksJson(publicKey, keyId, ISSUER));
      serverProperties.setProperty("server.external-jwks-file", jwksFile.toString());
    } catch (Exception e) {
      throw new RuntimeException("Failed to set up external JWKS file", e);
    }
  }

  @BeforeEach
  @Override
  public void setUp() {
    super.setUp();
    client = WebClient.builder(serverConfig.getServerUrl()).build();
  }

  @Test
  public void tokenWithoutEmailClaimNamesTheMissingClaim() {
    AggregatedHttpResponse response = exchangeToken(tokenFor(NON_ADMIN_SUB, null));

    assertThat(response.contentUtf8()).contains("'email' claim");
    assertThat(response.contentUtf8()).contains("optional claim");
  }

  @Test
  public void tokenWithUnknownEmailSaysNotProvisioned() {
    AggregatedHttpResponse response =
        exchangeToken(tokenFor(NON_ADMIN_SUB, "nobody@example.com"));

    assertThat(response.contentUtf8()).contains("not provisioned");
    assertThat(response.contentUtf8()).contains("nobody@example.com");
  }

  /** A token signed by the registered key. The email claim is included only when non-null. */
  private String tokenFor(String subject, String email) {
    var builder =
        JWT.create()
            .withSubject(subject)
            .withIssuer(ISSUER)
            .withAudience(TEST_AUDIENCE)
            .withIssuedAt(new Date())
            .withKeyId(keyId)
            .withJWTId(UUID.randomUUID().toString());
    if (email != null) {
      builder.withClaim("email", email);
    }
    return builder.sign(algorithm);
  }

  private AggregatedHttpResponse exchangeToken(String identityToken) {
    String formBody =
        "grant_type=urn:ietf:params:oauth:grant-type:token-exchange"
            + "&requested_token_type=urn:ietf:params:oauth:token-type:access_token"
            + "&subject_token_type=urn:ietf:params:oauth:token-type:id_token"
            + "&subject_token="
            + identityToken;

    RequestHeaders headers =
        RequestHeaders.builder()
            .method(HttpMethod.POST)
            .path(TOKEN_ENDPOINT)
            .contentType(MediaType.FORM_DATA)
            .build();

    return client.execute(headers, HttpData.ofUtf8(formBody)).aggregate().join();
  }

  /** Builds a JWKS JSON string with a single RSA key carrying an {@code issuer} member. */
  private static String buildJwksJson(RSAPublicKey publicKey, String keyId, String issuer) {
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String n = encoder.encodeToString(toUnsignedBytes(publicKey.getModulus()));
    String e = encoder.encodeToString(toUnsignedBytes(publicKey.getPublicExponent()));
    return String.format(
        "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS512\",\"kid\":\"%s\","
            + "\"n\":\"%s\",\"e\":\"%s\",\"issuer\":\"%s\"}]}",
        keyId, n, e, issuer);
  }

  /** Converts a BigInteger to unsigned big-endian bytes (no leading zero padding). */
  private static byte[] toUnsignedBytes(BigInteger value) {
    byte[] bytes = value.toByteArray();
    if (bytes[0] == 0) {
      byte[] trimmed = new byte[bytes.length - 1];
      System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
      return trimmed;
    }
    return bytes;
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `build/sbt "server/testOnly io.unitycatalog.server.service.AuthServicePrincipalErrorsTest"`
Expected: FAIL — both responses currently say `User not allowed: ...`.

- [ ] **Step 3: Write the implementation**

Replace `verifyPrincipal` in `AuthService.java`:

```java
  private void verifyPrincipal(DecodedJWT decodedJWT) {
    Claim emailClaim = decodedJWT.getClaim(JwtClaim.EMAIL.key());
    boolean hasEmail = !emailClaim.isMissing() && !emailClaim.isNull();
    // The fallback to "sub" is retained: DWSU tokens legitimately rely on it. Only the error is
    // split, so a missing optional claim stops looking like a rejected user.
    String subject =
        hasEmail ? emailClaim.asString() : decodedJWT.getClaim(JwtClaim.SUBJECT.key()).asString();

    LOGGER.debug("Validating principal: {}", subject);

    if ("admin".equals(subject)) {
      LOGGER.debug("admin always allowed");
      return;
    }

    try {
      User user = userRepository.getUserByEmail(subject);
      if (user != null && user.getState() == User.StateEnum.ENABLED) {
        LOGGER.debug("Principal {} is enabled", subject);
        return;
      }
    } catch (Exception e) {
      // IGNORE
    }

    if (!hasEmail) {
      throw new OAuthInvalidRequestException(
          ErrorCode.INVALID_ARGUMENT,
          "The subject token has no 'email' claim, so the principal fell back to 'sub'. For a "
              + "Microsoft Entra ID token, add 'email' as an optional claim on the app "
              + "registration so the token carries the address the user is provisioned under.");
    }

    throw new OAuthInvalidRequestException(
        ErrorCode.INVALID_ARGUMENT, "User not provisioned: " + subject);
  }
```

Add the import `com.auth0.jwt.interfaces.Claim` if it is not already present.

Messages describe configuration only. Do not log or return any other token contents.

- [ ] **Step 4: Run the test to verify it passes**

Run: `build/sbt "server/testOnly io.unitycatalog.server.service.AuthServicePrincipalErrorsTest"`
Expected: PASS.

- [ ] **Step 5: Run the full suite**

Run: `build/sbt server/test`
Expected: PASS. Any existing test asserting the literal string `User not allowed` against the token-exchange endpoint must be updated to the new wording; `AuthDecorator` keeps its own separate `User not allowed` message and is untouched.

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/io/unitycatalog/server/service/AuthService.java \
        server/src/test/java/io/unitycatalog/server/service/AuthServicePrincipalErrorsTest.java
git commit -m "feat(server): distinguish a missing email claim from an unprovisioned user"
```

---

### Task 7: Deployment configuration

Three operator values in `uc.env` reach `server.properties`. The OAuth URLs are derived here rather than in the server, because their only consumer — the CLI — reads the rendered file directly with `java.util.Properties` and never goes through `ServerProperties`.

**Files:**
- Modify: `deploy/uc.env.example`
- Modify: `deploy/server.properties.template`
- Modify: `deploy/deploy-uc.sh` (the `export` list at `:56-57`, the Python `keys` list at `:64-66`, plus derivation, a render guard and a `--render-only` flag)

**Interfaces:**
- Consumes: `server.entra.tenant-id` as read by `ServerProperties.getEntraTenantId()` from Task 1.
- Produces: rendered `server.properties` containing `server.entra.tenant-id`, `server.client-id`, `server.client-secret`, `server.authorization-url`, `server.token-url`.

- [ ] **Step 1: Add the keys to `deploy/uc.env.example`**

Append after the existing `UC_ACCESS_TOKEN_TTL` block:

```bash
# --- Microsoft Entra ID (optional; leave the tenant blank to disable) ---
# Setting the tenant id makes the server trust https://login.microsoftonline.com/<tenant>/v2.0 and
# accept UC_CLIENT_ID as an audience, in addition to whatever the JWKS file and UC_ALLOWED_ISSUERS
# already provide. Leave blank and nothing about Entra is configured.
UC_ENTRA_TENANT_ID=
# Application (client) ID of the Entra app registration. Also the expected token audience.
UC_CLIENT_ID=
# Client secret. NOT used by the server, which only verifies signatures using public keys. It is
# used by clients that run the authorization-code flow (the CLI today, the UI later).
UC_CLIENT_SECRET=
# Optional overrides. Left blank, both are derived from UC_ENTRA_TENANT_ID.
UC_AUTHORIZATION_URL=
UC_TOKEN_URL=
```

- [ ] **Step 2: Add the placeholders to `deploy/server.properties.template`**

Replace the four currently-empty lines in the authorization block:

```
server.authorization-url=${UC_AUTHORIZATION_URL}
server.token-url=${UC_TOKEN_URL}
server.client-id=${UC_CLIENT_ID}
server.client-secret=${UC_CLIENT_SECRET}
```

And add to the "Relyt fork additions" section:

```
# Microsoft Entra ID tenant. When set, the v2.0 issuer and the client id above are unioned into the
# trusted issuers and accepted audiences. Blank = no Entra trust.
server.entra.tenant-id=${UC_ENTRA_TENANT_ID}
```

- [ ] **Step 3: Derive the URLs and extend both allow-lists in `deploy/deploy-uc.sh`**

Add before the `export` at `:56`, in the defaults section:

```bash
# Derive the Entra OAuth endpoints from the tenant id unless explicitly overridden. These are
# consumed by the CLI, which reads the rendered server.properties directly, so they must be real
# values in the file rather than derived inside the server.
if [ -n "${UC_ENTRA_TENANT_ID:-}" ]; then
  UC_AUTHORIZATION_URL="${UC_AUTHORIZATION_URL:-https://login.microsoftonline.com/${UC_ENTRA_TENANT_ID}/oauth2/v2.0/authorize}"
  UC_TOKEN_URL="${UC_TOKEN_URL:-https://login.microsoftonline.com/${UC_ENTRA_TENANT_ID}/oauth2/v2.0/token}"
fi
UC_ENTRA_TENANT_ID="${UC_ENTRA_TENANT_ID:-}"
UC_CLIENT_ID="${UC_CLIENT_ID:-}"
UC_CLIENT_SECRET="${UC_CLIENT_SECRET:-}"
UC_AUTHORIZATION_URL="${UC_AUTHORIZATION_URL:-}"
UC_TOKEN_URL="${UC_TOKEN_URL:-}"
```

Extend the `export` list at `:56-57` with the five new names:

```bash
export UC_AUTHORIZATION UC_ALLOWED_ISSUERS UC_EXTERNAL_JWKS_FILE UC_AUDIENCES UC_ACCESS_TOKEN_TTL \
       UC_ENTRA_TENANT_ID UC_CLIENT_ID UC_CLIENT_SECRET UC_AUTHORIZATION_URL UC_TOKEN_URL \
       ALIYUN_REGION ALIYUN_ACCESS_KEY ALIYUN_SECRET_KEY ALIYUN_MASTER_ROLE_ARN UC_DB_FILE
```

Extend the Python `keys` list at `:64-66` with the same five:

```python
keys = ["UC_AUTHORIZATION", "UC_ALLOWED_ISSUERS", "UC_EXTERNAL_JWKS_FILE", "UC_AUDIENCES",
        "UC_ACCESS_TOKEN_TTL", "UC_ENTRA_TENANT_ID", "UC_CLIENT_ID", "UC_CLIENT_SECRET",
        "UC_AUTHORIZATION_URL", "UC_TOKEN_URL", "ALIYUN_REGION", "ALIYUN_ACCESS_KEY",
        "ALIYUN_SECRET_KEY", "ALIYUN_MASTER_ROLE_ARN", "UC_DB_FILE"]
```

- [ ] **Step 4: Add the render guard and a `--render-only` flag**

A placeholder missing from the two lists above is not an error today — it renders as the literal text `${UC_ENTRA_TENANT_ID}` into `server.properties`, which the server then reads as a real value. Guard against that class of mistake for every key. After the two `render` calls:

```bash
# A placeholder that survives rendering means a key is missing from the lists above. Left alone it
# would be read by the server as a literal value, so fail loudly instead.
for rendered in "$UC_SERVER_PROPERTIES" "$UC_HIBERNATE_PROPERTIES"; do
  if grep -q '\${' "$rendered"; then
    echo "ERROR: unsubstituted placeholder in $rendered:" >&2
    grep -n '\${' "$rendered" >&2
    exit 1
  fi
done

if [ "${UC_RENDER_ONLY:-}" = "1" ]; then
  echo "Render-only mode: not starting the server."
  exit 0
fi
```

And parse the flag where the script handles its arguments, setting `UC_RENDER_ONLY=1` for `--render-only` and removing it from the args passed through to `start-uc-server`.

- [ ] **Step 5: Verify the render end to end**

```bash
cd deploy
cp uc.env.example /tmp/uc-entra.env
sed -i '' 's|^UC_ENTRA_TENANT_ID=.*|UC_ENTRA_TENANT_ID=11111111-2222-3333-4444-555555555555|' /tmp/uc-entra.env
sed -i '' 's|^UC_CLIENT_ID=.*|UC_CLIENT_ID=test-client-id|' /tmp/uc-entra.env
sed -i '' 's|^UC_HOME=.*|UC_HOME=/tmp/uc-entra-home|' /tmp/uc-entra.env
UC_ENV_FILE=/tmp/uc-entra.env ./deploy-uc.sh --render-only
grep -E 'entra.tenant-id|client-id|authorization-url|token-url' /tmp/uc-entra-home/etc/conf/server.properties
```

Expected: the tenant id and client id appear, `server.authorization-url` is
`https://login.microsoftonline.com/11111111-2222-3333-4444-555555555555/oauth2/v2.0/authorize`,
the token URL is the matching `/token`, the script exits 0, and no `${` remains.

- [ ] **Step 6: Verify the guard actually fires**

```bash
printf '\nserver.bogus=${UC_NOT_IN_THE_LIST}\n' >> deploy/server.properties.template
UC_ENV_FILE=/tmp/uc-entra.env ./deploy/deploy-uc.sh --render-only; echo "exit=$?"
git checkout deploy/server.properties.template
```

Expected: the script prints the offending line and exits 1. Then the template is restored.

- [ ] **Step 7: Verify nothing changed for a deployment without Entra**

```bash
cp deploy/uc.env.example /tmp/uc-plain.env
sed -i '' 's|^UC_HOME=.*|UC_HOME=/tmp/uc-plain-home|' /tmp/uc-plain.env
UC_ENV_FILE=/tmp/uc-plain.env ./deploy/deploy-uc.sh --render-only
grep -E 'allowed-issuers|audiences|entra' /tmp/uc-plain-home/etc/conf/server.properties
```

Expected: `server.entra.tenant-id=` is blank, the URLs are blank, and the issuer/audience lines are exactly as before this change.

- [ ] **Step 8: Commit**

```bash
git add deploy/uc.env.example deploy/server.properties.template deploy/deploy-uc.sh
git commit -m "feat(deploy): configure Entra with three values and fail on unsubstituted placeholders"
```

---

### Task 8: Operator documentation

**Files:**
- Modify: `deploy/README.md` (new section after "Onboarding a new DWSU (hot, no restart)" at `:194`)
- Modify: `features.md`

**Interfaces:**
- Consumes: the config keys from Task 7 and the behavior from Tasks 1–6.
- Produces: no code.

- [ ] **Step 1: Write the Entra section in `deploy/README.md`**

Add a `## Microsoft Entra ID sign-in` section covering, concretely:

1. **App registration:** create one in the target tenant; note the Directory (tenant) ID and Application (client) ID; create a client secret.
2. **The `email` optional claim is required.** Under Token configuration, add `email` as an optional claim for the ID token. Without it the token carries only `sub`/`preferred_username`, the server falls back to `sub`, and every user is rejected — the error from Task 6 says exactly this.
3. **Users must already exist in UC.** Provision them via SCIM with the same address the `email` claim carries. There is no JIT provisioning.
4. **Set the three values** in `uc.env` and restart. State that `UC_ALLOWED_ISSUERS` does *not* need the Entra issuer — it is derived from the tenant id.
5. **Coexistence:** the static JWKS file keeps serving the issuers it declares; Entra resolves by discovery. Both work in one deployment, and the DWSU hot-onboarding flow is unchanged.
6. **Network requirement:** outbound HTTPS to `login.microsoftonline.com`. If it is unreachable the token exchange returns 503, not 401.

- [ ] **Step 2: Add the `features.md` entry**

Follow the existing format exactly — a date heading, the PR link, then `features:` and `bugfix:` bullets:

```markdown
## <merge date>

#<PR> (https://github.com/relytcloud/unitycatalog/pull/<PR>)

- features: Microsoft Entra ID as a token-exchange issuer. JWKS resolution is now per-issuer — the static JWKS file serves the issuers it declares, everything else resolves by OIDC discovery (cached, rate-limited, timed out) — so a DWSU deployment and an Entra tenant coexist. Configured with three values: tenant id, client id, client secret.
- bugfix: An unreachable or rate-limited identity provider is reported as 503 instead of a misleading 401 "Invalid signing key"; a subject token missing the `email` claim now says so instead of failing as `User not allowed: <guid>`.
```

- [ ] **Step 3: Commit**

```bash
git add deploy/README.md features.md
git commit -m "docs(deploy): document Entra ID setup and record the feature"
```

---

## Manual acceptance

Automated tests cannot prove that a real app registration emits the `email` claim. Before calling this done, against a real tenant:

1. Set the three values in `uc.env` and start the server.
2. Provision a UC user whose email matches the tenant user's.
3. Obtain an Entra ID token for that user and POST it to `/api/1.0/unity-control/auth/tokens` with `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`, `subject_token_type=urn:ietf:params:oauth:token-type:id_token`.
4. Expect a UC access token back, and confirm it works on a normal API call.
5. Confirm the server log shows `resolving keys by OIDC discovery` for the Entra issuer and that a second exchange does not re-fetch the discovery document.

If the CLI is used for step 3 rather than a raw token: Entra requires an exact redirect-URI match for confidential clients, while `Oauth2CliExchange.findAvailablePort()` falls back to a random port whenever `server.redirect-port` is blank. The CLI does honour that property when it is set; what the deploy path did not do was render it. Setting it (and registering the matching `http://localhost:<port>`) is recorded in the spec as a known follow-up and is **not** part of this plan.
