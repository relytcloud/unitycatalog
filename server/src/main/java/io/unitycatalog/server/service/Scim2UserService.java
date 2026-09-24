package io.unitycatalog.server.service;

import static io.unitycatalog.server.model.SecurableType.METASTORE;
import static io.unitycatalog.server.utils.Scim2Utils.asUserResource;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.Produces;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.armeria.server.annotation.StatusCode;
import com.unboundid.scim2.common.exceptions.BadRequestException;
import com.unboundid.scim2.common.exceptions.PreconditionFailedException;
import com.unboundid.scim2.common.exceptions.ResourceConflictException;
import com.unboundid.scim2.common.exceptions.ScimException;
import com.unboundid.scim2.common.filters.Filter;
import com.unboundid.scim2.common.messages.ListResponse;
import com.unboundid.scim2.common.messages.PatchOpType;
import com.unboundid.scim2.common.messages.PatchOperation;
import com.unboundid.scim2.common.messages.PatchRequest;
import com.unboundid.scim2.common.types.Email;
import com.unboundid.scim2.common.types.Meta;
import com.unboundid.scim2.common.types.UserResource;
import com.unboundid.scim2.common.utils.FilterEvaluator;
import com.unboundid.scim2.common.utils.Parser;
import io.unitycatalog.control.model.User;
import io.unitycatalog.server.auth.MetastoreAdmins;
import io.unitycatalog.server.auth.UnityCatalogAuthorizer;
import io.unitycatalog.server.auth.annotation.AuthorizeExpression;
import io.unitycatalog.server.auth.annotation.AuthorizeResourceKey;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.exception.GlobalExceptionHandler;
import io.unitycatalog.server.exception.Scim2RuntimeException;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.UserRepository;
import io.unitycatalog.server.persist.model.CreateUser;
import io.unitycatalog.server.persist.model.Privileges;
import io.unitycatalog.server.persist.model.UpdateUser;
import io.unitycatalog.server.utils.IdentityUtils;
import io.unitycatalog.server.utils.Scim2Utils;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SCIM2-compliant user management.
 *
 * <p>This will be a SCIM 2.0 compliant user management service. The UC User data model will be a
 * minimal set of required fields that are necessary to support user management/exchange.
 *
 * <ul>
 *   <li>id - internal unique identifier for user
 *   <li>name - maps to SCIM displayName
 *   <li>email - maps to SCIM primary email
 *   <li>externalId - maps to SCIM external id
 *   <li>userName - maps to SCIM primary email
 * </ul>
 */
@ExceptionHandler(GlobalExceptionHandler.class)
public class Scim2UserService {
  private static final Logger LOGGER = LoggerFactory.getLogger(Scim2UserService.class);

  /** The account behind the password sign-in; see AuthService#adminLogin. */
  private static final String BOOTSTRAP_ADMIN_EMAIL = "admin";

  /** The only attribute a PATCH may change here. */
  private static final String ACTIVE_ATTRIBUTE = "active";

  private final UserRepository userRepository;
  private final MetastoreAdmins metastoreAdmins;
  private final UnityCatalogAuthorizer authorizer;

  public Scim2UserService(UnityCatalogAuthorizer authorizer, Repositories repositories) {
    this.authorizer = authorizer;
    this.userRepository = repositories.getUserRepository();
    this.metastoreAdmins = new MetastoreAdmins(authorizer, repositories);
  }

  @Get("")
  @Produces("application/scim+json")
  @StatusCode(200)
  @AuthorizeExpression("#principal != null")
  @AuthorizeResourceKey(METASTORE)
  public ListResponse<UserResource> getScimUsers(
      @Param("filter") Optional<String> filter,
      @Param("startIndex") Optional<Integer> startIndex,
      @Param("count") Optional<Integer> count) {
    final Filter userFilter =
        filter.filter(f -> !f.isEmpty()).<Filter>map(this::parseFilter).orElse(null);
    FilterEvaluator filterEvaluator = new FilterEvaluator();

    List<UserResource> userResourcesList =
        userRepository
            .listUsers(
                startIndex.orElse(1) - 1,
                count.orElse(50),
                m -> match(filterEvaluator, userFilter, asUserResource(m)))
            .stream()
            .map(Scim2Utils::asUserResource)
            .toList();

    Meta meta = new Meta();
    meta.setCreated(Calendar.getInstance());
    meta.setLastModified(Calendar.getInstance());
    meta.setResourceType("User");

    ListResponse<UserResource> userResources =
        new ListResponse<>(
            userResourcesList.size(),
            userResourcesList,
            startIndex.orElse(1),
            userResourcesList.size());
    userResources.setMeta(meta);

    return userResources;
  }

