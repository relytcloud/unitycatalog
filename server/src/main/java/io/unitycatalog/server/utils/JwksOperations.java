package io.unitycatalog.server.utils;

import static io.unitycatalog.server.security.SecurityContext.Issuers.INTERNAL;

import com.auth0.jwk.InvalidPublicKeyException;
import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.JwkProviderBuilder;
import com.auth0.jwk.NetworkException;
import com.auth0.jwk.RateLimitReachedException;
import com.auth0.jwk.SigningKeyNotFoundException;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.Verification;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.exception.OAuthInvalidClientException;
import io.unitycatalog.server.exception.OAuthInvalidRequestException;
import io.unitycatalog.server.security.SecurityContext;
import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JwksOperations {

  /**
   * Bounds both the discovery-document fetch here and the JWKS fetch, via {@code
   * JwkProviderBuilder.timeouts} in {@link #remoteProvider}. Without it a slow IdP blocks the
   * calling thread indefinitely.
   */
  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

  /**
   * Remote key cache: entries and lifetime. A cache miss on an unknown kid still triggers a fetch,
   * which is what lets key rotation work; the rate limit is what stops that being abused. Entra
   * commonly publishes around six signing keys and rotates them, so the cache is sized well clear
   * of that -- an eviction buys nothing and costs a network round trip on the next exchange.
   */
  private static final long KEY_CACHE_SIZE = 32;

  private static final long KEY_CACHE_TTL_HOURS = 24;

  /**
   * Rate limit on remote key lookups: a burst of {@link #RATE_LIMIT_BUCKET} tokens, refilled one
   * token every {@link #RATE_LIMIT_REFILL_PERIOD_SECONDS} seconds -- i.e. ten lookups per minute.
   *
   * <p>UNIT TRAP, do not "simplify" this back to {@code (10, 10, TimeUnit.MINUTES)}. {@code
   * JwkProviderBuilder.rateLimited(size, rate, unit)} does NOT take a count per unit: {@code
   * BucketImpl.getRatePerToken()} returns {@code unit.toMillis(rate)}, which is the refill PERIOD
   * for ONE token. {@code (10, 10, MINUTES)} therefore means one token every ten minutes, not ten
   * per minute; ten junk requests would drain the bucket and keep every uncached key lookup
   * failing for over an hour. Six seconds per token is ten lookups per minute, which is what this
   * is meant to allow.
   */
  private static final long RATE_LIMIT_BUCKET = 10;

  private static final long RATE_LIMIT_REFILL_PERIOD_SECONDS = 6;

  /** How long a resolved jwks_uri is reused before the discovery document is re-read. */
  private static final Duration DISCOVERY_TTL = Duration.ofHours(24);

  /**
   * Matches every all-numeric host form, so an IPv4 literal can be told from a DNS name without
   * resolving one. Deliberately wider than a dotted quad: {@code https://2130706433/} is the
   * decimal spelling of {@code 127.0.0.1}, and {@code InetAddress} reads it as such, so a
   * quad-only pattern would classify it as a DNS name and wave it through. No DNS name is
   * all-digits, so nothing real is caught by this.
   */
  private static final Pattern NUMERIC_HOST = Pattern.compile("\\d+(\\.\\d+)*");

  /**
   * How often a failure-path log line may be repeated for one issuer. A failed discovery is never
   * cached -- see {@link #discover} -- so during an identity-provider outage every token exchange
   * runs the whole path again. Without a cooldown that is one WARN per exchange, which buries the
   * rest of the log exactly when it is being read.
   */
  private static final Duration LOG_COOLDOWN = Duration.ofSeconds(60);

  private record CachedDiscovery(String jwksUri, JwkProvider provider, Instant fetchedAt) {}

  private final Map<String, CachedDiscovery> discoveryCache = new ConcurrentHashMap<>();

  /** Per-issuer single-flight locks for {@link #discover}. */
  private final Map<String, Object> discoveryLocks = new ConcurrentHashMap<>();

  private final LogCooldown fallthroughWarnCooldown = new LogCooldown(LOG_COOLDOWN);

  private final LogCooldown remoteKeyFetchWarnCooldown = new LogCooldown(LOG_COOLDOWN);

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

  /**
   * Where a key set comes from. This is the one fact that decides how failing to obtain it should
   * be reported, and it is known only where the provider is built -- {@link #resolveJwkProvider}
   * routes between three sources. Carrying it to the catch site is what keeps the routing decision
   * from being re-derived, and eventually drifting, somewhere downstream.
   */
  private enum KeySource {
    /** The server's own certs.json, written at startup by {@link SecurityContext}. */
    INTERNAL_CERTS,
    /** The static external JWKS file named by {@code server.external-jwks-file}. */
    EXTERNAL_JWKS_FILE,
    /** An identity provider's JWKS endpoint, found by OIDC discovery. */
    DISCOVERY;

    /** Whether the keys live in a file this server owns, rather than somewhere upstream. */
    boolean isLocalFile() {
      return this != DISCOVERY;
    }
  }

  /**
   * A provider together with the provenance of the keys it serves. {@code location} is the file
   * path for a local source and the issuer for a discovered one: it is what an error message names
   * so an operator knows which thing to go and fix.
   */
  private record ResolvedProvider(JwkProvider provider, KeySource source, String location) {}

  /**
   * jwks-rsa 0.22.1 wordings that mean the KEY SET ITSELF could not be produced, as opposed to
   * "the key set has no key with this kid". See {@link #keySetWasObtained}. Pinned by {@code
   * JwksKeyLookupClassificationTest#jwksRsaWordingsTheClassifierDependsOnAreUnchanged}, which
   * reads them back out of the library.
   */
  private static final String NO_KEYS_IN_SET = "No keys found in ";

  private static final String UNPARSEABLE_KEY_SET = "Failed to parse jwk from json";

  public JWTVerifier verifierForIssuerAndKey(
      String issuer, String keyId, String alg, List<String> audiences) {
    ResolvedProvider resolved = resolveJwkProvider(issuer);

    Algorithm algorithm;
    try {
      // Classified here, where the provenance of the key set is still known. Anything further
      // downstream sees only auth0's exception hierarchy, which cannot express the difference --
      // see keyLookupFailure.
      algorithm = algorithmForJwk(resolved.provider().get(keyId), alg);
    } catch (RateLimitReachedException e) {
      throw new OAuthInvalidRequestException(
          ErrorCode.UNAVAILABLE, "Too many signing-key lookups; retry shortly.", e);
    } catch (JwkException e) {
      throw keyLookupFailure(resolved, issuer, e);
    }

    Verification builder = JWT.require(algorithm).withIssuer(issuer);
    if (audiences != null && !audiences.isEmpty()) {
      builder.withAnyOfAudience(audiences.toArray(new String[0]));
    }
    return builder.build();
  }

  /**
   * Translate an auth0 key-lookup failure into this server's own error, from the PROVENANCE of the
   * key set rather than the class auth0 threw.
   *
   * <p>auth0's hierarchy cannot carry that decision. {@code UrlJwkProvider.getJwks()} fetches
   * through a plain {@code URLConnection} under a blanket {@code catch (IOException)} that
   * constructs a {@link NetworkException}, and both local sources here are {@code UrlJwkProvider}s
   * over {@code file:} URLs. Classifying on {@code NetworkException} therefore reported a deleted
   * or unreadable certs.json as "could not reach the identity provider": a 503 on every
   * authenticated API call, with the filename dropped, which load balancers and clients retry
   * instead of failing fast. Its mirror is an IdP answering 200 with {@code {"keys":[]}}, which is
   * a plain {@code SigningKeyNotFoundException} and so was reported as a rejected token.
   *
   * <p>One rule holds across all three branches: the message handed back to the caller never
   * carries a provider URL or a filesystem path. Every branch here is reachable by anyone who can
   * present a bearer token, so the location of the key set -- a server path, or the IdP's
   * jwks_uri -- is logged and never returned. The remote branch says only "the identity provider
   * for issuer X", where X is the issuer the caller's own token claimed.
   */
  private BaseException keyLookupFailure(
      ResolvedProvider resolved, String issuer, JwkException cause) {
    if (keySetWasObtained(cause)) {
      // The key set was read and is intact; it just holds no key with this kid. The only
      // genuinely rejected-token case -- and so the only one logged at DEBUG: nothing about the
      // server is wrong, and a caller can produce this at will by sending any 'kid'.
      //
      // auth0's wording names the key set's LOCATION -- "No key found in
      // file:/opt/uc/etc/conf/certs.json with kid ..." for a file-backed provider, or the IdP's
      // jwks_uri for a discovered one. That is why it is not passed through: the rule for this
      // whole method is that a client-facing message carries no provider URL and no filesystem
      // path, because AuthDecorator runs on every authenticated route and any bearer token at
      // all reaches it. The location belongs in the log, which is where an operator reads it.
      LOGGER.debug(
          "Issuer '{}': no signing key matched the presented kid ({})",
          issuer,
          resolved.location(),
          cause);
      return new OAuthInvalidClientException(
          ErrorCode.UNAUTHENTICATED,
          "No signing key matching the token's 'kid' is registered for issuer " + issuer,
          cause);
    }
    if (resolved.source().isLocalFile()) {
      LOGGER.error(
          "Issuer '{}': could not read signing keys from local file '{}'",
          issuer,
          resolved.location(),
          cause);
      // Deliberately generic. This is reached from AuthDecorator on every authenticated route,
      // and any garbage bearer token gets there, so the caller is effectively unauthenticated:
      // naming the file would hand out a server filesystem path, and cause.getMessage() carries
      // that same path inside auth0's "Cannot obtain jwks from url file:/..." wording. The
      // operator loses nothing -- the ERROR above has the path and the cause.
      return new BaseException(
          ErrorCode.INTERNAL,
          "The server could not read its configured signing keys. This is a server"
              + " key-configuration problem, not a problem with the token; see the server logs"
              + " for details.",
          cause);
    }
    if (remoteKeyFetchWarnCooldown.allow(issuer)) {
      // Throttled: an identity-provider outage fails every exchange, and the stack trace is worth
      // once a minute per issuer, not once per request.
      LOGGER.warn(
          "Issuer '{}': could not fetch signing keys from the identity provider", issuer, cause);
    }
    return new OAuthInvalidRequestException(
        ErrorCode.UNAVAILABLE,
        "Could not reach the identity provider to fetch the signing keys for issuer " + issuer,
        cause);
  }

  /**
   * Whether the key set itself was obtained, so the only thing that failed is finding the
   * requested kid in it.
   *
   * <p>This is the message-matching option, and a deliberate coupling to jwks-rsa 0.22.1 (pinned
   * in build.sbt). auth0 throws a plain {@link SigningKeyNotFoundException} for three different
   * conditions and separates them only by text: {@code "No keys found in <url>"} (a 200 with an
   * empty "keys" array), {@code "Failed to parse jwk from json"} (an entry that is not a JWK), and
   * {@code "No key found in <url> with kid <kid>"} (the kid miss). Only the fetch failure is
   * distinguishable by type, as {@link NetworkException}.
   *
   * <p>The match is fail-safe in the direction that matters: an unrecognised wording is read as a
   * kid miss, which is the 401 this code reported before -- never a new 503 for a token that is
   * simply wrong. The severe case, a local file that cannot be read, does not depend on this at
   * all; it is typed.
   */
  private static boolean keySetWasObtained(JwkException e) {
    if (e instanceof NetworkException) {
      // "Cannot obtain jwks from url ..." -- including, for a file: URL, any IOException at all.
      return false;
    }
    if (!(e instanceof SigningKeyNotFoundException)) {
      // e.g. InvalidPublicKeyException: a key was selected but its material does not parse, so
      // the key set is not usable. Not a statement about the caller's kid.
      return false;
    }
    String message = e.getMessage();
    if (message == null) {
      return true;
    }
    return !message.startsWith(NO_KEYS_IN_SET) && !message.equals(UNPARSEABLE_KEY_SET);
  }

  private Algorithm algorithmForJwk(Jwk jwk, String alg) throws InvalidPublicKeyException {
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

  public JwkProvider loadJwkProvider(String issuer) {
    return resolveJwkProvider(issuer).provider();
  }

  @SneakyThrows
  private ResolvedProvider resolveJwkProvider(String issuer) {
    LOGGER.debug("Loading JwkProvider for issuer '{}'", issuer);
    if (issuer.equals(INTERNAL)) {
      // Return our own "self-signed" provider, for easy mode.
      // TODO: This should be configurable
      Path certsFile = securityContext.getCertsFile();
      return new ResolvedProvider(
          new JwkProviderBuilder(certsFile.toUri().toURL()).cached(false).build(),
          KeySource.INTERNAL_CERTS,
          certsFile.toString());
    } else {
      // Route per issuer. The static JWKS file is authoritative only for the issuers it DECLARES
      // (each key carries an "issuer" member; see IssuerScopedJwkProvider). Those file-held
      // issuers (e.g. Relyt instances doing token-exchange) are bare identifiers, not OIDC
      // providers: their public keys are registered locally instead of being discovered over the
      // network. Because a single file can hold keys for multiple issuers, a key is only accepted
      // for the issuer it was registered to — otherwise one registered instance could sign tokens
      // accepted as another allowlisted issuer.
      //
      // Every other issuer -- notably Microsoft Entra ID, whose keys rotate and cannot live in a
      // hand-maintained file -- is resolved by OIDC discovery. Testing "does the file declare this
      // issuer" rather than "does the file exist" is what makes the two trust sources coexist in
      // one deployment.
      if (knownIssuers().contains(issuer)) {
        Path jwksPath = Path.of(serverProperties.getExternalJwksFile());
        LOGGER.debug("Issuer '{}': resolving keys from static JWKS file '{}'", issuer, jwksPath);
        JwkProvider fileProvider =
            new JwkProviderBuilder(jwksPath.toUri().toURL()).cached(false).build();
        return new ResolvedProvider(
            new IssuerScopedJwkProvider(fileProvider, issuer),
            KeySource.EXTERNAL_JWKS_FILE,
            jwksPath.toString());
      }

      String normalizedIssuer =
          issuer.startsWith("https://") || issuer.startsWith("http://")
              ? issuer
              : "https://" + issuer;

      CachedDiscovery cached = freshDiscovery(normalizedIssuer);
      if (cached != null) {
        return new ResolvedProvider(cached.provider(), KeySource.DISCOVERY, normalizedIssuer);
      }

      // Single-flight, per issuer: one thread fetches and the rest wait for its result, instead
      // of every concurrent token exchange pinning its own Armeria blocking thread on a 5-second
      // fetch. That is the cold-start case and, more importantly, the whole duration of an
      // identity-provider outage, because a failed discovery is deliberately never cached.
      //
      // The lock object -- not the fetch -- is what computeIfAbsent creates. Doing the fetch
      // inside computeIfAbsent would hold a ConcurrentHashMap bin lock across a network call,
      // blocking unrelated issuers that happen to hash to the same bin and risking the map's own
      // recursive-update failure. The map is keyed by normalized issuer and so is bounded by the
      // allow-list that has already admitted this issuer upstream.
      Object discoveryLock = discoveryLocks.computeIfAbsent(normalizedIssuer, key -> new Object());
      synchronized (discoveryLock) {
        // Re-checked on entry: while this thread waited, a peer may have completed discovery and
        // published its result. On a FAILURE the peer wrote nothing and released the lock, so
        // this thread goes on to retry -- failures are still never cached.
        CachedDiscovery landed = freshDiscovery(normalizedIssuer);
        if (landed != null) {
          return new ResolvedProvider(landed.provider(), KeySource.DISCOVERY, normalizedIssuer);
        }
        return new ResolvedProvider(
            discover(issuer, normalizedIssuer), KeySource.DISCOVERY, normalizedIssuer);
      }
    }
  }

  /** The cached provider for an issuer, while it is still inside {@link #DISCOVERY_TTL}. */
  private CachedDiscovery freshDiscovery(String normalizedIssuer) {
    CachedDiscovery cached = discoveryCache.get(normalizedIssuer);
    if (cached == null
        || Duration.between(cached.fetchedAt(), Instant.now()).compareTo(DISCOVERY_TTL) >= 0) {
      return null;
    }
    return cached;
  }

  /**
   * Fetch and validate the issuer's OIDC discovery document, build the key provider it names, and
   * cache it. Called with this issuer's discovery lock held, and only on a cache miss.
   *
   * <p>Nothing is written to the cache unless a usable provider was produced, so every failure
   * here -- unreachable, timed out, non-2xx, malformed, mismatched issuer, rejected jwks_uri -- is
   * retried by the next request rather than pinned for {@link #DISCOVERY_TTL}. Caching failures
   * would turn a transient upstream blip into a local outage that an unauthenticated caller can
   * trigger, since the token endpoint needs no credentials.
   */
  private JwkProvider discover(String issuer, String normalizedIssuer) {
    // Reached only past the cache check in resolveJwkProvider, so this fires on an actual
    // discovery fetch -- for a healthy issuer, at most once per DISCOVERY_TTL. That is what makes
    // INFO affordable here, and what lets an operator confirm caching works by seeing the line
    // once rather than per exchange.
    LOGGER.info("Issuer '{}': resolving keys by OIDC discovery", issuer);
    String externalJwksFile =
        serverProperties != null ? serverProperties.getExternalJwksFile() : null;
    if (shouldWarnOnDiscoveryFallthrough(issuer, externalJwksFile)
        && fallthroughWarnCooldown.allow(issuer)) {
      LOGGER.warn(
          "Issuer '{}' is not declared in the external JWKS file '{}', so its keys are being"
              + " resolved by OIDC discovery. If this issuer's keys are meant to come from the"
              + " file, check the 'issuer' member of its JWK entries.",
          issuer,
          externalJwksFile);
    }

    // Get the JWKS from the OIDC well-known location described here
    // https://openid.net/specs/openid-connect-discovery-1_0-21.html#ProviderConfig

    String wellKnownConfigUrl = normalizedIssuer;

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
            "Timed out fetching the OIDC configuration for issuer " + normalizedIssuer,
            e);
      }
      throw new OAuthInvalidRequestException(
          ErrorCode.UNAVAILABLE,
          "Could not reach the identity provider for issuer " + normalizedIssuer,
          e);
    }

    if (!discoveryResponse.status().isSuccess()) {
      throw new OAuthInvalidRequestException(
          ErrorCode.UNAVAILABLE,
          String.format(
              "Identity provider returned HTTP %d for the OIDC configuration of issuer %s",
              discoveryResponse.status().code(), normalizedIssuer));
    }

    String response = discoveryResponse.contentUtf8();

    // A 200 with a body that is not a JSON object is an upstream failure like any other, not a
    // programming error: without this the Jackson IOException escapes through @SneakyThrows,
    // matches no GlobalExceptionHandler branch, and surfaces as a bodyless HTTP 500.
    Map<String, Object> configMap;
    try {
      configMap = mapper.readValue(response, new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      throw new OAuthInvalidRequestException(
          ErrorCode.UNAVAILABLE,
          "Identity provider returned a malformed OIDC configuration for issuer "
              + normalizedIssuer,
          e);
    }

    if (configMap == null || configMap.isEmpty()) {
      throw new OAuthInvalidRequestException(ErrorCode.UNAVAILABLE,
          "Could not get issuer configuration");
    }

    String configIssuer = stringMember(configMap, "issuer", normalizedIssuer);
    String configJwksUri = stringMember(configMap, "jwks_uri", normalizedIssuer);

    // Null when the document has no "issuer" member at all -- a mismatch like any other, and
    // checked explicitly so a malformed document cannot NPE its way to a bodyless 500.
    if (configIssuer == null || !configIssuer.equals(normalizedIssuer)) {
      throw new OAuthInvalidRequestException(ErrorCode.ABORTED,
          "Issuer doesn't match configuration");
    }

    if (configJwksUri == null) {
      throw new OAuthInvalidRequestException(ErrorCode.ABORTED, "JWKS configuration missing");
    }

    // Validated before the provider is built, so a rejected jwks_uri is never cached for
    // DISCOVERY_TTL and no connection is ever opened to it.
    JwkProvider provider = remoteProvider(configJwksUri, normalizedIssuer);
    discoveryCache.put(
        normalizedIssuer, new CachedDiscovery(configJwksUri, provider, Instant.now()));
    return provider;
  }

  /**
   * Whether an issuer reaching OIDC discovery, while a static JWKS file is configured, is worth
   * warning about.
   *
   * <p>The warning exists for one failure mode: an issuer that is MEANT to be file-held, whose
   * {@code issuer} member is typo'd, silently reroutes to discovery and then fails there. A
   * configured Entra tenant reaching discovery is not that -- discovery is the only path Entra has,
   * because its keys rotate and cannot live in a hand-maintained file. Warning about it in every
   * mixed JWKS-file + Entra deployment is how an operator is trained to ignore the line that was
   * meant to catch the typo.
   *
   * <p>Extracted so the rule itself can be unit-tested. Asserting on log output would pin the
   * wording instead of the decision, and would break on any reformatting of the message.
   *
   * @param externalJwksFile the configured static JWKS file, or null/blank when there is none
   */
  boolean shouldWarnOnDiscoveryFallthrough(String issuer, String externalJwksFile) {
    if (externalJwksFile == null || externalJwksFile.isBlank()) {
      // Nothing is meant to be file-held, so nothing can have fallen through by mistake.
      return false;
    }
    // Null for the single-argument constructor, which carries no ServerProperties at all, and for
    // a deployment with no Entra tenant configured.
    String entraIssuer = serverProperties != null ? serverProperties.getEntraIssuer() : null;
    return !issuer.equals(entraIssuer);
  }

  /**
   * Read a member of a discovery document as a string. A member that is present but of another
   * JSON type used to reach a {@code (String)} cast and escape as a ClassCastException -- no
   * GlobalExceptionHandler branch matches it, so it surfaced as a bodyless HTTP 500. It is an
   * upstream failure like any other malformed document, so it is reported the same way.
   *
   * @return the member's value, or null when the document has no such member
   */
  private static String stringMember(Map<String, Object> configMap, String member, String issuer) {
    Object value = configMap.get(member);
    if (value == null || value instanceof String) {
      return (String) value;
    }
    throw new OAuthInvalidRequestException(
        ErrorCode.UNAVAILABLE,
        String.format(
            "Identity provider returned a malformed OIDC configuration for issuer %s: '%s' is not"
                + " a string",
            issuer, member));
  }

  /**
   * Validate a {@code jwks_uri} before anything opens a connection to it.
   *
   * <p>{@code UrlJwkProvider} hands the URL straight to {@code URL.openConnection()} with no
   * scheme restriction, and this endpoint is unauthenticated, so an identity provider -- or
   * anything able to answer as one -- could otherwise aim the fetch at {@code file:///}, {@code
   * ftp://} or a cloud metadata address, with the outcome observable through the 401-vs-503 split.
   * The rule is:
   *
   * <ul>
   *   <li>{@code https} to a host that is not loopback, link-local, any-local or private: allowed
   *   <li>{@code http} to a loopback host: allowed, and only so a local test IdP still works
   *   <li>everything else, including {@code https://169.254.169.254} and the RFC 1918 ranges:
   *       rejected
   * </ul>
   *
   * <p>Only a literal address is classified. A DNS name is deliberately not resolved here:
   * resolving it would open a rebinding race between this check and the fetch, so it would buy no
   * real defence for the complexity. Everything rejected is an unusable upstream configuration,
   * hence {@link ErrorCode#UNAVAILABLE}.
   *
   * <p>The URL itself is attacker-supplied, so it is logged at debug only, and the message names
   * the scheme or the class of address -- what an operator needs -- and nothing more.
   */
  private static URL validatedJwksUrl(String jwksUri, String issuer) {
    URI uri;
    try {
      uri = new URI(jwksUri);
    } catch (URISyntaxException e) {
      LOGGER.debug("Issuer '{}': jwks_uri is not a valid URI: '{}'", issuer, jwksUri);
      throw new OAuthInvalidRequestException(
          ErrorCode.UNAVAILABLE,
          "Identity provider published an unusable jwks_uri for issuer " + issuer,
          e);
    }

    if (!uri.isAbsolute() || uri.getScheme() == null) {
      LOGGER.debug("Issuer '{}': jwks_uri is not an absolute URL: '{}'", issuer, jwksUri);
      throw new OAuthInvalidRequestException(
          ErrorCode.UNAVAILABLE,
          "Identity provider published a jwks_uri that is not an absolute URL, for issuer "
              + issuer);
    }

    String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    if (!"https".equals(scheme) && !"http".equals(scheme)) {
      LOGGER.debug("Issuer '{}': rejected jwks_uri '{}'", issuer, jwksUri);
      throw new OAuthInvalidRequestException(
          ErrorCode.UNAVAILABLE,
          String.format(
              "Identity provider published a jwks_uri with an unsupported scheme '%s' for issuer"
                  + " %s; only https is accepted",
              scheme, issuer));
    }

    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      // Also the fail-closed answer for an authority Java declines to parse into a host.
      LOGGER.debug("Issuer '{}': jwks_uri has no usable host: '{}'", issuer, jwksUri);
      throw new OAuthInvalidRequestException(
          ErrorCode.UNAVAILABLE,
          "Identity provider published a jwks_uri with no usable host, for issuer " + issuer);
    }

    boolean loopback = isLoopbackHost(host);
    if ("http".equals(scheme) && !loopback) {
      LOGGER.debug("Issuer '{}': rejected plain-http jwks_uri '{}'", issuer, jwksUri);
      throw new OAuthInvalidRequestException(
          ErrorCode.UNAVAILABLE,
          "Identity provider published a plain-http jwks_uri to a non-loopback host, for issuer "
              + issuer
              + "; only https is accepted");
    }
    if ("https".equals(scheme) && isInternalAddress(host)) {
      LOGGER.debug("Issuer '{}': rejected internal-address jwks_uri '{}'", issuer, jwksUri);
      throw new OAuthInvalidRequestException(
          ErrorCode.UNAVAILABLE,
          "Identity provider published a jwks_uri pointing at a loopback, link-local or private"
              + " address, for issuer "
              + issuer);
    }

    try {
      return uri.toURL();
    } catch (MalformedURLException | IllegalArgumentException e) {
      LOGGER.debug("Issuer '{}': jwks_uri is not a usable URL: '{}'", issuer, jwksUri);
      throw new OAuthInvalidRequestException(
          ErrorCode.UNAVAILABLE,
          "Identity provider published an unusable jwks_uri for issuer " + issuer,
          e);
    }
  }

  /**
   * Whether a URI host names a loopback endpoint. {@code localhost} counts by name, because that
   * is what a local test IdP is reached by; everything else must be a loopback literal.
   */
  private static boolean isLoopbackHost(String host) {
    String bare = stripBrackets(host);
    if (bare.equalsIgnoreCase("localhost")) {
      return true;
    }
    InetAddress literal = literalAddress(bare);
    return literal != null && literal.isLoopbackAddress();
  }

  /**
   * Whether a URI host names an address this server must never fetch from: loopback, link-local
   * (which is where 169.254.169.254, the cloud metadata address, lives), any-local, or a private /
   * unique-local range. A DNS name that is not {@code localhost} is not classified -- see {@link
   * #validatedJwksUrl} on why it is not resolved.
   */
  private static boolean isInternalAddress(String host) {
    String bare = stripBrackets(host);
    String lower = bare.toLowerCase(Locale.ROOT);
    if (lower.equals("localhost") || lower.endsWith(".localhost")) {
      return true;
    }
    InetAddress literal = literalAddress(bare);
    if (literal == null) {
      return false;
    }
    if (literal.isLoopbackAddress()
        || literal.isLinkLocalAddress()
        || literal.isAnyLocalAddress()
        || literal.isSiteLocalAddress()) {
      return true;
    }
    // IPv6 unique-local (fc00::/7). isSiteLocalAddress only covers the deprecated fec0::/10.
    byte[] octets = literal.getAddress();
    return octets.length == 16 && (octets[0] & 0xFE) == 0xFC;
  }

  /**
   * The address a host denotes when -- and only when -- it is written as a literal. Returning null
   * for anything else is what keeps this free of DNS lookups: {@code InetAddress.getByName} does
   * not resolve a literal, and is never reached with a name.
   */
  private static InetAddress literalAddress(String host) {
    if (!NUMERIC_HOST.matcher(host).matches() && host.indexOf(':') < 0) {
      return null;
    }
    try {
      return InetAddress.getByName(host);
    } catch (UnknownHostException e) {
      return null;
    }
  }

  /** {@code URI.getHost()} keeps the brackets around an IPv6 literal; the classifiers cannot. */
  private static String stripBrackets(String host) {
    return host.startsWith("[") && host.endsWith("]")
        ? host.substring(1, host.length() - 1)
        : host;
  }

  /**
   * A provider for a remote JWKS endpoint. Unlike the static file — which stays uncached so a
   * newly appended DWSU key takes effect without a restart — a remote provider is cached, rate
   * limited and given explicit timeouts, because it is a network dependency on every token
   * exchange.
   *
   * <p>Package-private so a test can inspect the configured bucket; building a provider opens no
   * connection.
   */
  JwkProvider remoteProvider(String jwksUri, String issuer) {
    int timeoutMillis = (int) HTTP_TIMEOUT.toMillis();
    return new JwkProviderBuilder(validatedJwksUrl(jwksUri, issuer))
        .cached(KEY_CACHE_SIZE, KEY_CACHE_TTL_HOURS, TimeUnit.HOURS)
        .rateLimited(RATE_LIMIT_BUCKET, RATE_LIMIT_REFILL_PERIOD_SECONDS, TimeUnit.SECONDS)
        .timeouts(timeoutMillis, timeoutMillis)
        .build();
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
   * A per-key "has this been logged recently" gate, for log lines on a path that repeats for as
   * long as an upstream failure lasts.
   *
   * <p>Only the FAILURE paths are gated. The discovery INFO already fires at most once per issuer
   * per {@link #DISCOVERY_TTL}, because it sits below the cache-hit return, so throttling it would
   * only risk hiding the one line an operator uses to confirm caching works.
   *
   * <p>Timed on {@link System#nanoTime()}, which is monotonic: a wall-clock step (NTP, a DST jump)
   * cannot silence a whole issuer for hours or defeat the cooldown entirely. The compare-and-set
   * is what keeps a burst of concurrent failures to one line rather than one per thread; a thread
   * that loses the race simply does not log.
   */
  static final class LogCooldown {

    private final Map<String, AtomicLong> lastLoggedNanos = new ConcurrentHashMap<>();
    private final long cooldownNanos;

    LogCooldown(Duration cooldown) {
      this.cooldownNanos = cooldown.toNanos();
    }

    /** Whether the caller may log for this key now, taking the slot if so. */
    boolean allow(String key) {
      long now = System.nanoTime();
      AtomicLong lastLogged =
          lastLoggedNanos.computeIfAbsent(key, k -> new AtomicLong(now - cooldownNanos - 1));
      long previous = lastLogged.get();
      // Subtraction, not comparison: nanoTime has an arbitrary origin and may be negative, so
      // `now > previous + cooldown` can be wrong across the wrap that this form survives.
      if (now - previous < cooldownNanos) {
        return false;
      }
      return lastLogged.compareAndSet(previous, now);
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

