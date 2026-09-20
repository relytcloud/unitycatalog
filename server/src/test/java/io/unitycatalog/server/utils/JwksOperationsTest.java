package io.unitycatalog.server.utils;

import static io.unitycatalog.server.security.SecurityContext.Issuers.INTERNAL;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
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
  public void concurrentColdStartCallersShareASingleDiscoveryFetch() throws Exception {
    // A cold cache plus N concurrent token exchanges used to mean N discovery fetches, each
    // pinning an Armeria blocking thread for up to the 5-second timeout. That is the cold start,
    // and -- because a failed discovery is deliberately never cached -- it is also every request
    // for the whole duration of an identity-provider outage.
    try (DiscoveryTestServer idp =
        new DiscoveryTestServer("{\"keys\":[" + entry("kidRemote", X_B, Y_B, null) + "]}")) {
      JwksOperations ops =
          opsForJwks("{\"keys\":[" + entry("kidLocal", X_A, Y_A, "some-other-issuer") + "]}");
      CountDownLatch firstFetchArrived = new CountDownLatch(1);
      CompletableFuture<Void> releaseDiscovery = idp.gateDiscoveryOn(firstFetchArrived);

      int callers = 8;
      ExecutorService pool = Executors.newFixedThreadPool(callers);
      List<Future<JwkProvider>> results = new ArrayList<>();
      try {
        // The first caller's request reaches the (now blocked) IdP before any other caller
        // starts, so the fetch is provably in flight rather than merely likely to be: no sleep,
        // and no dependence on how the threads happen to be scheduled.
        results.add(pool.submit(() -> ops.loadJwkProvider(idp.issuer())));
        assertThat(firstFetchArrived.await(30, TimeUnit.SECONDS)).isTrue();
        for (int i = 1; i < callers; i++) {
          results.add(pool.submit(() -> ops.loadJwkProvider(idp.issuer())));
        }
        releaseDiscovery.complete(null);

        for (Future<JwkProvider> result : results) {
          assertThat(result.get(30, TimeUnit.SECONDS).get("kidRemote").getId())
              .isEqualTo("kidRemote");
        }
      } finally {
        pool.shutdownNow();
      }

      // Every caller either performed the one fetch or took its result: whether a peer waited on
      // the lock or arrived after the cache was populated, the fetch count is the same.
      assertThat(idp.discoveryHits()).isEqualTo(1);
      assertThat(idp.jwksHits()).isEqualTo(1);
    }
  }

  @Test
  public void failedDiscoveryIsRetriedRatherThanCached() throws Exception {
    // Failures must never be cached: /tokens is unauthenticated, so negative caching would let
    // anyone turn a momentary upstream blip into a local outage lasting DISCOVERY_TTL. The
    // single-flight lock must not become a negative cache either -- it is released with nothing
    // written, so the next request retries.
    try (DiscoveryTestServer idp =
        new DiscoveryTestServer("{\"keys\":[" + entry("kidRemote", X_B, Y_B, null) + "]}")) {
      idp.failDiscoveryWith(HttpStatus.SERVICE_UNAVAILABLE);
      JwksOperations ops =
          opsForJwks("{\"keys\":[" + entry("kidLocal", X_A, Y_A, "some-other-issuer") + "]}");

      assertThatThrownBy(() -> ops.loadJwkProvider(idp.issuer()))
          .isInstanceOf(BaseException.class)
          .extracting(e -> ((BaseException) e).getErrorCode())
          .isEqualTo(ErrorCode.UNAVAILABLE);

      idp.failDiscoveryWith(HttpStatus.OK);

      assertThat(ops.loadJwkProvider(idp.issuer()).get("kidRemote").getId()).isEqualTo("kidRemote");
      assertThat(idp.discoveryHits()).isEqualTo(2);
    }
  }

  @Test
  public void theDiscoveryInfoIsEmittedOnlyOnASuccessfulResolution() throws Exception {
    // The one place in this class where a log line IS the behaviour under test, so the log is
    // what the test reads. Before this, the INFO sat ahead of the fetch and announced an attempt:
    // a failed discovery is never cached, so for the whole of an identity-provider outage it
    // fired on every single exchange -- the storm the WARN cooldown exists to stop, one level
    // quieter. Past the cache put it is once per issuer per cache window in every condition.
    try (DiscoveryTestServer idp =
        new DiscoveryTestServer("{\"keys\":[" + entry("kidRemote", X_B, Y_B, null) + "]}")) {
      idp.failDiscoveryWith(HttpStatus.SERVICE_UNAVAILABLE);
      JwksOperations ops =
          opsForJwks("{\"keys\":[" + entry("kidLocal", X_A, Y_A, "some-other-issuer") + "]}");

      try (CapturedLog log = CapturedLog.of(JwksOperations.class)) {
        for (int attempt = 0; attempt < 3; attempt++) {
          assertThatThrownBy(() -> ops.loadJwkProvider(idp.issuer()))
              .isInstanceOf(BaseException.class);
        }

        // Three failed exchanges, no INFO. The throttled WARN is what reports the outage.
        assertThat(log.infoMessagesMentioning(idp.issuer())).isEmpty();

        idp.failDiscoveryWith(HttpStatus.OK);
        ops.loadJwkProvider(idp.issuer());
        ops.loadJwkProvider(idp.issuer());

        // One line for the resolution that actually happened; the second call is a cache hit.
        assertThat(log.infoMessagesMentioning(idp.issuer())).hasSize(1);
        assertThat(log.infoMessagesMentioning(idp.issuer()).get(0))
            .contains("resolved signing keys by OIDC discovery");
      }
    }
  }

  /**
   * Captures one logger's events for the duration of a test, at INFO, and restores the logger on
   * close. Events are filtered by a caller-supplied needle -- here the test's own issuer, which
   * carries a port unique to its DiscoveryTestServer -- so a test class running beside this one
   * cannot pollute the assertion.
   */
  private static final class CapturedLog implements AutoCloseable {

    private final org.apache.logging.log4j.core.Logger logger;
    private final Level previousLevel;
    private final CollectingAppender appender;

    private CapturedLog(
        org.apache.logging.log4j.core.Logger logger,
        Level previousLevel,
        CollectingAppender appender) {
      this.logger = logger;
      this.previousLevel = previousLevel;
      this.appender = appender;
    }

    static CapturedLog of(Class<?> type) {
      String name = type.getName();
      LoggerContext context = (LoggerContext) LogManager.getContext(false);
      org.apache.logging.log4j.core.Logger logger = context.getLogger(name);
      Level previousLevel = logger.getLevel();
      // Set the level BEFORE attaching: Configurator.setLevel may install a fresh LoggerConfig
      // for this name, which would drop an appender added first. Set explicitly so the test does
      // not depend on whatever ambient level the suite happens to run under.
      Configurator.setLevel(name, Level.INFO);
      CollectingAppender appender = new CollectingAppender();
      appender.start();
      logger.addAppender(appender);
      return new CapturedLog(logger, previousLevel, appender);
    }

    List<String> infoMessagesMentioning(String needle) {
      return messagesMentioning(Level.INFO, needle);
    }

    List<String> messagesMentioning(Level level, String needle) {
      return appender.messagesAt(level).stream().filter(m -> m.contains(needle)).toList();
    }

    @Override
    public void close() {
      logger.removeAppender(appender);
      appender.stop();
      Configurator.setLevel(logger.getName(), previousLevel);
    }
  }

  private static final class CollectingAppender extends AbstractAppender {

    private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

    CollectingAppender() {
      super("uc-test-capture", null, null, true, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
      // Immutable copy: log4j reuses the mutable event instance after this returns.
      events.add(event.toImmutable());
    }

    List<String> messagesAt(Level level) {
      synchronized (events) {
        return events.stream()
            .filter(event -> level.equals(event.getLevel()))
            .map(event -> event.getMessage().getFormattedMessage())
            .toList();
      }
    }
  }

  @Test
  public void theLogCooldownAllowsOneLinePerKeyPerWindow() {
    // What keeps an outage to one WARN per issuer per minute instead of one per token exchange.
    JwksOperations.LogCooldown cooldown = new JwksOperations.LogCooldown(Duration.ofMinutes(1));

    assertThat(cooldown.allow("issuer-a")).isTrue();
    assertThat(cooldown.allow("issuer-a")).isFalse();
    // Per issuer: one provider's outage must not silence another provider's first failure.
    assertThat(cooldown.allow("issuer-b")).isTrue();
  }

  @Test
  public void theLogCooldownIsAWindowNotAOneShot() {
    // A zero-length window is the boundary case of "the cooldown has elapsed". Getting this
    // wrong would silence an issuer permanently after its first failure, which is worse than the
    // storm the throttle exists to stop. Expressed as zero rather than a sleep so it cannot flake.
    JwksOperations.LogCooldown cooldown = new JwksOperations.LogCooldown(Duration.ZERO);

    assertThat(cooldown.allow("issuer-a")).isTrue();
    assertThat(cooldown.allow("issuer-a")).isTrue();
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
  public void jwksUriWithATrailingDotOnTheHostIsRejected() throws Exception {
    // One character was the whole bypass. "localhost." is the same name as "localhost" -- the dot
    // is the DNS root written out, and InetAddress.getByName resolves it to 127.0.0.1 -- but it
    // equals neither "localhost" nor anything ending ".localhost", so it walked straight through
    // a check that named both. Anything reached by name can be spelt this way.
    assertJwksUriRejected("https://localhost./keys", "localhost");
    assertJwksUriRejected("https://LOCALHOST./keys", "LOCALHOST");
    assertJwksUriRejected("https://anything.localhost./keys", "anything.localhost");
  }

  @Test
  public void jwksUriInARangeThatReachesSomethingInternalIsRejected() throws Exception {
    // Ranges none of InetAddress's own predicates answer for, every one of which routes to
    // something inside a real deployment.
    assertJwksUriRejected("https://100.64.0.1/keys", "100.64.0.1"); // carrier-grade NAT
    assertJwksUriRejected("https://100.127.255.254/keys", "100.127.255.254"); // ..to the end of /10
    assertJwksUriRejected("https://192.0.0.1/keys", "192.0.0.1"); // IETF protocol assignments
    assertJwksUriRejected("https://198.18.0.1/keys", "198.18.0.1"); // benchmarking
    assertJwksUriRejected("https://198.19.255.255/keys", "198.19.255.255"); // ..to the end of /15
    assertJwksUriRejected("https://224.0.0.1/keys", "224.0.0.1"); // multicast
    assertJwksUriRejected("https://[ff02::1]/keys", "ff02::1"); // all-nodes multicast
  }

  @Test
  public void jwksUriUsingAnIpv6FormThatEmbedsAnInternalIpv4AddressIsRejected() throws Exception {
    // 64:ff9b::/96 is the well-known NAT64 prefix: a translator on the path forwards it to the
    // IPv4 address in the low 32 bits, so [64:ff9b::7f00:1] is a way of writing 127.0.0.1 that
    // isLoopbackAddress() says nothing about. ::/96 is the same trick in the deprecated
    // IPv4-compatible form.
    assertJwksUriRejected("https://[64:ff9b::7f00:1]/keys", "64:ff9b"); // 127.0.0.1
    assertJwksUriRejected("https://[64:ff9b::a9fe:a9fe]/keys", "64:ff9b"); // 169.254.169.254
    assertJwksUriRejected("https://[::7f00:1]/keys", "7f00"); // 127.0.0.1, IPv4-compatible
  }

  @Test
  public void anIpv6FormEmbeddingAPublicAddressIsStillAllowed() throws Exception {
    // The embedded-address rule classifies what is embedded; it does not reject the whole prefix.
    // Without this, "reject 64:ff9b::/96" and "reject the loopback inside it" are the same test.
    assertThat(JwksOperations.validatedJwksUrl("https://[64:ff9b::808:808]/keys", "iss", false))
        .hasToString("https://[64:ff9b::808:808]/keys"); // 8.8.8.8
  }

  @Test
  public void plainHttpIsRejectedWithoutTheLoopbackTestFlagEvenOnLoopback() throws Exception {
    // The exception for a local test IdP was on by default, gated by nothing but a javadoc. That
    // makes a trusted-but-compromised identity provider into a loopback port scanner: it publishes
    // http://127.0.0.1:<port>/ as its jwks_uri and reads the answer off the 401-vs-503 split.
    // Asserted by passing the flag's value in, so that the production rule is tested in a JVM
    // whose system property the build sets the other way.
    for (String uri :
        List.of(
            "http://127.0.0.1:9999/keys",
            "http://localhost:9999/keys",
            "http://localhost./keys",
            "http://[::1]:9999/keys")) {
      assertThatThrownBy(() -> JwksOperations.validatedJwksUrl(uri, "https://idp.example", false))
          .as(uri)
          .isInstanceOf(BaseException.class)
          .hasMessageContaining("only https is accepted")
          .extracting(e -> ((BaseException) e).getErrorCode())
          .isEqualTo(ErrorCode.UNAVAILABLE);
    }

    // And with the flag on it is admitted, which is what keeps DiscoveryTestServer working.
    assertThat(JwksOperations.validatedJwksUrl("http://127.0.0.1:9999/keys", "iss", true))
        .hasToString("http://127.0.0.1:9999/keys");
  }

  @Test
  public void theLoopbackExceptionIsOffUnlessThePropertyIsExactlyTrue() {
    // The default is what governs every real deployment, so it is the part worth pinning: absent,
    // empty, "1", "yes" and "TRUE" must not be read as agreement with anything but the last.
    assertThat(JwksOperations.plainHttpLoopbackAllowed(null)).isFalse();
    assertThat(JwksOperations.plainHttpLoopbackAllowed("")).isFalse();
    assertThat(JwksOperations.plainHttpLoopbackAllowed("1")).isFalse();
    assertThat(JwksOperations.plainHttpLoopbackAllowed("yes")).isFalse();
    assertThat(JwksOperations.plainHttpLoopbackAllowed("false")).isFalse();
    assertThat(JwksOperations.plainHttpLoopbackAllowed("true")).isTrue();
    assertThat(JwksOperations.plainHttpLoopbackAllowed("TRUE")).isTrue();
  }

  @Test
  public void anOrdinaryPublicHttpsJwksUriIsStillAccepted() throws Exception {
    // The other half of every rejection above: a guard that refuses everything is not a guard.
    assertThat(JwksOperations.validatedJwksUrl("https://login.example/keys", "iss", false))
        .hasToString("https://login.example/keys");
    assertThat(JwksOperations.validatedJwksUrl("https://8.8.8.8/keys", "iss", false))
        .hasToString("https://8.8.8.8/keys");
    // A name that merely CONTAINS localhost is not localhost.
    assertThat(JwksOperations.validatedJwksUrl("https://notlocalhost.example/k", "iss", false))
        .hasToString("https://notlocalhost.example/k");
  }

  @Test
  public void theLocalKeyFileErrorIsThrottledLikeItsRemoteTwin() throws Exception {
    // AuthDecorator resolves the INTERNAL issuer on every authenticated route, and /tokens
    // reaches the same code with no credentials at all, so a certs.json that is missing or
    // unreadable fails EVERY request. The remote twin of this branch has been throttled from the
    // start; this one logged an ERROR, with a stack trace, once per request -- a log-volume hole
    // any caller could hold open, burying the one line an operator needs to read.
    Path missing = Path.of("/no/such/dir/uc-certs-throttle-" + System.nanoTime() + ".json");
    SecurityContext securityContext = mock(SecurityContext.class);
    when(securityContext.getCertsFile()).thenReturn(missing);
    JwksOperations ops = new JwksOperations(securityContext);

    try (CapturedLog log = CapturedLog.of(JwksOperations.class)) {
      for (int request = 0; request < 5; request++) {
        assertThatThrownBy(
                () -> ops.verifierForIssuerAndKey(INTERNAL, "any-kid", "RS256", List.of()))
            .isInstanceOf(BaseException.class);
      }

      // Five failed requests, one line. The failure itself is still reported every time -- the
      // caller gets a 500 each time -- it is only the logging that is once per issuer per window.
      assertThat(log.messagesMentioning(Level.ERROR, missing.toString())).hasSize(1);
    }
  }

  @Test
  public void theExternalJwksFileWarningsAreThrottledToo() throws Exception {
    // The sibling of the throttle above, and on an even hotter path: AuthService calls
    // knownIssuers() to derive the trusted issuers on EVERY token exchange, before it will look
    // at the token, and /tokens is unauthenticated. Both of its failure branches -- the file is
    // not there, the file does not parse -- describe a state that persists, so an ungated line in
    // either is one WARN per request for as long as the file stays wrong.
    Path missing = Path.of("/no/such/dir/uc-jwks-missing-" + System.nanoTime() + ".json");
    ServerProperties missingProps = mock(ServerProperties.class);
    when(missingProps.getExternalJwksFile()).thenReturn(missing.toString());
    JwksOperations missingFileOps = new JwksOperations(mock(SecurityContext.class), missingProps);

    Path unreadable = Files.createTempFile("uc-jwks-unreadable", ".json");
    Files.writeString(unreadable, "<html>this is not a key set</html>");
    ServerProperties unreadableProps = mock(ServerProperties.class);
    when(unreadableProps.getExternalJwksFile()).thenReturn(unreadable.toString());
    JwksOperations unreadableOps = new JwksOperations(mock(SecurityContext.class), unreadableProps);

    try (CapturedLog log = CapturedLog.of(JwksOperations.class)) {
      for (int request = 0; request < 5; request++) {
        assertThat(missingFileOps.knownIssuers()).isEmpty();
        assertThat(unreadableOps.knownIssuers()).isEmpty();
      }

      assertThat(log.messagesMentioning(Level.WARN, missing.toString())).hasSize(1);
      assertThat(log.messagesMentioning(Level.WARN, unreadable.toString())).hasSize(1);
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

  private static final String ENTRA_ISSUER =
      "https://login.microsoftonline.com/11111111-2222-3333-4444-555555555555/v2.0";

  /** Operations with both a static JWKS file and a configured Entra tenant. */
  private static JwksOperations opsWith(String externalJwksFile, String entraIssuer) {
    ServerProperties serverProperties = mock(ServerProperties.class);
    when(serverProperties.getExternalJwksFile()).thenReturn(externalJwksFile);
    when(serverProperties.getEntraIssuer()).thenReturn(entraIssuer);
    return new JwksOperations(mock(SecurityContext.class), serverProperties);
  }

  @Test
  public void discoveryFallthroughIsNotWarnedAboutForTheConfiguredEntraIssuer() {
    // Entra's keys rotate and cannot live in the hand-maintained file, so discovery is its only
    // path. Warning on it in every mixed deployment is what teaches an operator to ignore the
    // line that is there to catch a typo'd "issuer" member.
    JwksOperations ops = opsWith("/etc/conf/relyt_jwks.json", ENTRA_ISSUER);

    assertThat(ops.shouldWarnOnDiscoveryFallthrough(ENTRA_ISSUER, "/etc/conf/relyt_jwks.json"))
        .isFalse();
  }

  @Test
  public void discoveryFallthroughIsStillWarnedAboutForEveryOtherIssuer() {
    // The case the warning exists for: an issuer that was meant to be file-held.
    JwksOperations ops = opsWith("/etc/conf/relyt_jwks.json", ENTRA_ISSUER);

    assertThat(ops.shouldWarnOnDiscoveryFallthrough("https://dwsu.example", "/etc/conf/x.json"))
        .isTrue();
    // Another tenant under the same authority is not the configured one, so it is not expected.
    assertThat(
            ops.shouldWarnOnDiscoveryFallthrough(
                "https://login.microsoftonline.com/some-other-tenant/v2.0", "/etc/conf/x.json"))
        .isTrue();
  }

  @Test
  public void discoveryFallthroughIsNotWarnedAboutWithoutAJwksFile() {
    JwksOperations ops = opsWith(null, ENTRA_ISSUER);

    assertThat(ops.shouldWarnOnDiscoveryFallthrough("https://dwsu.example", null)).isFalse();
    assertThat(ops.shouldWarnOnDiscoveryFallthrough("https://dwsu.example", "  ")).isFalse();
  }

  @Test
  public void discoveryFallthroughRuleToleratesAbsentServerProperties() {
    // The single-argument constructor passes a null ServerProperties, so the Entra comparison
    // must not reach for it. Reached in practice wherever JwksOperations is built for INTERNAL.
    JwksOperations ops = new JwksOperations(mock(SecurityContext.class));

    assertThat(ops.shouldWarnOnDiscoveryFallthrough("https://dwsu.example", "/etc/conf/x.json"))
        .isTrue();
    assertThat(ops.shouldWarnOnDiscoveryFallthrough(ENTRA_ISSUER, null)).isFalse();
  }
}
