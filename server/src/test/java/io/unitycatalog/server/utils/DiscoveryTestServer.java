package io.unitycatalog.server.utils;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.Server;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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
  private volatile String discoveryBody = null;
  private volatile CountDownLatch discoveryArrived = null;
  private volatile CompletableFuture<Void> discoveryGate = null;

  public DiscoveryTestServer(String jwksJson) {
    this.jwksJson = jwksJson;
    this.server =
        Server.builder()
            .http(0)
            .service(
                "/.well-known/openid-configuration",
                (ctx, req) -> {
                  discoveryHits.incrementAndGet();
                  CountDownLatch arrived = discoveryArrived;
                  if (arrived != null) {
                    arrived.countDown();
                  }
                  String body = discoveryBody;
                  HttpResponse base =
                      discoveryStatus.equals(HttpStatus.OK)
                          ? HttpResponse.of(
                              MediaType.JSON, body != null ? body : discoveryDocument())
                          : HttpResponse.of(discoveryStatus);
                  CompletableFuture<Void> gate = discoveryGate;
                  // A gated response is held until the test releases it, so "a fetch is in
                  // flight" becomes a state the test controls rather than a window it has to
                  // hope for.
                  HttpResponse response =
                      gate == null ? base : HttpResponse.of(gate.thenApply(ignored -> base));
                  // Delayed rather than slept: the handler runs on an event loop and must not
                  // block.
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
    return String.format("{\"issuer\":\"%s\",\"jwks_uri\":\"%s/keys\"}", issuer(), issuer());
  }

  /** Make subsequent discovery requests fail with the given status. */
  public void failDiscoveryWith(HttpStatus status) {
    this.discoveryStatus = status;
  }

  /**
   * Serve this exact body with a 200 instead of the generated discovery document, to exercise a
   * malformed but successful discovery response.
   */
  public void serveDiscoveryBody(String body) {
    this.discoveryBody = body;
  }

  /** Delay subsequent discovery responses, to exercise the client-side timeout. */
  public void delayDiscoveryBy(Duration delay) {
    this.discoveryDelay = delay;
  }

  /**
   * Hold every discovery response until the returned future is completed, and count each arriving
   * request down on {@code arrived}. Together these turn "a discovery fetch is in flight" into an
   * observable, test-controlled state: await {@code arrived}, do whatever must happen while the
   * fetch is outstanding, then complete the future to let it finish. Nothing here sleeps.
   */
  public CompletableFuture<Void> gateDiscoveryOn(CountDownLatch arrived) {
    CompletableFuture<Void> gate = new CompletableFuture<>();
    this.discoveryArrived = arrived;
    this.discoveryGate = gate;
    return gate;
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
