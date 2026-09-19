package io.unitycatalog.server.utils;

import static io.unitycatalog.server.security.SecurityContext.Issuers.INTERNAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.JwkProviderBuilder;
import com.auth0.jwk.NetworkException;
import com.auth0.jwk.SigningKeyNotFoundException;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.security.SecurityContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * How a failed signing-key lookup is classified, driven through the real provider chain.
 *
 * <p>The discriminator that matters is the PROVENANCE of the key set, not the class auth0 threw.
 * {@code UrlJwkProvider.getJwks()} fetches through a plain {@code URLConnection} under a blanket
 * {@code catch (IOException)} that constructs a {@link NetworkException}, and both local sources
 * here -- the internal certs file and the static external JWKS file -- are {@code UrlJwkProvider}s
 * over {@code file:} URLs. Classifying on {@code NetworkException} therefore reported a deleted or
 * unreadable {@code certs.json} as "could not reach the identity provider": a 503, on every
 * authenticated API call, with the filename stripped out, which load balancers and clients then
 * retry instead of failing fast.
 *
 * <p>The mirror of that defect is an identity provider answering 200 with {@code {"keys":[]}}: that
 * is a plain {@link SigningKeyNotFoundException}, so it used to be reported as a rejected token.
 *
 * <p>Every test here points the real code at a real (or really missing) key set. Constructing the
 * auth0 exceptions by hand is exactly what let the previous classification pass review: it proved
 * the branch ordering and never touched the question of which branch a file error lands in.
 */
public class JwksKeyLookupClassificationTest {

  // A real P-256 public point, used only as well-formed JWK material.
  private static final String X = "C8H9oDGZDEKZQ70-zxSiq0z6SdnYwgMLKdAs2xvMbfU";
  private static final String Y = "SwxfO-dr60Ugf3IFFazvgxDdBKqDheZYrL0Bk6-76S0";

  private static String entry(String kid, String issuer) {
    String base =
        String.format(
            "{\"kty\":\"EC\",\"crv\":\"P-256\",\"kid\":\"%s\",\"use\":\"sig\",\"alg\":\"ES256\","
                + "\"x\":\"%s\",\"y\":\"%s\"",
            kid, X, Y);
    return issuer == null ? base + "}" : base + String.format(",\"issuer\":\"%s\"}", issuer);
  }

  private static Path fileWith(String contents) throws Exception {
    Path file = Files.createTempFile("uc-jwks", ".json");
    Files.writeString(file, contents);
    return file;
  }

  /** Operations whose INTERNAL provider reads the given (possibly absent) certs file. */
  private static JwksOperations opsWithCertsFile(Path certsFile) {
    SecurityContext securityContext = mock(SecurityContext.class);
    when(securityContext.getCertsFile()).thenReturn(certsFile);
    return new JwksOperations(securityContext);
  }

  /** Operations whose external JWKS file is this one. */
  private static JwksOperations opsWithJwksFile(Path jwksFile) {
    ServerProperties serverProperties = mock(ServerProperties.class);
    when(serverProperties.getExternalJwksFile()).thenReturn(jwksFile.toString());
    return new JwksOperations(mock(SecurityContext.class), serverProperties);
  }

  @Test
  public void missingInternalCertsFileIsAServerFaultThatLeaksNoPath() {
    // The whole authenticated API surface resolves INTERNAL through this provider on every call.
    // Deleting the file, or mounting it with the wrong mode, is a server misconfiguration: it is
    // not an upstream outage and it is not the caller's token that is wrong.
    //
    // The message goes to a caller who only had to present SOME bearer token to get here, so it
    // must not name the file. The path is in the server-side ERROR log with the cause, which is
    // where the operator reads it.
    Path missing = Path.of("/no/such/dir/uc-certs-for-this-test.json");
    JwksOperations ops = opsWithCertsFile(missing);

    assertThatThrownBy(() -> ops.verifierForIssuerAndKey(INTERNAL, "any-kid", "RS256", List.of()))
        .isInstanceOf(BaseException.class)
        .hasMessageNotContaining(missing.toString())
        .hasMessageNotContaining("/no/such/dir")
        // Nor via auth0's own wording, which embeds the file: URL it failed to open.
        .hasMessageNotContaining("file:")
        // Still says whose fault it is, and where to look.
        .hasMessageContaining("server key-configuration problem")
        .hasMessageContaining("server logs")
        // Nothing upstream was contacted, so nothing upstream may be blamed.
        .hasMessageNotContaining("identity provider")
        .extracting(e -> ((BaseException) e).getErrorCode())
        .isEqualTo(ErrorCode.INTERNAL);
  }