  @Post("")
  @Produces("application/scim+json")
  @StatusCode(201)
  @AuthorizeExpression("#authorize(#principal, #metastore, OWNER)")
  @AuthorizeResourceKey(METASTORE)
  public UserResource createScimUser(UserResource userResource) {
    // Get primary email address
    Email primaryEmail =
        userResource.getEmails().stream()
            .filter(Email::getPrimary)
            .findFirst()
            .orElseThrow(
                () ->
                    new Scim2RuntimeException(
                        new PreconditionFailedException("User does not have a primary email.")));

    String pictureUrl = "";
    if (userResource.getPhotos() != null && !userResource.getPhotos().isEmpty()) {
      pictureUrl = userResource.getPhotos().get(0).getValue().toString();
    }
    try {
      User user =
          userRepository.createUser(
              CreateUser.builder()
                  .name(userResource.getDisplayName())
                  .email(primaryEmail.getValue())
                  .active(userResource.getActive())
                  .externalId(userResource.getExternalId())
                  .pictureUrl(pictureUrl)
                  .build());
      return asUserResource(user);
    } catch (BaseException e) {
      if (e.getErrorCode() == ErrorCode.ALREADY_EXISTS) {
        throw new Scim2RuntimeException(new ResourceConflictException(e.getMessage()));
      } else {
        throw new Scim2RuntimeException(new BadRequestException(e.getMessage()));
      }
    }
  }

  @Get("/{id}")
  @Produces("application/scim+json")
  @StatusCode(200)
  @AuthorizeExpression("#principal != null")
  @AuthorizeResourceKey(METASTORE)
  public UserResource getUser(@Param("id") String id) {
    return asUserResource(userRepository.getUser(id));
  }

  @Put("/{id}")
  @Produces("application/scim+json")
  @StatusCode(200)
  @AuthorizeExpression("#authorize(#principal, #metastore, OWNER)")
  @AuthorizeResourceKey(METASTORE)
  public UserResource updateUser(@Param("id") String id, UserResource userResource) {
    UserResource user = asUserResource(userRepository.getUser(id));
    if (!id.equals(userResource.getId())) {
      throw new Scim2RuntimeException(new ResourceConflictException("User id mismatch."));
    }

    // A PUT writes `active` back like any other field, so it is a third way to deactivate an
    // account and needs the same guard as PATCH and DELETE.
    if (Boolean.FALSE.equals(userResource.getActive())) {
      requireNotTheBootstrapAdministrator(userRepository.getUser(id));
    }

    UpdateUser updateUser =
        UpdateUser.builder()
            .name(userResource.getDisplayName())
            .active(userResource.getActive())
            .externalId(userResource.getExternalId())
            .build();

    return asUserResource(userRepository.updateUser(id, updateUser));
  }

  /**
   * Everything the user owns, for a caller about to decide whether it is safe to delete them.
   *
   * <p>Read-only and cheap: one indexed query per securable type, not the per-object walk the UI's
   * access view has to perform. A delete can rely on the same scan (see {@link #deleteUser}); this
   * endpoint exists so the confirmation dialog can show the consequences before anything happens
   * rather than discovering them from a rejection.
   */
  @Get("/{id}/ownedObjects")
  @ProducesJson
  @StatusCode(200)
  @AuthorizeExpression("#authorizeAny(#principal, #metastore, OWNER)")
  @AuthorizeResourceKey(METASTORE)
  public Map<String, Object> getOwnedObjects(@Param("id") String id) {
    User user = userRepository.getUser(id);
    return Map.of(
        "principal", user.getEmail(),
        "owned", userRepository.listOwnedSecurables(user.getEmail()));
  }

  /**
   * Deactivates the user, or — with {@code purge=true} — removes them permanently.
   *
   * <p>Deactivation is the default and is a pure state change: the user is refused on every request
   * from that moment (AuthDecorator re-reads the state per request, so tokens already issued stop
   * working immediately), while their grants, the objects they own and their email stay exactly as
   * they were. That is what makes it reversible.
   *
   * <p>Purging is the irreversible one, and the only reason to reach for it: it is what frees the
   * email and externalId for reuse. It does not block access any harder than deactivation already
   * does. Everything it needs to be safe is checked here rather than trusted to the caller.
   */
  @Delete("/{id}")
  @AuthorizeExpression("#authorizeAny(#principal, #metastore, OWNER)")
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse deleteUser(
      @Param("id") String id,
      @Param("purge") Optional<String> purge,
      @Param("reassign_to") Optional<String> reassignTo,
      @Param("confirm_principal") Optional<String> confirmPrincipal) {
    User user = userRepository.getUser(id);

    if (!purge.map(Boolean::parseBoolean).orElse(false)) {
      // Deactivate. The user's authorizations are deliberately left untouched: a deactivation that
      // silently erased them would be advertised as reversible while being anything but -- on
      // reactivation the user would find they no longer own what they created, with nothing in the
      // UI to explain it.
      requireNotTheBootstrapAdministrator(user);
      userRepository.deleteUser(user.getId());
      return HttpResponse.of(HttpStatus.OK);
    }

    purgeUser(user, reassignTo, confirmPrincipal);
    return HttpResponse.of(HttpStatus.OK);
  }

