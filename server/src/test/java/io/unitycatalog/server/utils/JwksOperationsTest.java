package io.unitycatalog.server.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.linecorp.armeria.common.HttpStatus;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.security.SecurityContext;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the static external JWKS provider binds each key to its issuer: a key registered
 * for one issuer must not be usable to verify a token claiming a different issuer (confused-issuer
 * protection), and a key with no {@code issuer} member is rejected outright.
 */
public class JwksOperationsTest {

  // Two real P-256 public points (only used as well-formed JWK material; signatures are not
  // verified in this test).
  private static final String X_A = "C8H9oDGZDEKZQ70-zxSiq0z6SdnYwgMLKdAs2xvMbfU";
  private static final String Y_A = "SwxfO-dr60Ugf3IFFazvgxDdBKqDheZYrL0Bk6-76S0";
  private static final String X_B = "In1ki6Yd2aDFZKuXtdteEjs82L4zh_OFlAgfSvCUQeI";
  private static final String Y_B = "9cSK-EecpGX0IDbvUwlcOr72EYWMx4u4c9DHI1DvjGQ";

  private static String entry(String kid, String x, String y, String issuer) {
    String base =
        String.format(
            "{\"kty\":\"EC\",\"crv\":\"P-256\",\"kid\":\"%s\",\"use\":\"sig\",\"alg\":\"ES256\","
                + "\"x\":\"%s\",\"y\":\"%s\"",
            kid, x, y);
    return issuer == null ? base + "}" : base + String.format(",\"issuer\":\"%s\"}", issuer);
  }

  private JwkProvider providerFor(String requestedIssuer, String jwksJson) throws Exception {
    Path jwksFile = Files.createTempFile("jwks", ".json");
    Files.writeString(jwksFile, jwksJson);
    ServerProperties serverProperties = mock(ServerProperties.class);
    when(serverProperties.getExternalJwksFile()).thenReturn(jwksFile.toString());
    JwksOperations ops = new JwksOperations(mock(SecurityContext.class), serverProperties);
    return ops.loadJwkProvider(requestedIssuer);
  }

  @Test
  public void keyIsUsableOnlyForItsRegisteredIssuer() throws Exception {
    String jwks =
        "{\"keys\":["
            + entry("kidA", X_A, Y_A, "issuer-a")
            + ","
            + entry("kidB", X_B, Y_B, "issuer-b")
            + "]}";

    JwkProvider providerA = providerFor("issuer-a", jwks);

    // The key registered for issuer-a is returned for issuer-a.
    assertThat(providerA.get("kidA").getId()).isEqualTo("kidA");

    // issuer-b's key must NOT be accepted when verifying a token claiming issuer-a.
    assertThatThrownBy(() -> providerA.get("kidB")).isInstanceOf(JwkException.class);
  }

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

  private JwksOperations opsForJwks(String jwksJson) throws Exception {
    Path jwksFile = Files.createTempFile("jwks", ".json");
    Files.writeString(jwksFile, jwksJson);
    ServerProperties serverProperties = mock(ServerProperties.class);
    when(serverProperties.getExternalJwksFile()).thenReturn(jwksFile.toString());
    return new JwksOperations(mock(SecurityContext.class), serverProperties);
  }

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

  @Test
  public void malformedDiscoveryDocumentIsReportedAsUnavailable() throws Exception {
    // A 200 whose body is not JSON at all (a captive portal or proxy error page). The Jackson
    // parse failure must become an upstream-failure 503, not a bodyless 500.
    try (DiscoveryTestServer idp = new DiscoveryTestServer("{\"keys\":[]}")) {
      idp.serveDiscoveryBody("<html><body>not json</body></html>");
      JwksOperations ops =
          opsForJwks("{\"keys\":[" + entry("kidLocal", X_A, Y_A, "some-other-issuer") + "]}");

      assertThatThrownBy(() -> ops.loadJwkProvider(idp.issuer()))
          .isInstanceOf(BaseException.class)
          .extracting(e -> ((BaseException) e).getErrorCode())
          .isEqualTo(ErrorCode.UNAVAILABLE);
    }
  }

