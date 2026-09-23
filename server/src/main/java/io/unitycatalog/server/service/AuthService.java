package io.unitycatalog.server.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import io.unitycatalog.control.model.AccessTokenType;
import io.unitycatalog.control.model.GrantType;
import io.unitycatalog.control.model.OAuthTokenExchangeForm;
import io.unitycatalog.control.model.OAuthTokenExchangeInfo;
import io.unitycatalog.control.model.TokenEndpointExtensionType;
import io.unitycatalog.control.model.TokenType;
import io.unitycatalog.control.model.User;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.exception.GlobalExceptionHandler;
import io.unitycatalog.server.exception.OAuthInvalidRequestException;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.UserRepository;
import io.unitycatalog.server.security.JwtClaim;
import io.unitycatalog.server.security.SecurityContext;
import io.unitycatalog.server.utils.JwksOperations;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.lang.reflect.ParameterizedType;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ExceptionHandler(GlobalExceptionHandler.class)
public class AuthService {

  private static final Logger LOGGER = LoggerFactory.getLogger(AuthService.class);
  private final UserRepository userRepository;

  private final SecurityContext securityContext;
  private final JwksOperations jwksOperations;
  private final ServerProperties serverProperties;

  private static final String EMPTY_RESPONSE = "{}";

  /** Binds the browser's /login round-trip to its /callback: "<state>|<return path>". */
  private static final String OAUTH_STATE_COOKIE = "UC_OAUTH_STATE";

  private static final String OAUTH_STATE_TTL = "PT10M";
  private static final String OAUTH_SCOPE = "openid profile email";
  private static final Duration IDP_TIMEOUT = Duration.ofSeconds(10);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** The only account the password sign-in accepts; it is the bootstrap administrator's email. */
  private static final String ADMIN_USERNAME = "admin";

  /** Slows password guessing without a lockout table; the handler is @Blocking. */
  private static final Duration FAILED_LOGIN_DELAY = Duration.ofSeconds(1);

  private final WebClient idpClient =
      WebClient.builder().responseTimeout(IDP_TIMEOUT).writeTimeout(IDP_TIMEOUT).build();

  public AuthService(
      SecurityContext securityContext,
      ServerProperties serverProperties,
      Repositories repositories) {
    this.securityContext = securityContext;
    this.jwksOperations = new JwksOperations(securityContext, serverProperties);
    this.serverProperties = serverProperties;
    this.userRepository = repositories.getUserRepository();
  }

