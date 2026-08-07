package io.unitycatalog.server.utils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.unitycatalog.server.exception.BaseException;
import org.junit.jupiter.api.Test;

public class UserEmailValidationTest {

  @Test
  public void testValidUserEmailsAndPrincipals() {
    // Normal emails plus the non-email deploy principals (admin, uc_default_user) must be accepted:
    // validateUserEmail is a character blacklist, not an RFC email check.
    ValidationUtils.validateUserEmail("admin");
    ValidationUtils.validateUserEmail("uc_default_user");
    ValidationUtils.validateUserEmail("user@example.com");
    ValidationUtils.validateUserEmail("first.last+tag@sub.example.com");
  }

  @Test
  public void testNullEmptyOrBlankRejected() {
    assertThatThrownBy(() -> ValidationUtils.validateUserEmail(null))
        .isInstanceOf(BaseException.class);
    assertThatThrownBy(() -> ValidationUtils.validateUserEmail(""))
        .isInstanceOf(BaseException.class);
    assertThatThrownBy(() -> ValidationUtils.validateUserEmail("   "))
        .isInstanceOf(BaseException.class);
  }

  @Test
  public void testTooLongRejected() {
    assertThatThrownBy(() -> ValidationUtils.validateUserEmail("a".repeat(256)))
        .isInstanceOf(BaseException.class);
  }

  @Test
  public void testInjectionCharsRejected() {
    // A '"' / '\' / whitespace / control char in the principal must be rejected so it cannot break
    // out of or inject into the JWT 'sub' claim the coordinator signs for token exchange.
    assertThatThrownBy(() -> ValidationUtils.validateUserEmail("a\"b"))
        .isInstanceOf(BaseException.class);
    assertThatThrownBy(() -> ValidationUtils.validateUserEmail("a\\b"))
        .isInstanceOf(BaseException.class);
    assertThatThrownBy(() -> ValidationUtils.validateUserEmail("a b"))
        .isInstanceOf(BaseException.class);
    char ctrlC = 3;
    assertThatThrownBy(() -> ValidationUtils.validateUserEmail("a" + ctrlC + "b"))
        .isInstanceOf(BaseException.class);
    char del = 0x7f;
    assertThatThrownBy(() -> ValidationUtils.validateUserEmail("a" + del))
        .isInstanceOf(BaseException.class);
  }
}
