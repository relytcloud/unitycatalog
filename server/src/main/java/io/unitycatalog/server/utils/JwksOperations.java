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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.exception.OAuthInvalidClientException;
import io.unitycatalog.server.exception.OAuthInvalidRequestException;
import io.unitycatalog.server.security.SecurityContext;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JwksOperations {

  private final WebClient webClient = WebClient.builder().build();
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
      String externalJwksFile =
          serverProperties != null ? serverProperties.getExternalJwksFile() : null;
      if (externalJwksFile != null && !externalJwksFile.isBlank()) {
        Path jwksPath = Path.of(externalJwksFile);
        if (Files.exists(jwksPath)) {
          LOGGER.debug("Using static external JWKS file '{}' for issuer '{}'", jwksPath, issuer);
          JwkProvider fileProvider =
              new JwkProviderBuilder(jwksPath.toUri().toURL()).cached(false).build();
          return new IssuerScopedJwkProvider(fileProvider, issuer);
        }
        LOGGER.warn("Configured external JWKS file '{}' does not exist", jwksPath);
      }

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

      String response = webClient
          .get(path)
          .aggregate()
          .join()
          .contentUtf8();

      // TODO: We should cache this. No need to fetch it each time.
      Map<String, Object> configMap = mapper.readValue(response, new TypeReference<>() {});

      if (configMap == null || configMap.isEmpty()) {
        throw new OAuthInvalidRequestException(ErrorCode.ABORTED,
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

