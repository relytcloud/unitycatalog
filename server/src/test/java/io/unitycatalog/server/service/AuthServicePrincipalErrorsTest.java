package io.unitycatalog.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import io.unitycatalog.server.base.BaseServerTest;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How a subject token names its principal, and how that failing is reported.
 *
 * <p>Three properties are pinned here. The principal is resolved from a claim that yields a usable
 * string, not from one that merely exists, because {@code Claim.asString()} quietly returns null
 * for a JSON null and for a number, array or object. The admission decision and the subject
 * stamped into the issued token come from one rule, so an exchange can never return 200 with a
 * token UC itself will refuse. And Entra-specific advice appears only for Entra tokens: falling
 * back to {@code sub} is how DWSU token-exchange is meant to work, and those operators need to be
 * told which principal to create, not to go and edit an app registration.
 */
public class AuthServicePrincipalErrorsTest extends BaseServerTest {

  private static final String TEST_AUDIENCE = "unity-catalog";
  private static final String TOKEN_ENDPOINT = "/api/1.0/unity-control/auth/tokens";
  private static final String DWSU_ISSUER = "relyt-instance-known";
  private static final String ENTRA_ISSUER =
      "https://login.microsoftonline.com/11111111-2222-3333-4444-555555555555/v2.0";
  private static final String NON_ADMIN_SUB = "00000000-1111-2222-3333-444444444444";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private WebClient client;
  private Algorithm algorithm;
  private String dwsuKeyId;
  private String entraKeyId;

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(Property.AUTHORIZATION_ENABLED.getKey(), "enable");
    serverProperties.setProperty("server.audiences", TEST_AUDIENCE);
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var keyPair = generator.generateKeyPair();
      RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
      algorithm = Algorithm.RSA512(publicKey, (RSAPrivateKey) keyPair.getPrivate());
      dwsuKeyId = UUID.randomUUID().toString();
      entraKeyId = UUID.randomUUID().toString();
      // Both issuers are declared in the static JWKS file, so both resolve locally and neither
      // test touches the network -- including the Entra-shaped one.
      Path jwksFile = Files.createTempFile("uc-jwks", ".json");
      Files.writeString(
          jwksFile,
          "{\"keys\":["
              + jwk(publicKey, dwsuKeyId, DWSU_ISSUER)
              + ","
              + jwk(publicKey, entraKeyId, ENTRA_ISSUER)
              + "]}");
      serverProperties.setProperty("server.external-jwks-file", jwksFile.toString());
    } catch (Exception e) {
      throw new RuntimeException("Failed to set up external JWKS file", e);
    }
  }

  @BeforeEach
  @Override
  public void setUp() {
    super.setUp();
    client = WebClient.builder(serverConfig.getServerUrl()).build();
  }

  @Test
  public void entraTokenWithoutEmailClaimNamesTheMissingClaimAndTheSubject() {
    AggregatedHttpResponse response =
        exchangeToken(entraToken(NON_ADMIN_SUB, Email.ABSENT, null));

    assertThat(response.contentUtf8()).contains("'email' claim");
    assertThat(response.contentUtf8()).contains("optional claim");
    // Without the subject the operator cannot tell which principal to provision.
    assertThat(response.contentUtf8()).contains(NON_ADMIN_SUB);
  }

  @Test
  public void subBasedDwsuTokenGetsNoEntraAdviceButStillNamesTheSubject() {
    // A DWSU token relies on 'sub' by design. Its lookup missing is an unprovisioned user, not a
    // misconfigured Entra app registration.
    AggregatedHttpResponse response = exchangeToken(dwsuToken(NON_ADMIN_SUB, Email.ABSENT, null));

    assertThat(response.contentUtf8()).contains("not provisioned");
    assertThat(response.contentUtf8()).contains(NON_ADMIN_SUB);
    assertThat(response.contentUtf8()).doesNotContain("app registration");
    assertThat(response.contentUtf8()).doesNotContain("Entra");
  }

  @Test
  public void tokenWithAProvisionedEmailIsExchangedForThatEmail() {
    // The positive case the other tests are the negative of. Without it, every assertion in this
    // class would still hold if the principal lookup rejected EVERY user: the class never
    // provisioned one, so "not provisioned" was the only outcome it could ever observe.
    provisionUser("provisioned@example.com");

    AggregatedHttpResponse response =
        exchangeToken(dwsuToken(NON_ADMIN_SUB, Email.STRING, "provisioned@example.com"));

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    // The 'email' claim names the principal, so it -- not 'sub' -- is what the issued token
    // carries.
    assertThat(subjectOfIssuedToken(response)).isEqualTo("provisioned@example.com");
  }

  @Test
  public void entraTokenWithAProvisionedEmailIsExchangedForThatEmail() {
    // The Entra shape: an opaque GUID 'sub' nobody provisions, and an 'email' optional claim that
    // does name the user. The Entra-specific advice must not fire when the email resolves.
    provisionUser("entra.person@example.com");

    AggregatedHttpResponse response =
        exchangeToken(entraToken(NON_ADMIN_SUB, Email.STRING, "entra.person@example.com"));

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(subjectOfIssuedToken(response)).isEqualTo("entra.person@example.com");
  }

  @Test
  public void tokenWithUnknownEmailSaysNotProvisioned() {
    AggregatedHttpResponse response =
        exchangeToken(dwsuToken(NON_ADMIN_SUB, Email.STRING, "nobody@example.com"));

    assertThat(response.contentUtf8()).contains("not provisioned");
    assertThat(response.contentUtf8()).contains("nobody@example.com");
  }

  @Test
  public void tokenWithoutIssuerClaimIsUnauthorizedNotAServerError() {
    // getIssuer() is null, and an immutable allow-list's contains(null) throws. This endpoint is
    // unauthenticated, so that NullPointerException was a 500 anyone could trigger.
    AggregatedHttpResponse response = exchangeToken(tokenWithoutIssuer());

    assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.contentUtf8()).contains("Invalid issuer");
  }

  @Test
  public void nonStringEmailClaimFallsBackToSubRatherThanResolvingToNull() {
    // "email": 12345 is present but not a string, so asString() is null. Treating presence as
    // usability made the principal null, skipped the 'sub' fallback, and reported
    // "User not provisioned: null" for a token whose 'sub' may well be provisioned.
    AggregatedHttpResponse response = exchangeToken(dwsuToken(NON_ADMIN_SUB, Email.NUMBER, null));

    assertThat(response.contentUtf8()).contains(NON_ADMIN_SUB);
    assertThat(response.contentUtf8()).doesNotContain("not provisioned: null");
  }

  @Test
  public void explicitlyNullEmailIssuesATokenWhoseSubjectIsTheSub() {
    // The principal admitted and the principal stamped into the issued token must be the same one.
    // While they diverged this returned 200 with a token carrying a null subject, which
    // AuthDecorator then rejected on every subsequent call.
    AggregatedHttpResponse response = exchangeToken(dwsuToken("admin", Email.JSON_NULL, null));

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(subjectOfIssuedToken(response)).isEqualTo("admin");
  }

  @Test
  public void nonStringEmailIssuesATokenWhoseSubjectIsTheSub() {
    AggregatedHttpResponse response = exchangeToken(dwsuToken("admin", Email.NUMBER, null));

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(subjectOfIssuedToken(response)).isEqualTo("admin");
  }

  /** The shape of the {@code email} claim, which is what decides whether it names anyone. */
  private enum Email {
    ABSENT,
    JSON_NULL,
    NUMBER,
    STRING
  }

  @SneakyThrows
  private String subjectOfIssuedToken(AggregatedHttpResponse response) {
    JsonNode body = MAPPER.readTree(response.contentUtf8());
    return JWT.decode(body.get("access_token").asText()).getClaim("sub").asString();
  }

  private String dwsuToken(String subject, Email email, String emailValue) {
    return token(DWSU_ISSUER, dwsuKeyId, subject, email, emailValue);
  }

  private String entraToken(String subject, Email email, String emailValue) {
    return token(ENTRA_ISSUER, entraKeyId, subject, email, emailValue);
  }

  private String token(
      String issuer, String keyId, String subject, Email email, String emailValue) {
    var builder =
        JWT.create()
            .withSubject(subject)
            .withIssuer(issuer)
            .withAudience(TEST_AUDIENCE)
            .withIssuedAt(new Date())
            .withKeyId(keyId)
            .withJWTId(UUID.randomUUID().toString());
    switch (email) {
      case JSON_NULL -> builder.withNullClaim("email");
      case NUMBER -> builder.withClaim("email", 12345);
      case STRING -> builder.withClaim("email", emailValue);
      case ABSENT -> {
        // no email claim at all
      }
    }
    return builder.sign(algorithm);
  }

  /** A token with no {@code iss} claim at all. */
  private String tokenWithoutIssuer() {
    return JWT.create()
        .withSubject(NON_ADMIN_SUB)
        .withAudience(TEST_AUDIENCE)
        .withIssuedAt(new Date())
        .withKeyId(dwsuKeyId)
        .withJWTId(UUID.randomUUID().toString())
        .sign(algorithm);
  }

  private AggregatedHttpResponse exchangeToken(String identityToken) {
    String formBody =
        "grant_type=urn:ietf:params:oauth:grant-type:token-exchange"
            + "&requested_token_type=urn:ietf:params:oauth:token-type:access_token"
            + "&subject_token_type=urn:ietf:params:oauth:token-type:id_token"
            + "&subject_token="
            + identityToken;

    RequestHeaders headers =
        RequestHeaders.builder()
            .method(HttpMethod.POST)
            .path(TOKEN_ENDPOINT)
            .contentType(MediaType.FORM_DATA)
            .build();

    return client.execute(headers, HttpData.ofUtf8(formBody)).aggregate().join();
  }

  /** A single JWKS entry carrying the {@code issuer} member that scopes the key to one issuer. */
  private static String jwk(RSAPublicKey publicKey, String keyId, String issuer) {
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String n = encoder.encodeToString(toUnsignedBytes(publicKey.getModulus()));
    String e = encoder.encodeToString(toUnsignedBytes(publicKey.getPublicExponent()));
    return String.format(
        "{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS512\",\"kid\":\"%s\","
            + "\"n\":\"%s\",\"e\":\"%s\",\"issuer\":\"%s\"}",
        keyId, n, e, issuer);
  }

  /** Converts a BigInteger to unsigned big-endian bytes (no leading zero padding). */
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
