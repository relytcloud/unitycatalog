package io.unitycatalog.server.exception;

import com.auth0.jwk.JwkException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.unboundid.scim2.common.exceptions.ScimException;
import io.unitycatalog.server.utils.RESTObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GlobalExceptionHandler implements ExceptionHandlerFunction {

  private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /**
   * What an unclassified failure tells the caller. Nothing taken from the exception reaches them:
   * its message can carry a filesystem path, a jdbc URL, a jwks_uri or whatever else the thrower
   * had in scope, and none of this server's callers is trusted with that -- AuthDecorator runs on
   * every authenticated route, and /tokens takes no credentials at all. What is actionable about a
   * 500 is the stack trace, and that goes to the log.
   */
  private static final String UNCLASSIFIED_FAILURE_MESSAGE =
      "The server encountered an internal error; see the server logs for details.";

  @SneakyThrows
  @Override
  public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
    if (cause instanceof BaseException) {
      BaseException baseException = (BaseException) cause;
      // DEBUG, and deliberately not by status. A BaseException was classified by the code that
      // threw it, and that code owns how loudly its failure is reported: JwksOperations, for one,
      // logs a missing local key file at ERROR under a per-issuer cooldown, because AuthDecorator
      // reaches that path on every authenticated request. Re-logging the same failure at ERROR
      // from here, once per request, would reopen exactly the log-volume hole that cooldown
      // exists to close.
      LOGGER.debug("Answering a classified failure with {}", baseException.getErrorCode(), cause);
      return HttpResponse.ofJson(
          baseException.getErrorCode().getHttpStatus(),
          createErrorResponse(
              baseException.getErrorCode(),
              baseException.getErrorMessage(),
              baseException.getMetadata()));
    } else if (cause instanceof JWTVerificationException) {
      LOGGER.debug("Token rejected: signature or claim verification failed", cause);
      return HttpResponse.ofJson(
          HttpStatus.UNAUTHORIZED,
          createErrorResponse(ErrorCode.UNAUTHENTICATED, "Invalid access token.", new HashMap<>()));
    } else if (cause instanceof JwkException) {
      // A safety net, no longer the classifier. JwksOperations translates key-lookup failures into
      // BaseException at the point the key set's PROVENANCE is known, because auth0's hierarchy
      // cannot express it: UrlJwkProvider wraps every IOException as NetworkException, and the
      // internal certs file and the static JWKS file are both UrlJwkProviders over file: URLs, so
      // "NetworkException" there means a missing local file, not an unreachable identity provider.
      // Deciding it here produced a 503 "could not reach the identity provider" for a deleted
      // certs.json, on every authenticated call.
      //
      // What remains is this branch, kept because com.auth0.jwk exceptions are checked exceptions
      // from a sibling hierarchy of com.auth0.jwt's JWTVerificationException and not
      // RuntimeExceptions: any that still escapes would otherwise reach Armeria's default handler
      // and surface as a bodyless HTTP 500. 401 is the conservative answer for an unclassified
      // signing-key failure, and matches what this code did before any of the branches existed.
      //
      // auth0's own message is logged, not returned. Every one of its key-lookup wordings names
      // the key set's location -- "No key found in file:/opt/uc/etc/conf/certs.json with kid ...",
      // "Cannot obtain jwks from url ..." -- and this response goes to whoever presented the
      // token, so passing it through would hand out a server filesystem path or the IdP's
      // jwks_uri. Same rule as JwksOperations.keyLookupFailure, which is what classifies these in
      // practice.
      LOGGER.debug("Unclassified signing-key failure reached the exception handler", cause);
      return HttpResponse.ofJson(
          HttpStatus.UNAUTHORIZED,
          createErrorResponse(
              ErrorCode.UNAUTHENTICATED,
              "The token's signing key could not be verified.",
              new HashMap<>()));
    } else if (cause instanceof Scim2RuntimeException) {
      ScimException scimException = (ScimException) cause.getCause();
      LOGGER.debug("Answering a SCIM failure with 500", cause);
      return HttpResponse.ofJson(
          HttpStatus.INTERNAL_SERVER_ERROR,
          RESTObjectMapper.mapper().writeValueAsString(scimException.getScimError()));
    } else if (cause instanceof RuntimeException) {
      // The one branch that logs at ERROR: everything above was classified by the code that threw
      // it and has already been written down wherever that code decided. Anything arriving here
      // was classified by nobody, so it is a server bug, and this is the only record of it.
      LOGGER.error("Unhandled exception, answering with 500", cause);
      return HttpResponse.ofJson(
          HttpStatus.INTERNAL_SERVER_ERROR,
          createErrorResponse(ErrorCode.INTERNAL, UNCLASSIFIED_FAILURE_MESSAGE, new HashMap<>()));
    }
    return ExceptionHandlerFunction.fallthrough();
  }

  /**
   * The body a caller receives: the error code, the message the throwing code chose FOR a caller,
   * and the details. Nothing derived from the exception object, which is why this no longer takes
   * one.
   *
   * <p>It used to put {@code Arrays.toString(cause.getStackTrace())} into every error response,
   * which is what turned any 500 into a disclosure of this server's internals -- class names,
   * package layout, library versions, and the exact line an unauthenticated request reached. It
   * went out of /tokens, which needs no credentials, and out of every authenticated route via
   * AuthDecorator, and it quietly undid the care taken elsewhere not to name a key set's location,
   * because the frames name the classes that failed to read it. Every caller of this method logs
   * the cause instead.
   *
   * <p>{@code stack_trace} stays in the body as an explicit null rather than disappearing: it is
   * part of the documented error shape -- see docs/server/auth.md, whose samples all show it null
   * -- so a client that reads the field still finds it, empty.
   */
  private Map<String, Object> createErrorResponse(
      ErrorCode errorCode, String message, Map<String, String> metadata) {
    Map<String, Object> response = new HashMap<>();
    response.put("error_code", errorCode.name());
    response.put("message", message);
    response.put("stack_trace", null);

    Map<String, Object> details = new HashMap<>();
    details.put("@type", "google.rpc.ErrorInfo");
    details.put("reason", errorCode.name());
    details.put("metadata", metadata);
    response.put("details", List.of(details));

    return response;
  }
}
