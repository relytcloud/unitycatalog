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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GlobalExceptionHandler implements ExceptionHandlerFunction {

  private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @SneakyThrows
  @Override
  public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
    if (cause instanceof BaseException) {
      BaseException baseException = (BaseException) cause;
      return HttpResponse.ofJson(
          baseException.getErrorCode().getHttpStatus(),
          createErrorResponse(
              baseException.getErrorCode(),
              baseException.getErrorMessage(),
              baseException.getCause(),
              baseException.getMetadata()));
    } else if (cause instanceof JWTVerificationException) {
      return HttpResponse.ofJson(
          HttpStatus.UNAUTHORIZED,
          createErrorResponse(
              ErrorCode.UNAUTHENTICATED, "Invalid access token.", cause, new HashMap<>()));
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
              cause,
              new HashMap<>()));
    } else if (cause instanceof Scim2RuntimeException) {
      ScimException scimException = (ScimException) cause.getCause();
      return HttpResponse.ofJson(
          HttpStatus.INTERNAL_SERVER_ERROR,
          RESTObjectMapper.mapper().writeValueAsString(scimException.getScimError()));
    } else if (cause instanceof RuntimeException) {
      return HttpResponse.ofJson(
          HttpStatus.INTERNAL_SERVER_ERROR,
          createErrorResponse(ErrorCode.INTERNAL, cause.getMessage(), cause, new HashMap<>()));
    }
    return ExceptionHandlerFunction.fallthrough();
  }

  private Map<String, Object> createErrorResponse(
      ErrorCode errorCode, String message, Throwable cause, Map<String, String> metadata) {
    Map<String, Object> response = new HashMap<>();
    response.put("error_code", errorCode.name());
    response.put("message", message);
    response.put("stack_trace", cause != null ? Arrays.toString(cause.getStackTrace()) : null);

    Map<String, Object> details = new HashMap<>();
    details.put("@type", "google.rpc.ErrorInfo");
    details.put("reason", errorCode.name());
    details.put("metadata", metadata);
    response.put("details", List.of(details));

    return response;
  }
}