  /**
   * OAuth token exchange.
   *
   * <p>Performs an OAuth token exchange for an access-token. Specifically this endpoint accepts a
   * "token-exchange" grant type (urn:ietf:params:oauth:grant-type:token-exchange) and along with
   * either an identity-token (urn:ietf:params:oauth:token-type:id_token) or a access-token
   * (urn:ietf:params:oauth:token-type:access_token), validates the token signature using OIDC
   * discovery and JWKs, and then creates a new access-token.
   *
   * <ul>
   *   <li>grant_type: urn:ietf:params:oauth:grant-type:token-exchange
   *   <li>requested_token_type: urn:ietf:params:oauth:token-type:access_token or
   *       urn:ietf:params:oauth:token-type:id_token
   *   <li>subject_token_type: urn:ietf:params:oauth:token-type:access_token
   *   <li>subject_token: The incoming token (typically from an identity provider)
   *   <li>actor_token_type: Not supported
   *   <li>actor_token: Not supported
   *   <li>scope: Not supported
   * </ul>
   *
   * <p>The issuer of the incoming token must be in the configured allowlist
   * (server.allowed-issuers) and the token must contain a valid audience claim matching the
   * configured audiences (server.audiences). Both configurations are required when authorization is
   * enabled.
   *
   * @param ext Specifies whether the issued token should be set as a cookie.
   * @param form The OAuth 2.0 token exchange request form.
   * @return The token exchange response
   */
  @Post("/tokens")
  @com.linecorp.armeria.server.annotation.Blocking
  public HttpResponse grantToken(
      ServiceRequestContext ctx,
      @Param("ext") Optional<TokenEndpointExtensionType> ext,
      @RequestConverter(ToOAuthTokenExchangeFormConverter.class) OAuthTokenExchangeForm form) {
    LOGGER.debug("Got token: {}", form);

    if (GrantType.TOKEN_EXCHANGE != form.getGrantType()) {
      throw new OAuthInvalidRequestException(
          ErrorCode.INVALID_ARGUMENT, "Unsupported grant type: " + form.getGrantType());
    }

    if (TokenType.ACCESS_TOKEN != form.getRequestedTokenType()) {
      throw new OAuthInvalidRequestException(
          ErrorCode.INVALID_ARGUMENT,
          "Unsupported requested token type: " + form.getRequestedTokenType());
    }

    if (form.getSubjectTokenType() == null) {
      throw new OAuthInvalidRequestException(
          ErrorCode.INVALID_ARGUMENT, "Subject token type is required but was not specified");
    }

    if (form.getActorTokenType() != null) {
      throw new OAuthInvalidRequestException(
          ErrorCode.INVALID_ARGUMENT, "Actor tokens not currently supported");
    }

    String accessToken = exchangeSubjectToken(form.getSubjectToken());

    OAuthTokenExchangeInfo tokenExchangeInfo =
        new OAuthTokenExchangeInfo()
            .accessToken(accessToken)
            .issuedTokenType(TokenType.ACCESS_TOKEN)
            .tokenType(AccessTokenType.BEARER);

    // Set token as cookie if ext param is set to cookie
    ResponseHeadersBuilder responseHeaders = ResponseHeaders.builder(HttpStatus.OK);
    ext.ifPresent(
        e -> {
          if (e.equals(TokenEndpointExtensionType.COOKIE)) {
            // Set cookie timeout to 5 days by default if not present in server.properties
            String cookieTimeout = this.serverProperties.get(Property.COOKIE_TIMEOUT);
            Cookie cookie =
                createCookie(ctx, AuthDecorator.UC_TOKEN_KEY, accessToken, "/", cookieTimeout);
            responseHeaders.add(HttpHeaderNames.SET_COOKIE, cookie.toSetCookieHeader());
          }
        });

    return HttpResponse.ofJson(responseHeaders.build(), tokenExchangeInfo);
  }

  /**
   * Verifies an identity token from a trusted issuer and issues a UC access token for the local
   * user it names. Shared by the token-exchange endpoint and the server-hosted login callback, so
   * both admit callers under exactly the same rules.
   */
  private String exchangeSubjectToken(String subjectToken) {
    boolean authorizationEnabled = this.serverProperties.isAuthorizationEnabled();
    if (!authorizationEnabled) {
      throw new OAuthInvalidRequestException(
          ErrorCode.INVALID_ARGUMENT, "Authorization is disabled");
    }

    // Trusted issuers are the UNION of two sources (issue #4):
    //  - knownIssuers(): derived fresh from the hot-reloaded external JWKS file, so onboarding a
    //    new DWSU only needs appending its key to the JWKS ConfigMap -- no UC restart, no
    //    server.properties change (the recommended path for Relyt instances).
    //  - server.allowed-issuers: a startup-snapshot list, kept for issuers NOT in the local JWKS
    //    (e.g. OIDC well-known discovery issuers) and for backward compatibility. Leave it empty to
    //    be governed entirely by the JWKS.
    Set<String> knownIssuers = jwksOperations.knownIssuers();
    List<String> allowedIssuers = serverProperties.getAllowedIssuers();
    if (knownIssuers.isEmpty() && allowedIssuers.isEmpty()) {
      LOGGER.error("No trusted issuers configured");
      throw new OAuthInvalidRequestException(
          ErrorCode.INVALID_ARGUMENT,
          "No trusted issuers configured. Register a DWSU key (with an \"issuer\" member) in the "
              + "external JWKS file (server.external-jwks-file), or set server.allowed-issuers.");
    }

    List<String> audiences = serverProperties.getAudiences();
    if (audiences.isEmpty()) {
      LOGGER.error("No audiences configured");
      throw new OAuthInvalidRequestException(
          ErrorCode.INVALID_ARGUMENT,
          "No audiences configured. Set server.audiences in server.properties");
    }

    DecodedJWT decodedJWT;
    try {
      decodedJWT = JWT.decode(subjectToken);
    } catch (JWTDecodeException e) {
      LOGGER.debug("Token rejected: malformed token", e);
      throw new OAuthInvalidRequestException(
          ErrorCode.UNAUTHENTICATED, "Invalid token: " + e.getMessage(), e);
    }

    String issuer = decodedJWT.getIssuer();

    // Validate issuer BEFORE fetching JWKS: trusted if present in the JWKS-derived set OR the
    // configured allow-list (union).
    if (!knownIssuers.contains(issuer) && !allowedIssuers.contains(issuer)) {
      LOGGER.debug("Token rejected: invalid issuer '{}'", issuer);
      throw new OAuthInvalidRequestException(ErrorCode.UNAUTHENTICATED, "Invalid issuer");
    }

    String keyId = decodedJWT.getKeyId();
    String alg = decodedJWT.getAlgorithm();

    LOGGER.debug("Validating token for issuer: {} and keyId: {}", issuer, keyId);

    try {
      JWTVerifier jwtVerifier =
          jwksOperations.verifierForIssuerAndKey(issuer, keyId, alg, audiences);
      decodedJWT = jwtVerifier.verify(decodedJWT);
    } catch (JWTVerificationException e) {
      LOGGER.debug("Token rejected: verification failed", e);
      throw new OAuthInvalidRequestException(
          ErrorCode.UNAUTHENTICATED, "Token verification failed: " + e.getMessage(), e);
    }

    User user = resolvePrincipal(decodedJWT);

    LOGGER.debug("Validated. Creating access token.");

    // The exchanged token carries the LOCAL user's email as sub, whatever claim identified the
    // caller. Everything downstream keys off that email, so the request path is untouched by
    // which claim matched.
    return securityContext.createAccessToken(user.getEmail(), serverProperties.getAccessTokenTtl());
  }

