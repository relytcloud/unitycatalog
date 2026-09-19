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
