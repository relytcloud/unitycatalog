package io.unitycatalog.server.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.auth0.jwk.NetworkException;
import com.auth0.jwk.SigningKeyNotFoundException;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import org.junit.jupiter.api.Test;

/**
 * What this handler is still responsible for once signing-key failures are classified upstream.
 *
 * <p>It used to classify them here, by matching auth0's exception classes: NetworkException to 503,
 * RateLimitReachedException to 503, anything else to 401. That was wrong, and unit tests that
 * constructed those exceptions by hand could never have shown it -- they proved the branches were
 * ordered correctly and said nothing about which branch a real failure lands in. {@code
 * UrlJwkProvider} wraps every IOException as NetworkException, and the internal certs file and the
 * static JWKS file are both UrlJwkProviders over {@code file:} URLs, so a deleted certs.json
 * arrived here as a "network" failure and was answered with 503 "could not reach the identity
 * provider" on every authenticated call.
 *
 * <p>Classification now lives in {@code JwksOperations}, where the provenance of the key set is
 * known, and is tested against the real provider chain in {@code JwksKeyLookupClassificationTest}.
 * What is left here is rendering an already-classified BaseException, and a safety net so no
 * com.auth0.jwk exception can escape as a bodyless 500.
 */
public class GlobalExceptionHandlerJwkTest {

  private AggregatedHttpResponse responseFor(Throwable cause) {
    HttpResponse response =
        new GlobalExceptionHandler()
            .handleException(mock(ServiceRequestContext.class), mock(HttpRequest.class), cause);
    return response.aggregate().join();
  }

  private HttpStatus statusFor(Throwable cause) {
    return responseFor(cause).status();
  }

  @Test
  public void anUnclassifiedJwkExceptionStillGetsABodyRatherThanABodylessError() {
    // com.auth0.jwk exceptions are checked, and a sibling hierarchy of com.auth0.jwt's
    // JWTVerificationException. Without this branch they reach Armeria's default handler.
    AggregatedHttpResponse response =
        responseFor(new SigningKeyNotFoundException("no such kid", null));

    assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.contentUtf8()).contains("no such kid");
  }

  @Test
  public void theHandlerNoLongerClassifiesByAuth0ExceptionClass() {
    // NetworkException means "cannot obtain jwks from url", which for a file: URL is a missing
    // local file, not an unreachable identity provider. This handler cannot tell the difference,
    // so it must not pretend to: it gets the same fallback as any other unclassified key failure,
    // and JwksOperations is what decides the real answer.
    assertThat(statusFor(new NetworkException("cannot obtain jwks", new RuntimeException())))
        .isEqualTo(statusFor(new SigningKeyNotFoundException("no such kid", null)));
  }

  @Test
  public void localKeyFileFaultIsRenderedAsAServerFaultNamingTheFile() {
    // The shape JwksOperations now produces for a missing or unreadable certs.json.
    AggregatedHttpResponse response =
        responseFor(
            new BaseException(
                ErrorCode.INTERNAL,
                "Could not read the signing keys for issuer 'internal' from"
                    + " '/opt/uc/etc/conf/certs.json': Cannot obtain jwks from url"
                    + " file:/opt/uc/etc/conf/certs.json. This is a server configuration problem,"
                    + " not a problem with the token."));

    assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.contentUtf8()).contains("/opt/uc/etc/conf/certs.json");
    // A 503 here tells every load balancer and client to retry a condition that never clears.
    assertThat(response.status()).isNotEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  public void anUpstreamKeyFetchFailureIsRenderedAsServiceUnavailable() {
    AggregatedHttpResponse response =
        responseFor(
            new OAuthInvalidRequestException(
                ErrorCode.UNAVAILABLE,
                "Could not reach the identity provider to fetch the signing keys for issuer"
                    + " https://idp.example"));

    assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.contentUtf8()).contains("identity provider");
  }

  @Test
  public void rejectedKidIsRenderedAsUnauthorized() {
    AggregatedHttpResponse response =
        responseFor(
            new OAuthInvalidClientException(
                ErrorCode.UNAUTHENTICATED, "Invalid signing key: no such kid"));

    assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