  /**
   * Server-hosted OAuth login, step one: send the browser to the identity provider (issue #15).
   *
   * <p>The browser is redirected -- the server never contacts the provider here. A one-time state
   * is bound to a short-lived cookie so the callback can tell this login apart from a forged one.
   * The client secret stays on the server; the UI only needs a link to this endpoint.
   *
   * @param redirect UI path to return to after login; anything but a relative path falls back to /
   */
  @Get("/login")
  public HttpResponse login(
      ServiceRequestContext ctx,
      HttpRequest request,
      @Param("redirect") Optional<String> redirect) {
    requireHostedLoginConfigured();

    String state = UUID.randomUUID().toString();
    String returnTo = safeReturnPath(redirect.orElse(null));
    String authorizationUrl = serverProperties.getAuthorizationUrl();
    String location =
        authorizationUrl
            + (authorizationUrl.contains("?") ? "&" : "?")
            + "client_id="
            + urlEncode(serverProperties.getClientId())
            + "&response_type=code&response_mode=query"
            + "&redirect_uri="
            + urlEncode(callbackUrl(ctx, request))
            + "&scope="
            + urlEncode(OAUTH_SCOPE)
            + "&state="
            + urlEncode(state);

    Cookie stateCookie =
        createCookie(
            ctx, OAUTH_STATE_COOKIE, state + "|" + returnTo, authMountPath(ctx), OAUTH_STATE_TTL);
    return HttpResponse.of(
        ResponseHeaders.builder(HttpStatus.FOUND)
            .add(HttpHeaderNames.LOCATION, location)
            .add(HttpHeaderNames.SET_COOKIE, stateCookie.toSetCookieHeader())
            .build());
  }

