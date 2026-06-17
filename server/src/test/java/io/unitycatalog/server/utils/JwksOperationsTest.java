package io.unitycatalog.server.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import io.unitycatalog.server.security.SecurityContext;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the static external JWKS provider binds each key to its issuer: a key registered
 * for one issuer must not be usable to verify a token claiming a different issuer (confused-issuer
 * protection), and a key with no {@code issuer} member is rejected outright.
 */
public class JwksOperationsTest {

  // Two real P-256 public points (only used as well-formed JWK material; signatures are not
  // verified in this test).
  private static final String X_A = "C8H9oDGZDEKZQ70-zxSiq0z6SdnYwgMLKdAs2xvMbfU";
  private static final String Y_A = "SwxfO-dr60Ugf3IFFazvgxDdBKqDheZYrL0Bk6-76S0";
  private static final String X_B = "In1ki6Yd2aDFZKuXtdteEjs82L4zh_OFlAgfSvCUQeI";
  private static final String Y_B = "9cSK-EecpGX0IDbvUwlcOr72EYWMx4u4c9DHI1DvjGQ";

  private static String entry(String kid, String x, String y, String issuer) {
    String base =
        String.format(
            "{\"kty\":\"EC\",\"crv\":\"P-256\",\"kid\":\"%s\",\"use\":\"sig\",\"alg\":\"ES256\","
                + "\"x\":\"%s\",\"y\":\"%s\"",
            kid, x, y);
    return issuer == null ? base + "}" : base + String.format(",\"issuer\":\"%s\"}", issuer);
  }

  private JwkProvider providerFor(String requestedIssuer, String jwksJson) throws Exception {
    Path jwksFile = Files.createTempFile("jwks", ".json");
    Files.writeString(jwksFile, jwksJson);
    ServerProperties serverProperties = mock(ServerProperties.class);
    when(serverProperties.getExternalJwksFile()).thenReturn(jwksFile.toString());
    JwksOperations ops = new JwksOperations(mock(SecurityContext.class), serverProperties);
    return ops.loadJwkProvider(requestedIssuer);
  }

  @Test
  public void keyIsUsableOnlyForItsRegisteredIssuer() throws Exception {
    String jwks =
        "{\"keys\":["
            + entry("kidA", X_A, Y_A, "issuer-a")
            + ","
            + entry("kidB", X_B, Y_B, "issuer-b")
            + "]}";

    JwkProvider providerA = providerFor("issuer-a", jwks);

    // The key registered for issuer-a is returned for issuer-a.
    assertThat(providerA.get("kidA").getId()).isEqualTo("kidA");

    // issuer-b's key must NOT be accepted when verifying a token claiming issuer-a.
    assertThatThrownBy(() -> providerA.get("kidB")).isInstanceOf(JwkException.class);
  }

  @Test
  public void keyWithoutIssuerMemberIsRejected() throws Exception {
    String jwks = "{\"keys\":[" + entry("kidNoIssuer", X_A, Y_A, null) + "]}";

    JwkProvider provider = providerFor("issuer-a", jwks);

    assertThatThrownBy(() -> provider.get("kidNoIssuer")).isInstanceOf(JwkException.class);
  }
}
