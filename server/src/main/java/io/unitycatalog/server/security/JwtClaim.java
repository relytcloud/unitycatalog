package io.unitycatalog.server.security;

/** Valid claims for UC access tokens. */
public enum JwtClaim {
  ISSUER("iss"),
  SUBJECT("sub"),
  EMAIL("email"),
  // Sign-in name claims consulted when resolving the caller to a local user (see
  // AuthService#resolvePrincipal): Entra ID does not send "email" unless it is configured as an
  // optional claim, so "preferred_username" / "upn" serve as fallbacks.
  PREFERRED_USERNAME("preferred_username"),
  UPN("upn"),
  EXPIRATION("exp"),
  ISSUED_AT("iat"),
  JWT_ID("jti"),
  KEY_ID("kid"),

  TOKEN_TYPE("type");

  private final String key;

  JwtClaim(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }
}