  @Test
  public void missingInternalCertsFileIsNotReportedAsServiceUnavailable() {
    // 503 tells every load balancer and client in the path to retry a condition that will never
    // clear on its own. Pinned separately from the status above because this is the specific
    // regression: NetworkException over a file: URL used to land here.
    JwksOperations ops = opsWithCertsFile(Path.of("/no/such/dir/uc-certs-for-this-test.json"));

    assertThatThrownBy(() -> ops.verifierForIssuerAndKey(INTERNAL, "any-kid", "RS256", List.of()))
        .isInstanceOf(BaseException.class)
        .extracting(e -> ((BaseException) e).getErrorCode())
        .isNotEqualTo(ErrorCode.UNAVAILABLE);
  }

  @Test
  public void malformedStaticJwksFileIsAServerFaultThatLeaksNoPath() throws Exception {
    // The key entry declares its issuer -- so resolution routes to the file -- but carries no
    // "kty", which is what Jwk.fromValues rejects. The key set cannot be produced at all, so no
    // statement about the caller's kid is possible. As above, the caller is told what kind of
    // problem it is and nothing about where the file lives.
    Path jwksFile = fileWith("{\"keys\":[{\"kid\":\"kidA\",\"issuer\":\"issuer-a\"}]}");
    JwksOperations ops = opsWithJwksFile(jwksFile);

    assertThatThrownBy(() -> ops.verifierForIssuerAndKey("issuer-a", "kidA", "ES256", List.of()))
        .isInstanceOf(BaseException.class)
        .hasMessageNotContaining(jwksFile.toString())
        .hasMessageNotContaining(jwksFile.getParent().toString())
        .hasMessageContaining("server key-configuration problem")
        .extracting(e -> ((BaseException) e).getErrorCode())
        .isEqualTo(ErrorCode.INTERNAL);
  }

  @Test
  public void unknownKidInTheStaticJwksFileIsStillUnauthorized() throws Exception {
    // The key set was read and is fine; it simply holds no key with this kid. That, and only
    // that, is a rejected token.
    Path jwksFile = fileWith("{\"keys\":[" + entry("kidA", "issuer-a") + "]}");
    JwksOperations ops = opsWithJwksFile(jwksFile);

    assertThatThrownBy(
            () -> ops.verifierForIssuerAndKey("issuer-a", "not-this-kid", "ES256", List.of()))
        .isInstanceOf(BaseException.class)
        .extracting(e -> ((BaseException) e).getErrorCode())
        .isEqualTo(ErrorCode.UNAUTHENTICATED);
  }

  @Test
  public void keyRegisteredForAnotherIssuerIsUnauthorized() throws Exception {
    // Confused-issuer protection reports a rejected token, not a broken server.
    Path jwksFile =
        fileWith(
            "{\"keys\":[" + entry("kidA", "issuer-a") + "," + entry("kidB", "issuer-b") + "]}");
    JwksOperations ops = opsWithJwksFile(jwksFile);

    assertThatThrownBy(() -> ops.verifierForIssuerAndKey("issuer-a", "kidB", "ES256", List.of()))
        .isInstanceOf(BaseException.class)
        .extracting(e -> ((BaseException) e).getErrorCode())
        .isEqualTo(ErrorCode.UNAUTHENTICATED);
  }

