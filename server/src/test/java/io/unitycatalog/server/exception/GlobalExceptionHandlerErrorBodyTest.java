package io.unitycatalog.server.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import org.junit.jupiter.api.Test;

/**
 * What an error response is allowed to contain.
 *
 * <p>Every branch of this handler used to put {@code Arrays.toString(cause.getStackTrace())} into
 * the body, and the unclassified branch also returned {@code cause.getMessage()} verbatim. Both go
 * to whoever made the request: {@code AuthDecorator} runs on every authenticated route and {@code
 * /tokens} takes no credentials at all, so an unauthenticated caller who could provoke any
 * exception read back this server's class names, package layout, library versions and the exact
 * line reached -- and, through the message, whatever the thrower happened to have in scope: a
 * filesystem path, a jdbc URL, a jwks_uri. It also undid, quietly, the care taken elsewhere to keep
 * a key set's location out of a client-facing message.
 *
 * <p>The trace is not lost. It is logged, which is the only place it was ever useful.
 */
public class GlobalExceptionHandlerErrorBodyTest {

  private static AggregatedHttpResponse responseFor(Throwable cause) {
    return new GlobalExceptionHandler()
        .handleException(mock(ServiceRequestContext.class), mock(HttpRequest.class), cause)
        .aggregate()
        .join();
  }

  /** A throwable with a real, filled-in stack trace, as one thrown in anger would have. */
  private static Throwable thrown(Throwable toThrow) {
    try {
      throw toThrow;
    } catch (Throwable t) {
      return t;
    }
  }

  @Test
  public void noErrorBodyCarriesAStackTrace() {
    // Asserted on frame text rather than on the absence of the field: a frame reads
    // "io.unitycatalog...(GlobalExceptionHandlerErrorBodyTest.java:42)", so ".java:" cannot
    // appear in a body that carries none.
    Throwable classified =
        thrown(new BaseException(ErrorCode.INTERNAL, "boom", new IllegalStateException("inner")));
    Throwable unclassified = thrown(new IllegalStateException("inner"));

    for (Throwable cause : new Throwable[] {classified, unclassified}) {
      String body = responseFor(cause).contentUtf8();
      assertThat(body).as(cause.toString()).doesNotContain(".java:");
      assertThat(body).as(cause.toString()).doesNotContain("io.unitycatalog.server.exception");
      assertThat(body).as(cause.toString()).doesNotContain("at java.base");
    }
  }

  @Test
  public void anUnclassifiedFailureDoesNotEchoItsOwnMessage() {
    // The message of an exception nobody classified was written for a log, not for a caller. This
    // one is what a connection failure or a mis-set property really looks like.
    Throwable cause =
        thrown(
            new IllegalStateException(
                "Cannot open jdbc:h2:/srv/uc/etc/db/h2db (user=sa password=hunter2)"));

    AggregatedHttpResponse response = responseFor(cause);

    assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.contentUtf8()).doesNotContain("hunter2");
    assertThat(response.contentUtf8()).doesNotContain("jdbc:h2");
    assertThat(response.contentUtf8()).doesNotContain("/srv/uc");
    // It still says what happened, and where the detail went.
    assertThat(response.contentUtf8()).contains("INTERNAL");
    assertThat(response.contentUtf8()).contains("server logs");
  }

  @Test
  public void classifiedFailureStillReturnsTheMessageItsThrowerChose() {
    // The other half: these messages are written FOR the caller, and several are load-bearing --
    // "User not allowed: ...", "No signing key matching the token's 'kid' ...". Dropping them
    // would trade one defect for a worse one.
    AggregatedHttpResponse response =
        responseFor(
            thrown(
                new BaseException(
                    ErrorCode.PERMISSION_DENIED, "User not allowed: bobbie@rocinante")));

    assertThat(response.status()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.contentUtf8()).contains("User not allowed: bobbie@rocinante");
  }

  @Test
  public void theStackTraceFieldRemainsInTheDocumentedShapeAsNull() {
    // docs/server/auth.md shows every error body with "stack_trace":null, and a client reading
    // the field should find it empty rather than missing.
    assertThat(responseFor(thrown(new IllegalStateException("inner"))).contentUtf8())
        .contains("\"stack_trace\":null");
  }
}
