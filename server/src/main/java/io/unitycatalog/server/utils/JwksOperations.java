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
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
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
import java.util.concurrent.CompletionException;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JwksOperations {

  /**
   * Timeout for reaching a remote identity provider. Applies to the discovery document here and,
   * via {@code JwkProviderBuilder.timeouts}, to the JWKS fetch. Without it a slow IdP blocks the
   * calling thread indefinitely.
   */
  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

  private final WebClient webClient = WebClient.builder().responseTimeout(HTTP_TIMEOUT).build();
  private static final ObjectMapper mapper = new ObjectMapper();
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
      // Trusted external issuers (e.g. Relyt instances doing token-exchange) are bare
      // identifiers, not OIDC providers: their public keys are registered locally in a static
      // JWKS file instead of being discovered over the network. Because a single file can hold
      // keys for multiple issuers, each JWK must carry an "issuer" member and a key is only
      // accepted for the issuer it was registered to (see IssuerScopedJwkProvider) — otherwise
      // one registered instance could sign tokens accepted as another allowlisted issuer.

      // Route per issuer. The static file is authoritative only for the issuers it DECLARES (each
      // key carries an "issuer" member; see IssuerScopedJwkProvider). Anything else -- notably
      // Microsoft Entra ID, whose keys rotate and cannot live in a hand-maintained file -- is
      // resolved by OIDC discovery. Testing "does the file declare this issuer" rather than "does
      // the file exist" is what makes the two trust sources coexist in one deployment.
      if (knownIssuers().contains(issuer)) {
        Path jwksPath = Path.of(serverProperties.getExternalJwksFile());
        LOGGER.debug("Issuer '{}': resolving keys from static JWKS file '{}'", issuer, jwksPath);
        JwkProvider fileProvider =
            new JwkProviderBuilder(jwksPath.toUri().toURL()).cached(false).build();
        return new IssuerScopedJwkProvider(fileProvider, issuer);
      }

      LOGGER.debug("Issuer '{}': resolving keys by OIDC discovery", issuer);

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

      AggregatedHttpResponse discoveryResponse;
      try {
        discoveryResponse = webClient.get(path).aggregate().join();
      } catch (CompletionException e) {
        if (e.getCause() instanceof ResponseTimeoutException) {
          throw new OAuthInvalidRequestException(
              ErrorCode.DEADLINE_EXCEEDED,
              "Timed out fetching the OIDC configuration for issuer " + issuer);
        }
        throw new OAuthInvalidRequestException(
            ErrorCode.UNAVAILABLE,
            "Could not reach the identity provider for issuer " + issuer,
            e);
      }

      if (!discoveryResponse.status().isSuccess()) {
        throw new OAuthInvalidRequestException(
            ErrorCode.UNAVAILABLE,
            String.format(
                "Identity provider returned HTTP %d for the OIDC configuration of issuer %s",
                discoveryResponse.status().code(), issuer));
      }

      String response = discoveryResponse.contentUtf8();

      // TODO: We should cache this. No need to fetch it each time.
      Map<String, Object> configMap = mapper.readValue(response, new TypeReference<>() {});

      if (configMap == null || configMap.isEmpty()) {
        throw new OAuthInvalidRequestException(ErrorCode.UNAVAILABLE,
            "Could not get issuer configuration");
      }

      String configIssuer = (String) configMap.get("issuer");
      String configJwksUri = (String) configMap.get("jwks_uri");

      if (!configIssuer.equals(issuer)) {
        throw new OAuthInvalidRequestException(ErrorCode.ABORTED,
            "Issuer doesn't match configuration");
      }

      if (configJwksUri == null) {
        throw new OAuthInvalidRequestException(ErrorCode.ABORTED, "JWKS configuration missing");
      }

      // TODO: Or maybe just cache the provider for reuse.
      return new JwkProviderBuilder(URI.create(configJwksUri).toURL()).cached(false).build();
    }
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

