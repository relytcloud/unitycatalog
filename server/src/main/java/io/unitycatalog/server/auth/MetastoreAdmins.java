package io.unitycatalog.server.auth;

import io.unitycatalog.control.model.User;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.MetastoreRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.UserRepository;
import io.unitycatalog.server.persist.model.Privileges;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Who administers this metastore.
 *
 * <p>Being an administrator is not an account type: it is holding OWNER on the metastore resource,
 * which is what {@code GET /auth/capabilities} reports and what every {@code #authorize(#principal,
 * #metastore, OWNER)} rule tests. This class is the one place that reads and changes that fact, so
 * the invariant below cannot be enforced in one caller and forgotten in another.
 *
 * <p><b>The invariant:</b> at least one enabled administrator always remains. Without it a
 * metastore can be left with nobody able to administer it and no supported way back, because
 * UnityAccessUtil#initializeAdmin re-grants OWNER only when the {@code admin} user does not exist
 * at all — a surviving {@code admin} row that has lost the privilege is never repaired by a
 * restart.
 */
public class MetastoreAdmins {

  /** The account behind the password sign-in; see AuthService#adminLogin. */
  private static final String BOOTSTRAP_ADMIN_EMAIL = "admin";

  private final UnityCatalogAuthorizer authorizer;
  private final UserRepository userRepository;
  private final MetastoreRepository metastoreRepository;

  public MetastoreAdmins(UnityCatalogAuthorizer authorizer, Repositories repositories) {
    this.authorizer = authorizer;
    this.userRepository = repositories.getUserRepository();
    this.metastoreRepository = repositories.getMetastoreRepository();
  }

  /**
   * Administrator principals, including deactivated ones, so a caller can see the whole picture.
   */
  public List<String> list() {
    return holders().stream().map(this::emailOrNull).filter(Objects::nonNull).sorted().toList();
  }

  /**
   * Makes the user an administrator.
   *
   * <p>Any administrator may do this. Restricting it to the built-in {@code admin} account would
   * buy nothing — metastore OWNER is already the top of the privilege lattice, so an administrator
   * granting one has escalated nobody past themselves — while making the break-glass password
   * account a participant in routine administration, which is the habit the deployment guide warns
   * against.
   */
  public void grant(String email) {
    User user = requireUser(email);
    if (user.getState() != User.StateEnum.ENABLED) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT, "Cannot make a deactivated user an administrator: " + email);
    }
    authorizer.grantAuthorization(
        UUID.fromString(Objects.requireNonNull(user.getId())),
        metastoreRepository.getMetastoreId(),
        Privileges.OWNER);
  }

  /** Takes the privilege away, provided somebody is left holding it. */
  public void revoke(String email) {
    // The bootstrap administrator keeps it. It is the account behind the password sign-in, and a
    // sign-in that works but administers nothing is no way back in. Worse, nothing repairs it:
    // UnityAccessUtil#initializeAdmin re-grants OWNER only when the account does not exist, so a
    // surviving row that has lost the privilege stays that way across restarts.
    if (BOOTSTRAP_ADMIN_EMAIL.equals(email)) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT,
          "The bootstrap administrator cannot lose administrator status; it is the way back in"
              + " when the identity provider is unavailable, and no restart restores it.");
    }
    User user = requireUser(email);
    UUID principalId = UUID.fromString(Objects.requireNonNull(user.getId()));
    requireAnotherAdminRemains(principalId);
    authorizer.revokeAuthorization(
        principalId, metastoreRepository.getMetastoreId(), Privileges.OWNER);
  }

  public boolean isAdmin(UUID principalId) {
    return authorizer.authorize(
        principalId, metastoreRepository.getMetastoreId(), Privileges.OWNER);
  }

  /**
   * Refuses an operation that would leave no enabled administrator once {@code losingAdmin} is no
   * longer one — whether that is a revocation or the deletion of the account itself.
   *
   * <p>A policy naming a user who is deactivated, or who no longer exists, does not count: it
   * cannot be used to sign in, so it would not keep the metastore administrable.
   */
  public void requireAnotherAdminRemains(UUID losingAdmin) {
    boolean remains =
        holders().stream().filter(id -> !id.equals(losingAdmin)).anyMatch(this::isEnabled);
    if (!remains) {
      throw new BaseException(
          ErrorCode.FAILED_PRECONDITION,
          "Refusing to leave the metastore without an enabled administrator; make another user an"
              + " administrator first.");
    }
  }

  private List<UUID> holders() {
    return authorizer.listAuthorizations(metastoreRepository.getMetastoreId()).entrySet().stream()
        .filter(entry -> entry.getValue().contains(Privileges.OWNER))
        .map(Map.Entry::getKey)
        .collect(Collectors.toList());
  }

  private User requireUser(String email) {
    try {
      return userRepository.getUserByEmail(email);
    } catch (BaseException e) {
      throw new BaseException(ErrorCode.NOT_FOUND, "User not found: " + email);
    }
  }

  private boolean isEnabled(UUID principalId) {
    User user = userOrNull(principalId);
    return user != null && user.getState() == User.StateEnum.ENABLED;
  }

  private String emailOrNull(UUID principalId) {
    User user = userOrNull(principalId);
    return user == null ? null : user.getEmail();
  }

  private User userOrNull(UUID principalId) {
    try {
      return userRepository.getUser(principalId.toString());
    } catch (Exception e) {
      // A policy left behind by a user that no longer exists names nobody.
      return null;
    }
  }
}