  /**
   * Server-hosted OAuth login, step two: the provider has sent the browser back with a code. This
   * is the one place the server itself calls the provider -- redeeming the code with the client
   * secret -- after which the resulting id_token goes through the same verification as a token
   * exchange and the UC access token is set as the session cookie.
   */
  @Get("/callback")
  @com.linecorp.armeria.server.annotation.Blocking
  public HttpResponse loginCallback(
      ServiceRequestContext ctx,
      HttpRequest request,
      @Param("code") Optional<String> code,
      @Param("state") Optional<String> state,
      @Param("error") Optional<String> error,
      @Param("error_description") Optional<String> errorDescription) {
    requireHostedLoginConfigured();

    if (error.isPresent()) {
      throw new OAuthInvalidRequestException(
          ErrorCode.UNAUTHENTICATED,
          "Identity provider returned " + error.get() + ": " + errorDescription.orElse(""));
    }

    String stateCookie = readCookie(request, OAUTH_STATE_COOKIE);
    if (stateCookie == null) {
      throw new OAuthInvalidRequestException(
          ErrorCode.INVALID_ARGUMENT, "Missing login state; start again from /login");
    }
    int separator = stateCookie.indexOf('|');
    String expectedState = separator < 0 ? stateCookie : stateCookie.substring(0, separator);
    String returnTo = separator < 0 ? "/" : safeReturnPath(stateCookie.substring(separator + 1));
    if (state.isEmpty() || !expectedState.equals(state.get())) {
      throw new OAuthInvalidRequestException(ErrorCode.INVALID_ARGUMENT, "Login state mismatch");
    }
    if (code.isEmpty() || code.get().isBlank()) {
      throw new OAuthInvalidRequestException(
          ErrorCode.INVALID_ARGUMENT, "Missing authorization code");
    }

    String idToken = redeemAuthorizationCode(code.get(), callbackUrl(ctx, request));
    String accessToken = exchangeSubjectToken(idToken);

    String cookieTimeout = this.serverProperties.get(Property.COOKIE_TIMEOUT);
    Cookie sessionCookie =
        createCookie(ctx, AuthDecorator.UC_TOKEN_KEY, accessToken, "/", cookieTimeout);
    Cookie clearedState = createCookie(ctx, OAUTH_STATE_COOKIE, "", authMountPath(ctx), "PT0S");
    return HttpResponse.of(
        ResponseHeaders.builder(HttpStatus.FOUND)
            .add(HttpHeaderNames.LOCATION, returnTo)
            .add(HttpHeaderNames.SET_COOKIE, sessionCookie.toSetCookieHeader())
            .add(HttpHeaderNames.SET_COOKIE, clearedState.toSetCookieHeader())
            .build());
  }

  private void requireHostedLoginConfigured() {
    if (!serverProperties.isHostedLoginConfigured()) {
      throw new OAuthInvalidRequestException(
          ErrorCode.INVALID_ARGUMENT,
          "Hosted login is not configured: set server.authorization-url, server.token-url, "
              + "server.client-id and server.client-secret");
    }
  }

  /** Exchanges the authorization code at the provider's token endpoint for an id_token. */
  private String redeemAuthorizationCode(String code, String redirectUri) {
    String form =
        "grant_type=authorization_code"
            + "&code="
            + urlEncode(code)
            + "&redirect_uri="
            + urlEncode(redirectUri)
            + "&client_id="
            + urlEncode(serverProperties.getClientId())
            + "&client_secret="
            + urlEncode(serverProperties.getClientSecret())
            + "&scope="
            + urlEncode(OAUTH_SCOPE);
    RequestHeaders headers =
        RequestHeaders.of(
            HttpMethod.POST,
            serverProperties.getTokenUrl(),
            HttpHeaderNames.CONTENT_TYPE,
            MediaType.FORM_DATA);
    var response = idpClient.execute(headers, HttpData.ofUtf8(form)).aggregate().join();
    if (!response.status().isSuccess()) {
      // This is where an expired client secret surfaces, and it surfaces late: the person has
      // already signed in at the provider. Name the provider's own error code, or a lapsed secret,
      // a redirect URI that no longer matches and a replayed code all read as the same failure.
      String reason = oauthErrorCode(response.contentUtf8());
      LOGGER.warn(
          "Code redemption failed: {} {}", response.status(), abbreviate(response.contentUtf8()));
      throw new OAuthInvalidRequestException(
          ErrorCode.UNAUTHENTICATED,
          "Identity provider rejected the authorization code: "
              + response.status()
              + (reason.isEmpty() ? "" : " (" + reason + ")"));
    }
    try {
      JsonNode body = JSON.readTree(response.contentUtf8());
      JsonNode idToken = body.get("id_token");
      if (idToken == null || idToken.asText().isBlank()) {
        throw new OAuthInvalidRequestException(
            ErrorCode.UNAUTHENTICATED,
            "Identity provider did not return an id_token; is the openid scope granted?");
      }
      return idToken.asText();
    } catch (java.io.IOException e) {
      throw new OAuthInvalidRequestException(
          ErrorCode.UNAUTHENTICATED, "Unreadable response from the identity provider", e);
    }
  }

