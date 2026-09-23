package io.unitycatalog.server.utils;

import static io.unitycatalog.server.security.SecurityContext.Issuers.INTERNAL;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.JwkProviderBuilder;
import com.auth0.jwk.SigningKeyNotFoundException;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.Verification;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.exception.OAuthInvalidClientException;
import io.unitycatalog.server.exception.OAuthInvalidRequestException;
import io.unitycatalog.server.security.SecurityContext;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JwksOperations {

  // Discovery and JWKS fetches go to a third party over the public internet; without a bound a
  // slow or unreachable identity provider would pin every token exchange for as long as the
  // socket stays open.
  private static final Duration IDP_TIMEOUT = Duration.ofSeconds(10);

  private final WebClient webClient =
      WebClient.builder().responseTimeout(IDP_TIMEOUT).writeTimeout(IDP_TIMEOUT).build();
  private static final ObjectMapper mapper = new ObjectMapper();
  // One provider per OIDC issuer, resolved through discovery once and then reused; the provider
  // itself caches keys by kid and re-fetches on an unseen kid, so key rotation still works. A
  // changed jwks_uri needs a restart, the same as server.allowed-issuers.
  private final ConcurrentMap<String, JwkProvider> discoveredProviders = new ConcurrentHashMap<>();
  private final SecurityContext securityContext;
  private final ServerProperties serverProperties;

  private static final Logger LOGGER = LoggerFactory.getLogger(JwksOperations.class);

  public JwksOperations(SecurityContext securityContext) {
    this(securityContext, null);
  }

  public JwksOperations(SecurityContext securityContext, ServerProperties serverProperties) {
    this.securityContext = securityContext;
    this.serverProperties = serverProperties;
  }

  @SneakyThrows
  public JWTVerifier verifierForIssuerAndKey(
      String issuer, String keyId, String alg, List<String> audiences) {
    JwkProvider jwkProvider = loadJwkProvider(issuer);
    Jwk jwk = jwkProvider.get(keyId);

    Algorithm algorithm = algorithmForJwk(jwk, alg);

    Verification builder = JWT.require(algorithm).withIssuer(issuer);
    if (audiences != null && !audiences.isEmpty()) {
      builder.withAnyOfAudience(audiences.toArray(new String[0]));
    }
    return builder.build();
  }

  @SneakyThrows
  private Algorithm algorithmForJwk(Jwk jwk, String alg) {
    String keyType = jwk.getType();

    return switch (keyType) {
      case "RSA" -> switch (alg) {
        case "RS256" -> Algorithm.RSA256((RSAPublicKey) jwk.getPublicKey(), null);
        case "RS384" -> Algorithm.RSA384((RSAPublicKey) jwk.getPublicKey(), null);
        case "RS512" -> Algorithm.RSA512((RSAPublicKey) jwk.getPublicKey(), null);
        default -> throw new OAuthInvalidClientException(ErrorCode.ABORTED,
                String.format("Unsupported RSA algorithm: %s", alg));
      };
      case "EC" -> switch (alg) {
        case "ES256" -> Algorithm.ECDSA256((ECPublicKey) jwk.getPublicKey(), null);
        case "ES384" -> Algorithm.ECDSA384((ECPublicKey) jwk.getPublicKey(), null);
        case "ES512" -> Algorithm.ECDSA512((ECPublicKey) jwk.getPublicKey(), null);
        default -> throw new OAuthInvalidClientException(ErrorCode.ABORTED,
                String.format("Unsupported ECDSA algorithm: %s", alg));
      };
      default -> throw new OAuthInvalidClientException(ErrorCode.ABORTED,
              String.format("Unsupported key type: %s", keyType));
    };
  }

  @SneakyThrows
  public JwkProvider loadJwkProvider(String issuer) {
    LOGGER.debug("Loading JwkProvider for issuer '{}'", issuer);
    if (issuer.equals(INTERNAL)) {
      // Return our own "self-signed" provider, for easy mode.
      // TODO: This should be configurable
      Path certsFile = securityContext.getCertsFile();
      return new JwkProviderBuilder(certsFile.toUri().toURL()).cached(false).build();
    } else {
      // Two trust chains share this method (issue #15). Relyt instances are bare identifiers whose
      // public keys are registered in the static external JWKS file; OIDC providers such as Entra
      // ID publish theirs through discovery. The file used to win unconditionally whenever it was
      // configured -- which the deployment template does by default -- so an OIDC token never
      // reached discovery and failed with "key not registered for issuer". Route on whether the
      // issuer has a key registered in the file instead: registered -> file, scoped to that
      // issuer (see IssuerScopedJwkProvider, which keeps one instance's key from vouching for
      // another allowlisted issuer); otherwise -> discovery.
      if (knownIssuers().contains(issuer)) {
        Path jwksPath = Path.of(serverProperties.getExternalJwksFile());
        LOGGER.debug("Using static external JWKS file '{}' for issuer '{}'", jwksPath, issuer);
        JwkProvider fileProvider =
            new JwkProviderBuilder(jwksPath.toUri().toURL()).cached(false).build();
        return new IssuerScopedJwkProvider(fileProvider, issuer);
      }

      return discoveredProviders.computeIfAbsent(issuer, this::discoverJwkProvider);
    }
  }

  /**
   * Resolves an OIDC issuer's JWKS through its well-known configuration. Called once per issuer
   * (see {@link #discoveredProviders}); the returned provider caches keys and rate-limits fetches.
   */
  @SneakyThrows
  private JwkProvider discoverJwkProvider(String issuer) {
    // Get the JWKS from the OIDC well-known location described here
    // https://openid.net/specs/openid-connect-discovery-1_0-21.html#ProviderConfig

    if (!issuer.startsWith("https://") && !issuer.startsWith("http://")) {
      issuer = "https://" + issuer;
    }

    String wellKnownConfigUrl = issuer;

    if (!wellKnownConfigUrl.endsWith("/")) {
      wellKnownConfigUrl += "/";
    }

    var path = wellKnownConfigUrl + ".well-known/openid-configuration";
    LOGGER.debug("path: {}", path);

    // A provider that is down, unreachable or slow (see IDP_TIMEOUT) is an authentication
    // failure for this token, not a server fault: surface it as a 401 with a JSON body rather than
    // letting the client's CompletionException escape as a bodyless 500.
    String response;
    try {
      response = webClient.get(path).aggregate().join().contentUtf8();
    } catch (RuntimeException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      throw new OAuthInvalidRequestException(
          ErrorCode.UNAUTHENTICATED,
          String.format(
              "Could not fetch the OIDC configuration of issuer '%s': %s",
              issuer, cause.getMessage()),
          e);
    }

    Map<String, Object> configMap;
    try {
      configMap = mapper.readValue(response, new TypeReference<>() {});
    } catch (IOException e) {
      throw new OAuthInvalidRequestException(
          ErrorCode.UNAUTHENTICATED,
          String.format("Unreadable OIDC configuration from issuer '%s'", issuer),
          e);
    }

    if (configMap == null || configMap.isEmpty()) {
      throw new OAuthInvalidRequestException(
          ErrorCode.ABORTED, "Could not get issuer configuration");
    }

    String configIssuer = (String) configMap.get("issuer");
    String configJwksUri = (String) configMap.get("jwks_uri");

    if (!issuerMatchesConfiguration(configIssuer, issuer)) {
      throw new OAuthInvalidRequestException(
          ErrorCode.ABORTED, "Issuer doesn't match configuration");
    }

    if (configJwksUri == null) {
      throw new OAuthInvalidRequestException(ErrorCode.ABORTED, "JWKS configuration missing");
    }

    // Keys are cached by kid; an unseen kid (rotation) triggers a fetch, bounded by the rate
    // limiter so a stream of bogus kids cannot hammer the provider.
    return new JwkProviderBuilder(URI.create(configJwksUri).toURL())
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build();
  }

  /**
   * Whether the issuer advertised by a discovery document is the one the token claims.
   *
   * <p>Single-tenant providers advertise their exact issuer and a plain comparison suffices. Entra
   * ID's multi-tenant endpoints ({@code common}, {@code organizations}) instead advertise the
   * literal template {@code https://login.microsoftonline.com/{tenantid}/v2.0} while tokens carry
   * the real tenant GUID in that position, so the two never compare equal. A {@code {tenantid}}
   * path segment therefore matches any single segment; every other segment, and the scheme and
   * host, must still match exactly. Exact-issuer configurations are unaffected.
   */
  static boolean issuerMatchesConfiguration(String configIssuer, String issuer) {
    if (configIssuer == null || issuer == null) {
      return false;
    }
    if (configIssuer.equals(issuer)) {
      return true;
    }
    if (!configIssuer.contains("{tenantid}")) {
      return false;
    }
    String[] expected = configIssuer.split("/");
    String[] actual = issuer.split("/");
    if (expected.length != actual.length) {
      return false;
    }
    for (int i = 0; i < expected.length; i++) {
      if (expected[i].equals("{tenantid}")) {
        if (actual[i].isEmpty()) {
          return false;
        }
        continue;
      }
      if (!expected[i].equals(actual[i])) {
        return false;
      }
    }
    return true;
  }

  /**
   * The set of issuers trusted via the hot-reloaded external JWKS file. Every JWK in the file
   * carries an {@code "issuer"} member (see {@link IssuerScopedJwkProvider}); this returns the
   * distinct set of those issuers. The file is read fresh on every call (no caching, matching
   * {@link #loadJwkProvider}'s {@code .cached(false)}), so appending a new DWSU's key to the JWKS
   * ConfigMap takes effect immediately, without restarting the server (issue #4). Returns an empty
   * set when no external JWKS file is configured, missing, or unreadable (fail-closed).
   */
  public Set<String> knownIssuers() {
    String externalJwksFile =
        serverProperties != null ? serverProperties.getExternalJwksFile() : null;
    if (externalJwksFile == null || externalJwksFile.isBlank()) {
      return Set.of();
    }
    Path jwksPath = Path.of(externalJwksFile);
    if (!Files.exists(jwksPath)) {
      LOGGER.warn("Configured external JWKS file '{}' does not exist", jwksPath);
      return Set.of();
    }
    try {
      JsonNode keys = mapper.readTree(jwksPath.toFile()).path("keys");
      Set<String> issuers = new HashSet<>();
      for (JsonNode key : keys) {
        String issuer = key.path("issuer").asText(null);
        if (issuer != null && !issuer.isBlank()) {
          issuers.add(issuer);
        }
      }
      return issuers;
    } catch (IOException e) {
      LOGGER.warn("Failed to read external JWKS file '{}' for issuer discovery", jwksPath, e);
      return Set.of();
    }
  }

  /**
   * Wraps a JWKS provider to bind each key to the issuer it was registered for. Every JWK in the
   * static external JWKS file must carry an {@code "issuer"} member; a key is returned only when
   * that member equals the token's claimed issuer. This prevents a confused-issuer attack where a
   * file shared across instances would otherwise let one instance's key (selected only by {@code
   * kid}) sign a token accepted as a different allowlisted issuer.
   */
  private static final class IssuerScopedJwkProvider implements JwkProvider {
    private final JwkProvider delegate;
    private final String expectedIssuer;

    IssuerScopedJwkProvider(JwkProvider delegate, String expectedIssuer) {
      this.delegate = delegate;
      this.expectedIssuer = expectedIssuer;
    }

    @Override
    public Jwk get(String keyId) throws JwkException {
      Jwk jwk = delegate.get(keyId);
      Object keyIssuer = jwk.getAdditionalAttributes().get("issuer");
      if (keyIssuer == null || !expectedIssuer.equals(keyIssuer.toString())) {
        throw new SigningKeyNotFoundException(
            String.format(
                "JWKS key '%s' is not registered for issuer '%s'", keyId, expectedIssuer),
            null);
      }
      return jwk;
    }
  }
}