  @Test
  public void discoveryDocumentWithoutIssuerMemberIsRejected() throws Exception {
    // A well-formed JSON object with no "issuer" member used to NPE on the null cast result.
    try (DiscoveryTestServer idp = new DiscoveryTestServer("{\"keys\":[]}")) {
      idp.serveDiscoveryBody("{\"jwks_uri\":\"" + idp.issuer() + "/keys\"}");
      JwksOperations ops =
          opsForJwks("{\"keys\":[" + entry("kidLocal", X_A, Y_A, "some-other-issuer") + "]}");

      assertThatThrownBy(() -> ops.loadJwkProvider(idp.issuer()))
          .isInstanceOf(BaseException.class)
          .extracting(e -> ((BaseException) e).getErrorCode())
          .isEqualTo(ErrorCode.ABORTED);
    }
  }

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
      assertThat(idp.jwksHits()).isEqualTo(1);
    }
  }

  @Test
  public void jwksUriWithANonHttpsSchemeIsRejected() throws Exception {
    // UrlJwkProvider opens whatever URL it is handed. A discovery document that points the key
    // fetch at the local filesystem (or ftp, or a cloud metadata address) must never be built
    // into a provider, let alone cached for a day.
    try (DiscoveryTestServer idp = new DiscoveryTestServer("{\"keys\":[]}")) {
      idp.serveDiscoveryBody(
          "{\"issuer\":\"" + idp.issuer() + "\",\"jwks_uri\":\"file:///etc/passwd\"}");
      JwksOperations ops =
          opsForJwks("{\"keys\":[" + entry("kidLocal", X_A, Y_A, "some-other-issuer") + "]}");

      assertThatThrownBy(() -> ops.loadJwkProvider(idp.issuer()))
          .isInstanceOf(BaseException.class)
          .hasMessageContaining("unsupported scheme 'file'")
          .extracting(e -> ((BaseException) e).getErrorCode())
          .isEqualTo(ErrorCode.UNAVAILABLE);
    }
  }

  @Test
  public void relativeJwksUriIsRejected() throws Exception {
    // The commonest misconfigured-IdP output. URI.create("/keys").toURL() throws
    // IllegalArgumentException, which matches no exception-handler branch and surfaced as a 500.
    try (DiscoveryTestServer idp = new DiscoveryTestServer("{\"keys\":[]}")) {
      idp.serveDiscoveryBody("{\"issuer\":\"" + idp.issuer() + "\",\"jwks_uri\":\"/keys\"}");
      JwksOperations ops =
          opsForJwks("{\"keys\":[" + entry("kidLocal", X_A, Y_A, "some-other-issuer") + "]}");

      assertThatThrownBy(() -> ops.loadJwkProvider(idp.issuer()))
          .isInstanceOf(BaseException.class)
          .extracting(e -> ((BaseException) e).getErrorCode())
          .isEqualTo(ErrorCode.UNAVAILABLE);
    }
  }

  @Test
  public void nonStringIssuerMemberIsRejected() throws Exception {
    // A JSON number where a string belongs used to reach a (String) cast: the ClassCastException
    // matched no handler branch and surfaced as a bodyless 500.
    try (DiscoveryTestServer idp = new DiscoveryTestServer("{\"keys\":[]}")) {
      idp.serveDiscoveryBody("{\"issuer\":123,\"jwks_uri\":\"" + idp.issuer() + "/keys\"}");
      JwksOperations ops =
          opsForJwks("{\"keys\":[" + entry("kidLocal", X_A, Y_A, "some-other-issuer") + "]}");

      assertThatThrownBy(() -> ops.loadJwkProvider(idp.issuer()))
          .isInstanceOf(BaseException.class)
          .extracting(e -> ((BaseException) e).getErrorCode())
          .isEqualTo(ErrorCode.UNAVAILABLE);
    }
  }

  @Test
  public void jwksUriPointingAtTheCloudMetadataAddressIsRejected() throws Exception {
    // https alone does not close the hole: 169.254.169.254 is the cloud metadata endpoint, and
    // whether the fetch succeeded is observable through the 401-vs-503 split.
    assertJwksUriRejected("https://169.254.169.254/latest/meta-data/", "169.254.169.254");
  }

  @Test
  public void jwksUriPointingAtAPrivateRangeIsRejected() throws Exception {
    assertJwksUriRejected("https://10.1.2.3/keys", "10.1.2.3");
    assertJwksUriRejected("https://192.168.1.1/keys", "192.168.1.1");
    assertJwksUriRejected("https://172.16.0.1/keys", "172.16.0.1");
  }

  @Test
  public void jwksUriUsingTheDecimalSpellingOfLoopbackIsRejected() throws Exception {
    // 2130706433 is 127.0.0.1 written as a single decimal, which InetAddress and URL.openConnection
    // both read as loopback. A dotted-quad-only check would wave it through as a DNS name.
    assertJwksUriRejected("https://2130706433/keys", "2130706433");
  }

  @Test
  public void plainHttpJwksUriToANonLoopbackHostIsRejected() throws Exception {
    // http is tolerated only for a local test IdP.
    assertJwksUriRejected("http://keys.example.test/keys", "keys.example.test");
  }

  /**
   * Serves a discovery document carrying this jwks_uri and asserts it is refused as an upstream
   * failure, without the URL's host reaching the caller-visible message.
   */
  private void assertJwksUriRejected(String jwksUri, String hostThatMustNotLeak) throws Exception {
    try (DiscoveryTestServer idp = new DiscoveryTestServer("{\"keys\":[]}")) {
      idp.serveDiscoveryBody(
          "{\"issuer\":\"" + idp.issuer() + "\",\"jwks_uri\":\"" + jwksUri + "\"}");
      JwksOperations ops =
          opsForJwks("{\"keys\":[" + entry("kidLocal", X_A, Y_A, "some-other-issuer") + "]}");

      assertThatThrownBy(() -> ops.loadJwkProvider(idp.issuer()))
          .isInstanceOf(BaseException.class)
          .hasMessageNotContaining(hostThatMustNotLeak)
          .extracting(e -> ((BaseException) e).getErrorCode())
          .isEqualTo(ErrorCode.UNAVAILABLE);
    }
  }

  @Test
  public void remoteKeyLookupsAllowATenBurstAndRefillAtTenPerMinute() throws Exception {
    // JwkProviderBuilder.rateLimited's middle argument is the refill PERIOD for one token, not a
    // count per unit: BucketImpl.getRatePerToken() returns unit.toMillis(rate). The previous
    // (10, 10, MINUTES) therefore meant one token every ten minutes, so ~10 unauthenticated
    // requests carrying junk 'kid's could drain the bucket and keep every uncached key lookup
    // failing for 100 minutes. Draining the bucket and reading its refill delay pins the unit
    // without sleeping.
    JwksOperations ops = opsForJwks("{\"keys\":[]}");
    JwkProvider provider = ops.remoteProvider("https://idp.example/keys", "https://idp.example");
    Object bucket = rateLimitBucketOf(provider);

    for (int i = 1; i <= 10; i++) {
      assertThat(consume(bucket)).as("burst token %d of 10", i).isTrue();
    }
    assertThat(consume(bucket)).as("11th lookup within the burst").isFalse();

    // One token per 6000ms is ten lookups per minute; the configuration this replaces would be
    // ~600000, which is what the upper bound catches.
    assertThat(willLeakIn(bucket)).isBetween(5_000L, 6_000L);
  }

  /**
   * jwks-rsa's chain is cache -&gt; rate limiter -&gt; URL provider. The bucket, and the Bucket
   * type itself, are package-private to com.auth0.jwk, so the configuration can only be observed
   * reflectively.
   */
  private static Object rateLimitBucketOf(JwkProvider cachedProvider) throws Exception {
    Method getBaseProvider = cachedProvider.getClass().getDeclaredMethod("getBaseProvider");
    getBaseProvider.setAccessible(true);
    Object rateLimited = getBaseProvider.invoke(cachedProvider);
    Field bucketField = rateLimited.getClass().getDeclaredField("bucket");
    bucketField.setAccessible(true);
    return bucketField.get(rateLimited);
  }

  /** Takes one token, as a key lookup would. */
  private static boolean consume(Object bucket) throws Exception {
    Method consume = bucket.getClass().getDeclaredMethod("consume");
    consume.setAccessible(true);
    return (boolean) consume.invoke(bucket);
  }

  /** Milliseconds until one more token is available. */
  private static long willLeakIn(Object bucket) throws Exception {
    Method willLeakIn = bucket.getClass().getDeclaredMethod("willLeakIn", long.class);
    willLeakIn.setAccessible(true);
    return (long) willLeakIn.invoke(bucket, 1L);
  }

  @Test
  public void knownIssuersReturnsDistinctIssuersAcrossKeys() throws Exception {
    // Two issuers, and a second key for issuer-a: the result is the deduplicated set of issuers.
    String jwks =
        "{\"keys\":["
            + entry("kidA1", X_A, Y_A, "issuer-a")
            + ","
            + entry("kidA2", X_B, Y_B, "issuer-a")
            + ","
            + entry("kidB", X_B, Y_B, "issuer-b")
            + "]}";

    assertThat(opsForJwks(jwks).knownIssuers()).containsExactlyInAnyOrder("issuer-a", "issuer-b");
  }

  @Test
  public void knownIssuersIgnoresKeysWithoutIssuerMember() throws Exception {
    // A key with no "issuer" member cannot be used to verify any token (see
    // keyWithoutIssuerMemberIsRejected) and must not contribute a trusted issuer either.
    String jwks =
        "{\"keys\":["
            + entry("kidA", X_A, Y_A, "issuer-a")
            + ","
            + entry("kidNoIssuer", X_B, Y_B, null)
            + "]}";

    assertThat(opsForJwks(jwks).knownIssuers()).containsExactlyInAnyOrder("issuer-a");
  }

  @Test
  public void knownIssuersEmptyForEmptyKeyArray() throws Exception {
    assertThat(opsForJwks("{\"keys\":[]}").knownIssuers()).isEmpty();
  }

  @Test
  public void knownIssuersEmptyWhenFileMissing() {
    // Fail-closed: a configured-but-absent file yields no trusted issuers rather than throwing.
    ServerProperties serverProperties = mock(ServerProperties.class);
    when(serverProperties.getExternalJwksFile()).thenReturn("/no/such/uc-jwks-file.json");
    JwksOperations ops = new JwksOperations(mock(SecurityContext.class), serverProperties);

    assertThat(ops.knownIssuers()).isEmpty();
  }

  @Test
  public void knownIssuersEmptyWhenNotConfigured() {
    ServerProperties serverProperties = mock(ServerProperties.class);
    when(serverProperties.getExternalJwksFile()).thenReturn(null);
    JwksOperations ops = new JwksOperations(mock(SecurityContext.class), serverProperties);

    assertThat(ops.knownIssuers()).isEmpty();
  }
}