  /**
   * The provider's own name for the failure, from the OAuth error response: its {@code error} field
   * plus the first provider-specific code in {@code error_description} (Entra ID states
   * AADSTS7000222 for an expired client secret, AADSTS50011 for a redirect URI that does not
   * match). The description itself is left to the log: it runs to several lines and carries a
   * correlation id, and the browser only needs enough to tell the causes apart.
   */
  private static String oauthErrorCode(String body) {
    if (body == null || body.isBlank()) {
      return "";
    }
    try {
      JsonNode json = JSON.readTree(body);
      String error = json.path("error").asText("");
      java.util.regex.Matcher m =
          java.util.regex.Pattern.compile("[A-Z]{2,}\\d{4,}")
              .matcher(json.path("error_description").asText(""));
      String providerCode = m.find() ? m.group() : "";
      return Stream.of(error, providerCode)
          .filter(v -> !v.isBlank())
          .collect(Collectors.joining(", "));
    } catch (java.io.IOException e) {
      return "";
    }
  }

  private static String abbreviate(String body) {
    if (body == null) {
      return "";
    }
    String oneLine = body.replaceAll("\\s+", " ").trim();
    return oneLine.length() <= 500 ? oneLine : oneLine.substring(0, 500) + "…";
  }

  /**
   * The absolute URL of /callback as the BROWSER must reach it. Behind the UI's reverse proxy the
   * server sees the proxy's host, not the one in the address bar, so honour server.external-url
   * first, then X-Forwarded-Proto/Host, and only then the request's own scheme and Host.
   */
  private String callbackUrl(ServiceRequestContext ctx, HttpRequest request) {
    String base = serverProperties.getExternalUrl();
    if (base == null) {
      String forwardedProto = firstValue(request.headers().get(HttpHeaderNames.X_FORWARDED_PROTO));
      String forwardedHost = firstValue(request.headers().get(HttpHeaderNames.X_FORWARDED_HOST));
      String scheme =
          forwardedProto != null
              ? forwardedProto
              : (ctx.sessionProtocol().isTls() ? "https" : "http");
      String host = forwardedHost != null ? forwardedHost : request.headers().authority();
      base = scheme + "://" + host;
    }
    return base + authMountPath(ctx) + "/callback";
  }

  /** The path this service is mounted at (e.g. /api/1.0/unity-control/auth), from the request. */
  private static String authMountPath(ServiceRequestContext ctx) {
    String path = ctx.path();
    int lastSlash = path.lastIndexOf('/');
    return lastSlash > 0 ? path.substring(0, lastSlash) : "";
  }

  /** Only a relative in-app path may be returned to after login; anything else goes to /. */
  private static String safeReturnPath(String candidate) {
    if (candidate == null || candidate.isBlank()) {
      return "/";
    }
    // "//host" is scheme-relative and would leave the site; so would a scheme or backslashes.
    if (!candidate.startsWith("/") || candidate.startsWith("//") || candidate.contains("\\")) {
      return "/";
    }
    return candidate;
  }

  private static String firstValue(String headerValue) {
    if (headerValue == null || headerValue.isBlank()) {
      return null;
    }
    int comma = headerValue.indexOf(',');
    return (comma < 0 ? headerValue : headerValue.substring(0, comma)).trim();
  }

