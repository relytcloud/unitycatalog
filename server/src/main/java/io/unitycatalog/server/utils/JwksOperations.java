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

  /**
   * JVM flag that admits a plain-http {@code jwks_uri} to a loopback host. Off unless it is set to
   * {@code true}, so a production server accepts https and nothing else.
   *
   * <p>The exception exists for one thing: a test identity provider on loopback, which cannot
   * present a certificate any JVM trusts. Left on by default it is a standing SSRF primitive --
   * an identity provider this server already trusts, or anything that has taken one over, could
   * publish {@code http://127.0.0.1:<port>/} as its jwks_uri and have the server issue blind GETs
   * against its own loopback interface, one port at a time, with the outcome readable through the
   * 401-vs-503 split. A javadoc saying "only for local tests" does not stop that; this flag does.
   */
  static final String ALLOW_PLAIN_HTTP_LOOPBACK_PROPERTY =
      "io.unitycatalog.server.jwks.allowPlainHttpLoopback";

  private record CachedDiscovery(String jwksUri, JwkProvider provider, Instant fetchedAt) {}

  private final Map<String, CachedDiscovery> discoveryCache = new ConcurrentHashMap<>();

  /** Per-issuer single-flight locks for {@link #discover}. */
  private final Map<String, Object> discoveryLocks = new ConcurrentHashMap<>();

  private final LogCooldown fallthroughWarnCooldown = new LogCooldown(LOG_COOLDOWN);

  private final LogCooldown remoteKeyFetchWarnCooldown = new LogCooldown(LOG_COOLDOWN);

  private final LogCooldown localKeyFileErrorCooldown = new LogCooldown(LOG_COOLDOWN);

  private final LogCooldown jwksFileWarnCooldown = new LogCooldown(LOG_COOLDOWN);

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
    } catch (BaseException e) {
      // Already classified: a token this server refuses (an 'alg' it cannot use), not a failure
      // to obtain the key set. Re-thrown ahead of the net below so it keeps its own status.
      throw e;
    } catch (RuntimeException e) {
      // The net. auth0's key-lookup exceptions are CHECKED and a sibling hierarchy of
      // java-jwt's, so the two catches above cannot cover what com.auth0.jwk.Jwk throws from
      // getPublicKey(): it declares only InvalidKeySpecException, NoSuchAlgorithmException and
      // InvalidParameterSpecException, and for kty=RSA it first base64url-decodes the entry's
      // "n" member. An entry with no "n" throws NullPointerException, a non-base64url "n"
      // throws IllegalArgumentException, and an "n" that is a JSON number throws
      // ClassCastException out of Jwk's own (String) cast -- all RuntimeExceptions, which
      // reached GlobalExceptionHandler's RuntimeException branch and became a 500. A JWK whose
      // material does not decode makes the key set unusable in exactly the way an unparseable
      // one is, so it is classified by the same provenance rule: a server-configuration fault
      // for a file this server owns, an upstream fault for a discovered key set.
      //
      // Deliberately the whole of RuntimeException rather than that list: the point is that NO
      // failure of a third-party key decoder reaches a caller as a stack trace, and the list of
      // what it can throw is not ours to keep in step. A null "kty" lands here too, via the
      // switch in algorithmForJwk, and an unusable key set is the right answer for it.
      throw unusableKeySet(resolved, issuer, e);
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
    return unusableKeySet(resolved, issuer, cause);
  }

  /**
   * The key set itself could not be produced, so nothing has been decided about the caller's
   * token. Whose fault that is follows from the PROVENANCE alone: a file this server owns is the
   * server's own misconfiguration, and anything discovered is upstream.
   *
   * <p>Reached both from {@link #keyLookupFailure}, for the auth0 failures that mean "no key set",
   * and from {@link #verifierForIssuerAndKey}'s RuntimeException net, for a key whose material
   * does not decode. Same fault, same answer.
   *
   * <p>BOTH log lines are throttled per issuer. The remote one always was; the local one was not,
   * and it is on the hotter path of the two -- {@code AuthDecorator} resolves INTERNAL on every
   * authenticated route and /tokens reaches it with no credentials at all, so a deleted certs.json
   * meant one ERROR, with a stack trace, per request, for as long as the file stayed missing. That
   * is a caller-driven log-volume hole and it buries the one line an operator needs. Once a minute
   * per issuer says everything the first line said.
   */
  private BaseException unusableKeySet(ResolvedProvider resolved, String issuer, Throwable cause) {
    if (resolved.source().isLocalFile()) {
      if (localKeyFileErrorCooldown.allow(issuer)) {
        LOGGER.error(
            "Issuer '{}': could not read signing keys from local file '{}'",
            issuer,
            resolved.location(),
            cause);
      }
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

  /**
   * The verification algorithm for a key, chosen by the algorithm the TOKEN names.
   *
   * @param alg the token header's {@code alg}, which is attacker-supplied and may be absent
   */
  private Algorithm algorithmForJwk(Jwk jwk, String alg) throws InvalidPublicKeyException {
    // A JWT header is not required to carry an "alg" member: JWT.decode accepts one that does
    // not, and DecodedJWT.getAlgorithm() then returns null. A String switch on null throws
    // NullPointerException -- a RuntimeException, and so a sibling of every exception
    // verifierForIssuerAndKey used to catch -- which reached GlobalExceptionHandler's
    // RuntimeException branch and answered with a 500 carrying the NPE's stack trace. Every
    // route could be made to do it: AuthDecorator runs on all of them, and /tokens needs no
    // credentials at all. (A null 'kid' gets this far too -- UrlJwkProvider.get(null) returns
    // the sole key when the set holds exactly one, which is what certs.json holds.)
    //
    // Naming no algorithm is a token this server cannot verify, which is the same answer as
    // naming one it does not support: 401, at DEBUG, because any caller can produce it at will.
    if (alg == null || alg.isBlank()) {
      LOGGER.debug("Token rejected: no 'alg' in the token header");
      throw new OAuthInvalidClientException(
          ErrorCode.UNAUTHENTICATED, "The token header names no signature algorithm.");
    }
    // Not null-guarded: Jwk.fromValues rejects an entry with no "kty" before one can get here, so
    // a null would mean the key set is unusable rather than the token unverifiable -- which is
    // exactly what the RuntimeException net in verifierForIssuerAndKey concludes for the NPE.
    String keyType = jwk.getType();

    return switch (keyType) {
      case "RSA" -> switch (alg) {
        case "RS256" -> Algorithm.RSA256((RSAPublicKey) jwk.getPublicKey(), null);
        case "RS384" -> Algorithm.RSA384((RSAPublicKey) jwk.getPublicKey(), null);
        case "RS512" -> Algorithm.RSA512((RSAPublicKey) jwk.getPublicKey(), null);
        default -> throw new OAuthInvalidClientException(ErrorCode.UNAUTHENTICATED,
                String.format("Unsupported RSA algorithm: %s", alg));
      };
      case "EC" -> switch (alg) {
        case "ES256" -> Algorithm.ECDSA256((ECPublicKey) jwk.getPublicKey(), null);
        case "ES384" -> Algorithm.ECDSA384((ECPublicKey) jwk.getPublicKey(), null);
        case "ES512" -> Algorithm.ECDSA512((ECPublicKey) jwk.getPublicKey(), null);
        default -> throw new OAuthInvalidClientException(ErrorCode.UNAUTHENTICATED,
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
   *
   * <p>Which is also why the only INFO here sits at the very end, on the successful path: for as
   * long as an issuer is failing, nothing is cached and this method runs again for every exchange.
   */
  private JwkProvider discover(String issuer, String normalizedIssuer) {
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
    // Reports a COMPLETED resolution, and is deliberately past the cache put. Before the fetch it
    // read as "about to attempt", which is once per issuer per DISCOVERY_TTL only while discovery
    // succeeds: a failure is never cached, so during an identity-provider outage that line fired
    // on every exchange -- the same storm item 2 set out to stop, one level quieter. Here it is
    // once per issuer per cache window in every condition, and a failed attempt is reported by
    // the throttled WARN alone.
    LOGGER.info("Issuer '{}': resolved signing keys by OIDC discovery", issuer);
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
   *   <li>{@code https} to a host that is not loopback, link-local, any-local, private, carrier-
   *       grade NAT, benchmarking, multicast or an IPv6 form embedding one of those: allowed
   *   <li>{@code http} to a loopback host: allowed ONLY while {@link
   *       #ALLOW_PLAIN_HTTP_LOOPBACK_PROPERTY} is set, which is a test-only flag; a production
   *       server takes https and nothing else
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
   *
   * <p><b>This bounds the URL we hand to the fetch, not every address the fetch can reach.</b>
   * {@code UrlJwkProvider} delegates to {@code URL.openConnection()}, which follows same-protocol
   * redirects, so an allow-listed identity provider whose {@code jwks_uri} answers 302 to an
   * internal {@code https} address reaches it without passing through this check again. Closing
   * that means fetching the JWKS here instead of delegating, and is not done yet. Read this as a
   * guard against a discovery document that names a bad target outright -- which is the reachable
   * case, since the issuer must already be allow-listed -- and not as a complete SSRF control.
   */
  private static URL validatedJwksUrl(String jwksUri, String issuer) {
    return validatedJwksUrl(jwksUri, issuer, plainHttpLoopbackAllowed());
  }

  /**
   * The rule itself, with the plain-http exception passed in rather than read from the JVM, so a
   * test can assert what a server WITHOUT the flag does without mutating global state that another
   * test running beside it would see.
   */
  static URL validatedJwksUrl(String jwksUri, String issuer, boolean allowPlainHttpLoopback) {
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
      // Also the fail-closed answer for an authority Java declines to parse into a host, which is
      // every case where URI and URL disagree about what the host is: URI is the stricter parser
      // of the two, so anything it will not read (an underscore in a name, a second '@', a
      // non-ASCII full stop) is refused here rather than reaching URL's more forgiving one.
      LOGGER.debug("Issuer '{}': jwks_uri has no usable host: '{}'", issuer, jwksUri);
      throw new OAuthInvalidRequestException(
          ErrorCode.UNAVAILABLE,
          "Identity provider published a jwks_uri with no usable host, for issuer " + issuer);
    }

    if ("http".equals(scheme)) {
      if (!(allowPlainHttpLoopback && isLoopbackHost(host))) {
        LOGGER.debug("Issuer '{}': rejected plain-http jwks_uri '{}'", issuer, jwksUri);
        throw new OAuthInvalidRequestException(
            ErrorCode.UNAVAILABLE,
            "Identity provider published a plain-http jwks_uri, for issuer "
                + issuer
                + "; only https is accepted");
      }
      // The test identity provider, explicitly enabled. Returned here rather than falling
      // through, because the check below rejects loopback -- which is the whole point of it.
      return asUrl(uri, jwksUri, issuer);
    }
    if (isInternalAddress(host)) {
      LOGGER.debug("Issuer '{}': rejected internal-address jwks_uri '{}'", issuer, jwksUri);
      throw new OAuthInvalidRequestException(
          ErrorCode.UNAVAILABLE,
          "Identity provider published a jwks_uri pointing at a loopback, link-local or private"
              + " address, for issuer "
              + issuer);
    }
    return asUrl(uri, jwksUri, issuer);
  }

  /** Whether the plain-http-to-loopback exception is enabled in this JVM. */
  private static boolean plainHttpLoopbackAllowed() {
    return plainHttpLoopbackAllowed(System.getProperty(ALLOW_PLAIN_HTTP_LOOPBACK_PROPERTY));
  }

  /**
   * How the flag's value is read: only the exact string {@code true} turns it on, so an unset
   * property, an empty one, and anything else all leave the exception off. Separated from the
   * lookup so the default -- the one that governs every real deployment -- is itself testable in a
   * JVM where the tests have set the property.
   */
  static boolean plainHttpLoopbackAllowed(String propertyValue) {
    return "true".equalsIgnoreCase(propertyValue);
  }

  private static URL asUrl(URI uri, String jwksUri, String issuer) {
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
    String bare = normalizeHost(host);
    if (bare.equals("localhost")) {
      return true;
    }
    InetAddress literal = literalAddress(bare);
    return literal != null && literal.isLoopbackAddress();
  }

  /**
   * Whether a URI host names an address this server must never fetch from: loopback, link-local
   * (which is where 169.254.169.254, the cloud metadata address, lives), any-local, private,
   * shared/carrier-grade-NAT, benchmarking, multicast, or an IPv6 form that embeds one of those. A
   * DNS name that is not {@code localhost} is not classified -- see {@link #validatedJwksUrl} on
   * why it is not resolved.
   */
  private static boolean isInternalAddress(String host) {
    String bare = normalizeHost(host);
    // Empty is what a host of "." normalizes to: nothing usable, so fail closed.
    if (bare.isEmpty() || bare.equals("localhost") || bare.endsWith(".localhost")) {
      return true;
    }
    InetAddress literal = literalAddress(bare);
    if (literal == null) {
      return false;
    }
    if (literal.isLoopbackAddress()
        || literal.isLinkLocalAddress()
        || literal.isAnyLocalAddress()
        || literal.isSiteLocalAddress()
        // 224.0.0.0/4 and ff00::/8. A multicast fetch is never a key set and can be a way to
        // reach listeners on a local segment.
        || literal.isMulticastAddress()) {
      return true;
    }
    byte[] octets = literal.getAddress();
    if (octets.length == 4) {
      return isInternalIpv4(octets);
    }
    // IPv6 unique-local (fc00::/7). isSiteLocalAddress only covers the deprecated fec0::/10.
    if ((octets[0] & 0xFE) == 0xFC) {
      return true;
    }
    byte[] embedded = embeddedIpv4(octets);
    return embedded != null && isInternalIpv4(embedded);
  }

  /**
   * The IPv4 ranges that must never be fetched from, as raw octets so this serves both a literal
   * IPv4 host and the IPv4 address an IPv6 one embeds.
   *
   * <p>The first five duplicate what {@link InetAddress}'s own predicates answer for a literal and
   * are listed anyway, because for an embedded address there is no {@code InetAddress} to ask. The
   * rest are ranges those predicates do not cover at all, and every one of them reaches something:
   * 100.64.0.0/10 is where carrier-grade NAT and a great many cloud-internal and Kubernetes
   * networks live, 192.0.0.0/24 holds IETF protocol assignments, and 198.18.0.0/15 is the
   * benchmarking range that appears inside service meshes.
   */
  private static boolean isInternalIpv4(byte[] octets) {
    int first = octets[0] & 0xFF;
    int second = octets[1] & 0xFF;
    int third = octets[2] & 0xFF;
    return first == 0 // "this network", including 0.0.0.0
        || first == 127 // loopback
        || first == 10 // RFC 1918
        || (first == 172 && second >= 16 && second <= 31) // RFC 1918
        || (first == 192 && second == 168) // RFC 1918
        || (first == 169 && second == 254) // link-local, incl. the cloud metadata address
        || (first == 100 && second >= 64 && second <= 127) // 100.64.0.0/10, carrier-grade NAT
        || (first == 192 && second == 0 && third == 0) // 192.0.0.0/24, IETF protocol assignments
        || (first == 198 && (second == 18 || second == 19)) // 198.18.0.0/15, benchmarking
        || first >= 224; // multicast and the reserved 240.0.0.0/4
  }

  /**
   * The IPv4 address an IPv6 address embeds in its low 32 bits, or null if it embeds none.
   *
   * <p>{@code 64:ff9b::/96} is the well-known NAT64 prefix: a translator on the path forwards it
   * to the embedded IPv4 address, so {@code [64:ff9b::7f00:1]} is a way of writing 127.0.0.1 that
   * none of {@link InetAddress}'s predicates recognise. {@code ::/96} is the deprecated
   * IPv4-compatible form and is treated the same way, because it costs one comparison and the
   * deprecation is not enforced by anything this code can see. {@code ::ffff:0:0/96}, the
   * IPv4-MAPPED form, needs no case here: {@code InetAddress.getByName} returns an {@code
   * Inet4Address} for it, so it is classified as IPv4 before this is reached.
   */
  private static byte[] embeddedIpv4(byte[] octets) {
    boolean nat64 =
        octets[0] == 0x00
            && octets[1] == 0x64
            && octets[2] == (byte) 0xFF
            && octets[3] == (byte) 0x9B
            && allZero(octets, 4, 12);
    if (!nat64 && !allZero(octets, 0, 12)) {
      return null;
    }
    return new byte[] {octets[12], octets[13], octets[14], octets[15]};
  }

  private static boolean allZero(byte[] octets, int from, int to) {
    for (int i = from; i < to; i++) {
      if (octets[i] != 0) {
        return false;
      }
    }
    return true;
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

  /**
   * A URI host reduced to the form the classifiers compare against: no brackets, no trailing root
   * dot, lower case.
   *
   * <p>The trailing dot is the one that mattered. {@code https://localhost./keys} parses to the
   * host {@code localhost.}, which equals neither {@code localhost} nor anything ending {@code
   * .localhost}, so it walked straight through the internal-address check -- and then {@code
   * InetAddress.getByName} resolved it to 127.0.0.1, because a trailing dot is simply the DNS root
   * written out. One character was the whole bypass. (A literal cannot be spelt this way: {@code
   * URI.getHost()} returns null for {@code https://127.0.0.1./}, which is refused earlier as an
   * unusable host.)
   */
  private static String normalizeHost(String host) {
    String bare = stripBrackets(host);
    int end = bare.length();
    while (end > 0 && bare.charAt(end - 1) == '.') {
      end--;
    }
    return bare.substring(0, end).toLowerCase(Locale.ROOT);
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
      // Throttled, keyed on the file, for the same reason the key-lookup failures are: this
      // method runs on EVERY token exchange -- AuthService calls it to derive the trusted issuers
      // before it will look at a token at all -- and /tokens needs no credentials. A file that is
      // absent or unreadable stays that way, so an ungated line here is one WARN per request for
      // as long as the misconfiguration lasts, which is exactly the log-volume hole the cooldowns
      // below the fold exist to close.
      if (jwksFileWarnCooldown.allow(jwksPath.toString())) {
        LOGGER.warn("Configured external JWKS file '{}' does not exist", jwksPath);
      }
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
      if (jwksFileWarnCooldown.allow(jwksPath.toString())) {
        LOGGER.warn("Failed to read external JWKS file '{}' for issuer discovery", jwksPath, e);
      }
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

