package io.unitycatalog.server.base.auth;

import java.math.BigInteger;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/** Builds JWKS documents for tests that stand up their own signing keys. */
public final class JwksTestUtils {

  private JwksTestUtils() {}

  /**
   * A single-key RSA JWKS carrying an {@code issuer} member, the shape the server's static external
   * JWKS file expects (each key is bound to the issuer it was registered for).
   */
  public static String issuerBoundJwks(RSAPublicKey publicKey, String keyId, String issuer) {
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String n = encoder.encodeToString(toUnsignedBytes(publicKey.getModulus()));
    String e = encoder.encodeToString(toUnsignedBytes(publicKey.getPublicExponent()));
    return String.format(
        "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS512\",\"kid\":\"%s\","
            + "\"n\":\"%s\",\"e\":\"%s\",\"issuer\":\"%s\"}]}",
        keyId, n, e, issuer);
  }

  /** Unsigned big-endian bytes of a BigInteger (no sign-padding zero byte). */
  private static byte[] toUnsignedBytes(BigInteger value) {
    byte[] bytes = value.toByteArray();
    if (bytes[0] == 0) {
      byte[] trimmed = new byte[bytes.length - 1];
      System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
      return trimmed;
    }
    return bytes;
  }
}