  private static String readCookie(HttpRequest request, String name) {
    return request.headers().cookies().stream()
        .filter(c -> c.name().equals(name))
        .map(Cookie::value)
        .findFirst()
        .orElse(null);
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /**
   * Which sign-in methods this server offers. Public: the UI reads it before anyone has signed in,
   * to decide whether to show a login page at all and which entry points to put on it, so a single
   * UI build follows the server's configuration. Nothing here is secret -- three booleans saying
   * which documented features are switched on.
   */
  @Get("/providers")
  public HttpResponse authProviders() {
    return HttpResponse.ofJson(
        Map.of(
            "authorization_enabled", serverProperties.isAuthorizationEnabled(),
            "hosted_login", serverProperties.isHostedLoginConfigured(),
            "admin_login", serverProperties.getAdminPassword() != null));
  }

  /**
   * The administrator's password sign-in for the UI (issue #15). Only the built-in "admin" account
   * signs in this way; everyone else uses their identity provider. The password from
   * server.properties is compared in constant time and a failure is delayed. Success ends exactly
   * where a Microsoft sign-in does -- a UC access token in the UC_TOKEN cookie -- so nothing
   * downstream distinguishes the two sessions.
   */
  @Post("/admin/login")
  @com.linecorp.armeria.server.annotation.Blocking
  public HttpResponse adminLogin(ServiceRequestContext ctx, AggregatedHttpRequest request) {
    String configured = serverProperties.getAdminPassword();
    if (configured == null) {
      throw new OAuthInvalidRequestException(
          ErrorCode.INVALID_ARGUMENT,
          "Administrator sign-in is not configured: set server.admin-password");
    }

    Map<String, String> form = formFields(request);
    String username = form.getOrDefault("username", "");
    String password = form.getOrDefault("password", "");

    // Both comparisons always run so timing does not reveal which one failed.
    boolean usernameMatches = constantTimeEquals(ADMIN_USERNAME, username);
    boolean passwordMatches = constantTimeEquals(configured, password);
    if (!usernameMatches || !passwordMatches) {
      LOGGER.warn("Administrator sign-in rejected for username '{}'", username);
      sleepQuietly(FAILED_LOGIN_DELAY);
      throw new OAuthInvalidRequestException(
          ErrorCode.UNAUTHENTICATED, "Wrong username or password");
    }

    String accessToken =
        securityContext.createAccessToken(ADMIN_USERNAME, serverProperties.getAccessTokenTtl());
    OAuthTokenExchangeInfo info =
        new OAuthTokenExchangeInfo()
            .accessToken(accessToken)
            .issuedTokenType(TokenType.ACCESS_TOKEN)
            .tokenType(AccessTokenType.BEARER);
    String cookieTimeout = this.serverProperties.get(Property.COOKIE_TIMEOUT);
    Cookie session = createCookie(ctx, AuthDecorator.UC_TOKEN_KEY, accessToken, "/", cookieTimeout);
    return HttpResponse.ofJson(
        ResponseHeaders.builder(HttpStatus.OK)
            .add(HttpHeaderNames.SET_COOKIE, session.toSetCookieHeader())
            .build(),
        info);
  }

  /**
   * Signs a browser in with a Unity Catalog access token the operator already holds -- the one in
   * etc/conf/token.txt, typically. It replaces the proxy's blanket token injection, which made the
   * application's own address an unauthenticated way in: the token now has to be presented at an
   * address of its own, and it buys exactly the identity the token names.
   *
   * <p>The token is checked the way every request is checked -- issued by this server, signature
   * valid, not expired, naming an enabled user -- and is then handed back as the session cookie
   * itself. Nothing is minted here, so the session cannot outlive the token it came from.
   */
  @Post("/token/login")
  @com.linecorp.armeria.server.annotation.Blocking
  public HttpResponse tokenLogin(ServiceRequestContext ctx, AggregatedHttpRequest request) {
    String token = formFields(request).getOrDefault("token", "").trim();
    if (token.isEmpty()) {
      throw new OAuthInvalidRequestException(ErrorCode.INVALID_ARGUMENT, "No token supplied");
    }

    DecodedJWT decodedJWT;
    try {
      decodedJWT = JWT.decode(token);
    } catch (JWTDecodeException e) {
      throw rejectToken("not a token", e);
    }

    // Only this server's own tokens open a session. A token from an identity provider goes through
    // /auth/tokens instead, which is where issuer and audience are checked.
    if (!SecurityContext.Issuers.INTERNAL.equals(decodedJWT.getIssuer())) {
      throw rejectToken("issuer '" + decodedJWT.getIssuer() + "' is not this server", null);
    }

    try {
      JWTVerifier verifier =
          jwksOperations.verifierForIssuerAndKey(
              SecurityContext.Issuers.INTERNAL,
              decodedJWT.getKeyId(),
              decodedJWT.getAlgorithm(),
              List.of());
      decodedJWT = verifier.verify(decodedJWT);
    } catch (JWTVerificationException e) {
      throw rejectToken("verification failed", e);
    }

    String subject = decodedJWT.getSubject();
    User user = enabledUserOrNull(() -> userRepository.getUserByEmail(subject));
    if (user == null) {
      throw rejectToken("no enabled user named '" + subject + "'", null);
    }

    LOGGER.info("Token sign-in accepted for {}", user.getEmail());
    OAuthTokenExchangeInfo info =
        new OAuthTokenExchangeInfo()
            .accessToken(token)
            .issuedTokenType(TokenType.ACCESS_TOKEN)
            .tokenType(AccessTokenType.BEARER);
    Cookie session =
        createCookie(ctx, AuthDecorator.UC_TOKEN_KEY, token, "/", sessionSeconds(decodedJWT));
    return HttpResponse.ofJson(
        ResponseHeaders.builder(HttpStatus.OK)
            .add(HttpHeaderNames.SET_COOKIE, session.toSetCookieHeader())
            .build(),
        info);
  }

  /**
   * One rejection for every way a presented token can be wrong, so a caller cannot tell them apart;
   * the reason is logged rather than returned. Delayed like a wrong password: this endpoint is
   * public and the token is the only secret it asks for.
   */
  private OAuthInvalidRequestException rejectToken(String reason, Exception cause) {
    LOGGER.warn("Token sign-in rejected: {}", reason);
    sleepQuietly(FAILED_LOGIN_DELAY);
    String message = "Token is not valid for sign-in";
    return cause == null
        ? new OAuthInvalidRequestException(ErrorCode.UNAUTHENTICATED, message)
        : new OAuthInvalidRequestException(ErrorCode.UNAUTHENTICATED, message, cause);
  }

  /** The session may last as long as the configured cookie timeout, but never past the token. */
  private long sessionSeconds(DecodedJWT decodedJWT) {
    long configured = Duration.parse(serverProperties.get(Property.COOKIE_TIMEOUT)).getSeconds();
    if (decodedJWT.getExpiresAt() == null) {
      return configured;
    }
    long untilExpiry =
        Duration.between(Instant.now(), decodedJWT.getExpiresAt().toInstant()).getSeconds();
    return Math.max(0, Math.min(configured, untilExpiry));
  }

  private static Map<String, String> formFields(AggregatedHttpRequest request) {
    MediaType contentType = request.contentType();
    if (contentType == null || !contentType.belongsTo(MediaType.FORM_DATA)) {
      return Map.of();
    }
    return QueryParams.fromQueryString(request.content(contentType.charset(StandardCharsets.UTF_8)))
        .stream()
        .collect(
            Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (first, second) -> first));
  }