  private void purgeUser(
      User user, Optional<String> reassignTo, Optional<String> confirmPrincipal) {
    String targetEmail = user.getEmail();
    UUID targetId = UUID.fromString(Objects.requireNonNull(user.getId()));

    // These refusals are reported as BaseException rather than the SCIM errors the rest of this
    // service raises, because a Scim2RuntimeException is answered with HTTP 500 whatever status
    // the SCIM error carries (see GlobalExceptionHandler) -- a guard that fired deliberately would
    // reach the caller as a server fault, with the explanation stripped out on the way. A purge is
    // a Unity Catalog extension rather than a SCIM operation, so a Unity Catalog error fits it.

    // Typing the principal back is the only check that catches "right button, wrong row". It
    // matters more here than it looks: the user list mixes people with Relyt instance principals
    // (whose email column holds an instance identifier), and deleting one of those takes a whole
    // compute instance off UC.
    if (confirmPrincipal.filter(targetEmail::equals).isEmpty()) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT,
          "confirm_principal must be set to '"
              + targetEmail
              + "' to permanently delete this user.");
    }

    if (targetEmail.equals(IdentityUtils.findPrincipalEmailAddress())) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT,
          "A user cannot permanently delete their own account. Ask another administrator.");
    }

    // The bootstrap administrator is the account behind the password sign-in, which is the way back
    // in when the identity provider is unavailable. Worse, that sign-in does not check whether the
    // account still exists: delete it and /auth/admin/login still returns a session, but every
    // request made with it is rejected -- a dead end that looks like it worked.
    if (BOOTSTRAP_ADMIN_EMAIL.equals(targetEmail)) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT,
          "The bootstrap administrator cannot be permanently deleted. Deactivate it instead.");
    }

    // Requiring deactivation first is not ceremony: it puts the irreversible step behind a state
    // the administrator can observe. If this principal turns out to still be in use, deactivation
    // surfaces that within minutes and is undone with one click.
    if (user.getState() != User.StateEnum.DISABLED) {
      throw new BaseException(
          ErrorCode.FAILED_PRECONDITION,
          "User must be deactivated before being permanently deleted: " + targetEmail);
    }

    // Deleting an administrator is one of the ways a metastore can be left without one.
    metastoreAdmins.requireAnotherAdminRemains(targetId);

    List<UserRepository.OwnedSecurable> owned = userRepository.listOwnedSecurables(targetEmail);
    String reassignedTo = null;
    if (!owned.isEmpty()) {
      reassignedTo =
          reassignTo.orElseThrow(
              () ->
                  new BaseException(
                      ErrorCode.ABORTED,
                      "User owns "
                          + owned.size()
                          + " object(s); set reassign_to to the principal that should take"
                          + " ownership. See GET .../scim2/Users/"
                          + user.getId()
                          + "/ownedObjects for the list."));
      reassignOwnership(targetId, targetEmail, reassignedTo);
    }

    authorizer.clearAuthorizationsForPrincipal(targetId);
    userRepository.purgeUser(user.getId());
    // The only record that this happened: UC has no audit log, and a permanent delete that also
    // moves ownership is not something to have to reconstruct from its effects.
    LOGGER.warn(
        "Permanently deleted user {} ({}) by {}; {} object(s) reassigned to {}",
        targetEmail,
        targetId,
        IdentityUtils.findPrincipalEmailAddress(),
        owned.size(),
        reassignedTo == null ? "nobody" : reassignedTo);
  }

  /**
   * Hands both halves of ownership to the new owner: the {@code owner} columns, which is what gets
   * displayed, and the OWNER policies, which is what actually authorizes.
   *
   * <p>The policies are granted before the old principal's are cleared, so no object is left
   * without an owner even if the purge fails partway.
   */
  private void reassignOwnership(UUID targetId, String targetEmail, String newOwnerEmail) {
    if (newOwnerEmail.equals(targetEmail)) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT, "reassign_to cannot be the user being deleted.");
    }
    User newOwner;
    try {
      newOwner = userRepository.getUserByEmail(newOwnerEmail);
    } catch (BaseException e) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT, "reassign_to names an unknown user: " + newOwnerEmail);
    }
    if (newOwner.getState() != User.StateEnum.ENABLED) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT,
          "reassign_to names a deactivated user: "
              + newOwnerEmail
              + ". Ownership would be handed to an account that cannot act on it.");
    }

    UUID newOwnerId = UUID.fromString(Objects.requireNonNull(newOwner.getId()));
    userRepository.reassignOwnership(targetEmail, newOwnerEmail);
    authorizer
        .listAuthorizationsForPrincipal(targetId)
        .forEach(
            (resource, privileges) -> {
              if (privileges.contains(Privileges.OWNER)) {
                authorizer.grantAuthorization(newOwnerId, resource, Privileges.OWNER);
              }
            });
  }

  /**
   * Changes a user's {@code active} state.
   *
   * <p>Both forms RFC 7644 §3.5.2.1 allows are accepted, because both turn up in practice: a
   * pathless {@code replace} carries the attributes as an object ({@code {"active": false}}, which
   * is what Okta and this project's own UI send), while a {@code replace} that names {@code active}
   * in its path carries the bare value. An operation that asks for neither is answered with 501
   * rather than guessed at.
   *
   * <p>Authorization matches DELETE without {@code purge}, which flips the same flag. Until this
   * was added the endpoint carried no authorization annotation at all, and UnityAccessDecorator
   * lets a method without an expression through — so any signed-in user could deactivate an
   * administrator.
   */
  @Patch("/{id}")
  @AuthorizeExpression("#authorizeAny(#principal, #metastore, OWNER)")
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse patchUser(@Param("id") String id, PatchRequest patchRequest) {
    return patchRequest.getOperations().stream()
        .filter(operation -> operation.getOpType() == PatchOpType.REPLACE)
        .map(Scim2UserService::activeFrom)
        .flatMap(Optional::stream)
        .findFirst()
        .map(active -> applyActive(id, active))
        .orElse(HttpResponse.of(HttpStatus.NOT_IMPLEMENTED));
  }

  /** The new {@code active} value a replace asks for, if it asks for one at all. */
  private static Optional<Boolean> activeFrom(PatchOperation operation) {
    JsonNode value = operation.getJsonNode();
    if (operation.getPath() == null) {
      // Pathless: the value is the object of attributes to replace.
      return booleanMember(value, ACTIVE_ATTRIBUTE);
    }
    // A named path carries the attribute's new value directly. SCIM attribute names are
    // case-insensitive, so "active" and "Active" name the same attribute.
    return ACTIVE_ATTRIBUTE.equalsIgnoreCase(operation.getPath().toString())
            && value != null
            && value.isBoolean()
        ? Optional.of(value.booleanValue())
        : Optional.empty();
  }

  /** Reads one boolean member by name, matching case-insensitively as SCIM requires. */
  private static Optional<Boolean> booleanMember(JsonNode object, String name) {
    if (object == null || !object.isObject()) {
      return Optional.empty();
    }
    Iterator<String> members = object.fieldNames();
    while (members.hasNext()) {
      String member = members.next();
      if (member.equalsIgnoreCase(name)) {
        JsonNode value = object.get(member);
        return value != null && value.isBoolean()
            ? Optional.of(value.booleanValue())
            : Optional.empty();
      }
    }
    return Optional.empty();
  }

  private HttpResponse applyActive(String id, boolean active) {
    if (!active) {
      requireNotTheBootstrapAdministrator(userRepository.getUser(id));
    }
    userRepository.updateUser(id, UpdateUser.builder().active(active).build());
    return HttpResponse.of(HttpStatus.OK);
  }

  /**
   * Refuses to deactivate the bootstrap administrator, by either route.
   *
   * <p>It is the account behind the password sign-in — the way back in when the identity provider
   * is unavailable. Deactivating it does not simply disable one user: it closes that door, and
   * opening it again needs an administrator who can still sign in, which is exactly what may not
   * exist when the provider is the reason anyone reached for the password.
   *
   * <p>Upstream lets a deployment deactivate its bootstrap account once real accounts exist (see
   * the note in UnityAccessUtil#initializeAdmin). That predates the password sign-in this fork
   * added, which gave the same account a second job, so the two cannot both hold here.
   */
  private void requireNotTheBootstrapAdministrator(User user) {
    if (BOOTSTRAP_ADMIN_EMAIL.equals(user.getEmail())) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT,
          "The bootstrap administrator cannot be deactivated; it is the way back in when the"
              + " identity provider is unavailable.");
    }
  }

  private Filter parseFilter(String filter) {
    try {
      return Parser.parseFilter(filter);
    } catch (BadRequestException e) {
      throw new Scim2RuntimeException(e);
    }
  }

  private boolean match(FilterEvaluator filterEvaluator, Filter userFilter, UserResource user) {
    try {
      return (userFilter == null
          || userFilter.visit(filterEvaluator, user.asGenericScimResource().getObjectNode()));
    } catch (ScimException e) {
      throw new Scim2RuntimeException(e);
    }
  }
}
