package io.unitycatalog.server.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.unitycatalog.server.exception.AuthorizationException;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.UserRepository;
import io.unitycatalog.server.security.SecurityContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * What this decorator does with a token BEFORE it has been verified.
 *
 * <p>Everything here is reachable by anyone who can set a header: the decorator runs on every
 * authenticated route and decodes the bearer token, without verifying it, to find the issuer and
 * the key to verify it with. Nothing a caller can put in an unverified token may therefore produce
 * a server fault -- and a JWT is far emptier than it looks. {@code JWT.decode} requires only that
 * the three segments are base64url-encoded JSON; it does not require an {@code iss}, an {@code alg}
 * or a {@code kid}, and the accessors return null for each.
 */
public class AuthDecoratorTest {

  /** A syntactically valid, entirely unsigned JWT with exactly the members given. */
  private static String tokenWith(String headerJson, String payloadJson) {
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    return encoder.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8))
        + "."
        + encoder.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8))
        + ".";
  }

  private static HttpRequest requestWith(String token) {
    return HttpRequest.of(
        RequestHeaders.of(
            HttpMethod.GET,
            "/api/2.1/unity-catalog/catalogs",
            HttpHeaderNames.AUTHORIZATION,
            "Bearer " + token));
  }

  /** A well-formed public key, used only as material; no signature is verified here. */
  private static final String ONE_KEY =
      "{\"kty\":\"EC\",\"crv\":\"P-256\",\"kid\":\"kidA\",\"use\":\"sig\","
          + "\"alg\":\"ES256\","
          + "\"x\":\"C8H9oDGZDEKZQ70-zxSiq0z6SdnYwgMLKdAs2xvMbfU\","
          + "\"y\":\"SwxfO-dr60Ugf3IFFazvgxDdBKqDheZYrL0Bk6-76S0\"}";

  /** A decorator whose user lookup must never be reached by any test here. */
  private static AuthDecorator decoratorWith(UserRepository userRepository) {
    return decoratorWith(userRepository, Path.of("/no/such/dir/uc-certs.json"));
  }

  private static AuthDecorator decoratorWith(UserRepository userRepository, Path certsFile) {
    SecurityContext securityContext = mock(SecurityContext.class);
    when(securityContext.getCertsFile()).thenReturn(certsFile);
    Repositories repositories = mock(Repositories.class);
    when(repositories.getUserRepository()).thenReturn(userRepository);
    return new AuthDecorator(securityContext, repositories);
  }

  @Test
  public void tokenWithNoIssuerClaimIsRefusedRatherThanFaultingTheServer() throws Exception {
    // decodedJWT.getIssuer() returns null for a token with no "iss", and issuer.equals(INTERNAL)
    // threw NullPointerException on it: no GlobalExceptionHandler branch but the RuntimeException
    // one matched, so a 500 with a stack trace went back for a token the very next line was
    // there to refuse. Exactly the defect fixed in AuthService's token-exchange path, in the
    // sibling that reads an issuer the same way -- this one on every authenticated route.
    UserRepository userRepository = mock(UserRepository.class);
    AuthDecorator decorator = decoratorWith(userRepository);
    HttpRequest request = requestWith(tokenWith("{\"alg\":\"RS256\"}", "{\"sub\":\"a@b.test\"}"));

    assertThatThrownBy(
            () ->
                decorator.serve(
                    mock(HttpService.class), mock(ServiceRequestContext.class), request))
        .isInstanceOf(AuthorizationException.class)
        .extracting(e -> ((BaseException) e).getErrorCode())
        .isEqualTo(ErrorCode.PERMISSION_DENIED);

    // Refused on the issuer alone: no key was fetched and no user was looked up.
    verifyNoInteractions(userRepository);
  }

  @Test
  public void tokenFromSomeOtherIssuerIsStillRefused() throws Exception {
    // The case the null guard must not have changed: only the internal issuer is accepted here.
    AuthDecorator decorator = decoratorWith(mock(UserRepository.class));
    HttpRequest request =
        requestWith(
            tokenWith("{\"alg\":\"RS256\"}", "{\"iss\":\"https://idp.example\",\"sub\":\"a\"}"));

    assertThatThrownBy(
            () ->
                decorator.serve(
                    mock(HttpService.class), mock(ServiceRequestContext.class), request))
        .isInstanceOf(AuthorizationException.class)
        .extracting(e -> ((BaseException) e).getErrorCode())
        .isEqualTo(ErrorCode.PERMISSION_DENIED);
  }

  @Test
  public void anInternalTokenNamingNoAlgorithmIsRefusedRatherThanFaultingTheServer()
      throws Exception {
    // Past the issuer check with neither "alg" nor "kid", against a certs file that holds exactly
    // ONE key -- which is what SecurityContext writes. UrlJwkProvider.get(null) returns the sole
    // key of a one-key set, so a null 'kid' is no obstacle, and the null 'alg' then reached the
    // algorithm switch and threw NullPointerException there: a 500, with a stack trace, on any
    // authenticated route, for a header anyone can send.
    //
    // A token that names no algorithm cannot be verified, so it is refused as a token.
    Path certsFile = Files.createTempFile("uc-certs", ".json");
    Files.writeString(certsFile, "{\"keys\":[" + ONE_KEY + "]}");
    AuthDecorator decorator = decoratorWith(mock(UserRepository.class), certsFile);
    HttpRequest request = requestWith(tokenWith("{\"typ\":\"JWT\"}", "{\"iss\":\"internal\"}"));

    assertThatThrownBy(
            () ->
                decorator.serve(
                    mock(HttpService.class), mock(ServiceRequestContext.class), request))
        .isInstanceOf(BaseException.class)
        .extracting(e -> ((BaseException) e).getErrorCode())
        .isEqualTo(ErrorCode.UNAUTHENTICATED);
  }
}
