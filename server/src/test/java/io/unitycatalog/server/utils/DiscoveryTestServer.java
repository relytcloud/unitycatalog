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
  private volatile String discoveryBody = null;

  public DiscoveryTestServer(String jwksJson) {
    this.jwksJson = jwksJson;
    this.server =
        Server.builder()
            .http(0)
            .service(
                "/.well-known/openid-configuration",
                (ctx, req) -> {
                  discoveryHits.incrementAndGet();
                  String body = discoveryBody;
                  HttpResponse response =
                      discoveryStatus.equals(HttpStatus.OK)
                          ? HttpResponse.of(
                              MediaType.JSON, body != null ? body : discoveryDocument())
                          : HttpResponse.of(discoveryStatus);
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