  private static boolean constantTimeEquals(String expected, String actual) {
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }

  private static void sleepQuietly(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Post("/logout")
  public HttpResponse logout(ServiceRequestContext ctx, HttpRequest request) {
    return request.headers().cookies().stream()
        .filter(c -> c.name().equals(AuthDecorator.UC_TOKEN_KEY))
        .findFirst()
        .map(
            authorizationCookie -> {
              Cookie expiredCookie = createCookie(ctx, AuthDecorator.UC_TOKEN_KEY, "", "/", "PT0S");
              ResponseHeaders headers =
                  ResponseHeaders.builder()
                      .status(HttpStatus.OK)
                      .add(HttpHeaderNames.SET_COOKIE, expiredCookie.toSetCookieHeader())
                      .contentType(MediaType.JSON)
                      .build();
              // Armeria requires a non-empty response payload, so an empty JSON is sent
              return HttpResponse.of(headers, HttpData.ofUtf8(EMPTY_RESPONSE));
            })
        .orElse(HttpResponse.of(HttpStatus.OK, MediaType.JSON, EMPTY_RESPONSE));
  }

  /**
   * Maps the verified identity token to a local, enabled user. Claims are tried in order and the
   * first that names a user wins: {@code email}; then {@code preferred_username} and {@code upn},
   * because Entra ID omits {@code email} unless it is configured as an optional claim and the
   * sign-in name is what identifies the account; then {@code sub}, which is where Relyt instances
   * and the bootstrap admin carry the principal. Every lookup is by the user's email. There is no
   * just-in-time provisioning: the user must already exist.
   */
  private User resolvePrincipal(DecodedJWT decodedJWT) {
    String identifier = null;
    for (JwtClaim claim :
        List.of(JwtClaim.EMAIL, JwtClaim.PREFERRED_USERNAME, JwtClaim.UPN, JwtClaim.SUBJECT)) {
      String value = claimAsString(decodedJWT, claim);
      if (value == null) {
        continue;
      }
      identifier = identifier == null ? value : identifier;
      User user = enabledUserOrNull(() -> userRepository.getUserByEmail(value));
      if (user != null) {
        LOGGER.debug("Principal resolved by {}: {}", claim.key(), value);
        return user;
      }
    }

    throw new OAuthInvalidRequestException(
        ErrorCode.INVALID_ARGUMENT, "User not allowed: " + identifier);
  }

  private static String claimAsString(DecodedJWT decodedJWT, JwtClaim claim) {
    String value = decodedJWT.getClaim(claim.key()).asString();
    return value == null || value.isBlank() ? null : value;
  }

  /** The user if the lookup succeeds and the account is enabled; null otherwise. */
  private static User enabledUserOrNull(java.util.function.Supplier<User> lookup) {
    try {
      User user = lookup.get();
      return user != null && user.getState() == User.StateEnum.ENABLED ? user : null;
    } catch (Exception e) {
      return null;
    }
  }

  private Cookie createCookie(
      ServiceRequestContext ctx, String key, String value, String path, String maxAge) {
    return createCookie(ctx, key, value, path, Duration.parse(maxAge).getSeconds());
  }

  /**
   * A session cookie the browser will actually keep and send back.
   *
   * <p>Secure follows the address bar rather than being asserted unconditionally: a browser drops a
   * Secure cookie arriving over plain HTTP, which is how the UI is reached in a local or
   * internal-network deployment, and the sign-in then appears to succeed while leaving no session.
   *
   * <p>SameSite is Lax rather than Strict because the browser comes back from the identity provider
   * on a cross-site navigation: a Strict cookie is withheld on exactly that request, so the state
   * cookie would be missing at /callback and the new session cookie would not be sent to the page
   * the callback redirects to. Lax still withholds it from cross-site subrequests.
   */
  private Cookie createCookie(
      ServiceRequestContext ctx, String key, String value, String path, long maxAgeSeconds) {
    return Cookie.builder(key, value)
        .path(path)
        .maxAge(maxAgeSeconds)
        .httpOnly(true)
        .secure(browserUsesHttps(ctx))
        .sameSite("Lax")
        .build();
  }

  /** Whether the browser reached this server over HTTPS, read the same way callbackUrl reads it. */
  private boolean browserUsesHttps(ServiceRequestContext ctx) {
    String base = serverProperties.getExternalUrl();
    if (base != null) {
      return base.regionMatches(true, 0, "https://", 0, "https://".length());
    }
    String forwardedProto =
        firstValue(ctx.request().headers().get(HttpHeaderNames.X_FORWARDED_PROTO));
    if (forwardedProto != null) {
      return forwardedProto.equalsIgnoreCase("https");
    }
    return ctx.sessionProtocol().isTls();
  }

  // NOTE:
  // When specifying `application/x-www-form-urlencoded` as the content type in the OpenAPI schema,
  // the OpenAPI Generator does not create request models from the schema.
  // Moreover, directly accessing parameters from the body without a model causes issues with
  // Armeria, particularly when the `ext` query parameter is included.
  //
  // To resolve this, instead of redefining a request model solely for Armeria's parameter
  // injection,
  // a `RequestConverterFunction` for `OAuthTokenExchangeRequest` is implemented here.
  // This approach ensures a single model is used across both the `controlApi` and `cli` projects,
  // preserving the principle of a single source of truth.
  //
  // SEE:
  // - https://armeria.dev/docs/server-annotated-service/#getting-a-query-parameter
  // - https://armeria.dev/docs/server-annotated-service/#injecting-a-parameter-as-an-enum-type
  private static class ToOAuthTokenExchangeFormConverter implements RequestConverterFunction {
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Object convertRequest(
        ServiceRequestContext ctx,
        AggregatedHttpRequest request,
        Class<?> expectedResultType,
        @Nullable ParameterizedType expectedParameterizedResultType) {
      MediaType contentType = request.contentType();
      if (expectedResultType == OAuthTokenExchangeForm.class
          && contentType != null
          && contentType.belongsTo(MediaType.FORM_DATA)) {
        Map<String, String> form =
            QueryParams.fromQueryString(
                    request.content(contentType.charset(StandardCharsets.UTF_8)))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return mapper.convertValue(form, OAuthTokenExchangeForm.class);
      }
      return RequestConverterFunction.fallthrough();
    }
  }
}