  @Test
  public void emptyRemoteKeySetIsAnUpstreamFailureNotARejectedToken() throws Exception {
    // An IdP answering 200 with no keys is broken; the caller's token has not been judged at all.
    // auth0 reserves NetworkException for "cannot obtain jwks from url", so this arrives as a
    // plain SigningKeyNotFoundException and used to be reported as 401.
    try (DiscoveryTestServer idp = new DiscoveryTestServer("{\"keys\":[]}")) {
      JwksOperations ops =
          opsWithJwksFile(fileWith("{\"keys\":[" + entry("kidLocal", "other-issuer") + "]}"));

      assertThatThrownBy(
              () -> ops.verifierForIssuerAndKey(idp.issuer(), "any-kid", "ES256", List.of()))
          .isInstanceOf(BaseException.class)
          .extracting(e -> ((BaseException) e).getErrorCode())
          .isEqualTo(ErrorCode.UNAVAILABLE);
    }
  }

  @Test
  public void unknownKidAgainstAHealthyRemoteKeySetIsUnauthorized() throws Exception {
    try (DiscoveryTestServer idp =
        new DiscoveryTestServer("{\"keys\":[" + entry("kidRemote", null) + "]}")) {
      JwksOperations ops =
          opsWithJwksFile(fileWith("{\"keys\":[" + entry("kidLocal", "other-issuer") + "]}"));

      assertThatThrownBy(
              () -> ops.verifierForIssuerAndKey(idp.issuer(), "not-this-kid", "ES256", List.of()))
          .isInstanceOf(BaseException.class)
          .extracting(e -> ((BaseException) e).getErrorCode())
          .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }
  }

  @Test
  public void healthyRemoteKeySetStillProducesAVerifier() throws Exception {
    try (DiscoveryTestServer idp =
        new DiscoveryTestServer("{\"keys\":[" + entry("kidRemote", null) + "]}")) {
      JwksOperations ops =
          opsWithJwksFile(fileWith("{\"keys\":[" + entry("kidLocal", "other-issuer") + "]}"));

      assertThat(ops.verifierForIssuerAndKey(idp.issuer(), "kidRemote", "ES256", List.of()))
          .isNotNull();
    }
  }

  /**
   * The wordings the classifier matches, read back from the library itself.
   *
   * <p>auth0 throws a plain {@link SigningKeyNotFoundException} for "this key set has no such kid",
   * for "this key set is empty" and for "this key set does not parse", and separates them only by
   * message text. {@link JwksOperations} matches that text against jwks-rsa 0.22.1 (pinned in
   * build.sbt). If an upgrade rewords any of these, this test fails and names the string that
   * moved, rather than the classification silently reverting to 401 for a broken key set.
   */
  @Test
  public void jwksRsaWordingsTheClassifierDependsOnAreUnchanged() throws Exception {
    Path emptySet = fileWith("{\"keys\":[]}");
    JwkProvider emptyProvider =
        new JwkProviderBuilder(emptySet.toUri().toURL()).cached(false).build();
    assertThatThrownBy(() -> emptyProvider.get("any-kid"))
        .isInstanceOf(SigningKeyNotFoundException.class)
        .hasMessage("No keys found in " + emptySet.toUri().toURL());

    Path unparseable = fileWith("{\"keys\":[{\"kid\":\"kidA\"}]}");
    JwkProvider unparseableProvider =
        new JwkProviderBuilder(unparseable.toUri().toURL()).cached(false).build();
    assertThatThrownBy(() -> unparseableProvider.get("kidA"))
        .isInstanceOf(SigningKeyNotFoundException.class)
        .hasMessage("Failed to parse jwk from json");

    Path healthy = fileWith("{\"keys\":[" + entry("kidA", null) + "]}");
    JwkProvider healthyProvider =
        new JwkProviderBuilder(healthy.toUri().toURL()).cached(false).build();
    assertThatThrownBy(() -> healthyProvider.get("not-this-kid"))
        .isInstanceOf(SigningKeyNotFoundException.class)
        .isNotInstanceOf(NetworkException.class)
        .hasMessage("No key found in " + healthy.toUri().toURL() + " with kid not-this-kid");

    // And the one that started all this: a missing local file is a "network" failure to auth0.
    Path missing = Path.of("/no/such/dir/uc-certs-for-this-test.json");
    JwkProvider missingProvider =
        new JwkProviderBuilder(missing.toUri().toURL()).cached(false).build();
    assertThatThrownBy(() -> missingProvider.get("any-kid"))
        .isInstanceOf(NetworkException.class)
        .hasMessage("Cannot obtain jwks from url " + missing.toUri().toURL());
  }
}
