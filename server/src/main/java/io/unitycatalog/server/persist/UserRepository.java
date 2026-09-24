package io.unitycatalog.server.persist;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.unitycatalog.control.model.User;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.dao.CatalogInfoDAO;
import io.unitycatalog.server.persist.dao.CredentialDAO;
import io.unitycatalog.server.persist.dao.ExternalLocationDAO;
import io.unitycatalog.server.persist.dao.FunctionInfoDAO;
import io.unitycatalog.server.persist.dao.RegisteredModelInfoDAO;
import io.unitycatalog.server.persist.dao.SchemaInfoDAO;
import io.unitycatalog.server.persist.dao.TableInfoDAO;
import io.unitycatalog.server.persist.dao.UserDAO;
import io.unitycatalog.server.persist.dao.VolumeInfoDAO;
import io.unitycatalog.server.persist.model.CreateUser;
import io.unitycatalog.server.persist.model.UpdateUser;
import io.unitycatalog.server.persist.utils.PagedListingHelper;
import io.unitycatalog.server.persist.utils.TransactionManager;
import io.unitycatalog.server.utils.IdentityUtils;
import io.unitycatalog.server.utils.ValidationUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UserRepository {
  private static final Logger LOGGER = LoggerFactory.getLogger(UserRepository.class);
  private final SessionFactory sessionFactory;
  private static final PagedListingHelper<UserDAO> LISTING_HELPER =
      new PagedListingHelper<>(UserDAO.class);

  public UserRepository(Repositories repositories, SessionFactory sessionFactory) {
    this.sessionFactory = sessionFactory;
  }

  public User createUser(CreateUser createUser) {
    ValidationUtils.validateUserEmail(createUser.getEmail());
    User user =
        new User()
            .id(UUID.randomUUID().toString())
            .name(createUser.getName())
            .email(createUser.getEmail())
            .externalId(createUser.getExternalId())
            .state(User.StateEnum.ENABLED)
            .createdAt(System.currentTimeMillis());

    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          if (getUserByEmail(session, user.getEmail()) != null
              || (user.getExternalId() != null
                  && getUserByExternalId(session, user.getExternalId()) != null)) {
            throw new BaseException(
                ErrorCode.ALREADY_EXISTS, "User already exists: " + user.getEmail());
          }
          session.persist(UserDAO.from(user));
          return user;
        },
        "Failed to create user",
        /* readOnly = */ false);
  }

  public List<User> listUsers(int startIndex, int maxUsers, Predicate<User> filter) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          int count = 0;
          List<User> users = new ArrayList<>();

          Optional<String> nextPageToken = Optional.empty();
          boolean hasMore = true;
          while (users.size() < maxUsers && hasMore) {
            List<UserDAO> userDAOs =
                LISTING_HELPER.listEntity(session, Optional.empty(), nextPageToken, null);

            List<User> userBlock =
                userDAOs.stream()
                    .map(UserDAO::toUser)
                    .filter(filter::test)
                    .collect(Collectors.toList());

            if (count + userBlock.size() < startIndex) {
              // if we haven't reached the start index, skip the block
              count += userBlock.size();
            } else if (count >= startIndex) {
              // we've already reached the start index, add the whole block
              users.addAll(userBlock);
              count += userBlock.size();
            } else {
              // we'll reach the start index in this block somewhere.
              int firstIndex = startIndex - count;
              users.addAll(userBlock.subList(firstIndex, userBlock.size()));
              count += userBlock.size();
            }
            nextPageToken =
                Optional.ofNullable(
                    userDAOs.isEmpty() ? null : userDAOs.get(userDAOs.size() - 1).getName());
            hasMore = nextPageToken.isPresent();
          }

          return users.subList(0, Math.min(users.size(), maxUsers));
        },
        "Failed to list users",
        /* readOnly = */ true);
  }

  public User getUser(String id) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          UserDAO userDAO = getUserById(session, id);
          if (userDAO == null) {
            throw new BaseException(ErrorCode.NOT_FOUND, "User not found: " + id);
          }
          return userDAO.toUser();
        },
        "Failed to get user",
        /* readOnly = */ true);
  }

  public UserDAO getUserById(Session session, String id) {
    Query<UserDAO> query = session.createQuery("FROM UserDAO WHERE id = :id", UserDAO.class);
    query.setParameter("id", UUID.fromString(id));
    query.setMaxResults(1);
    return query.uniqueResult();
  }

  public User getUserByEmail(String email) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          UserDAO userDAO = getUserByEmail(session, email);
          if (userDAO == null) {
            throw new BaseException(ErrorCode.NOT_FOUND, "User not found: " + email);
          }
          return userDAO.toUser();
        },
        "Failed to get user by email",
        /* readOnly = */ true);
  }

  public UserDAO getUserByEmail(Session session, String email) {
    Query<UserDAO> query = session.createQuery("FROM UserDAO WHERE email = :email", UserDAO.class);
    query.setParameter("email", email);
    query.setMaxResults(1);
    return query.uniqueResult();
  }

  public UserDAO getUserByExternalId(Session session, String externalId) {
    Query<UserDAO> query =
        session.createQuery("FROM UserDAO WHERE externalId = :externalId", UserDAO.class);
    query.setParameter("externalId", externalId);
    query.setMaxResults(1);
    return query.uniqueResult();
  }

  public User updateUser(String id, UpdateUser updateUser) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          UserDAO userDAO = getUserById(session, id);
          if (userDAO == null) {
            throw new BaseException(ErrorCode.NOT_FOUND, "User not found: " + id);
          }
          if (updateUser.getName() != null) {
            userDAO.setName(updateUser.getName());
          }
          if (updateUser.getActive() != null) {
            userDAO.setState(
                updateUser.getActive()
                    ? User.StateEnum.ENABLED.toString()
                    : User.StateEnum.DISABLED.toString());
          }
          if (updateUser.getExternalId() != null) {
            userDAO.setExternalId(updateUser.getExternalId());
          }
          session.merge(userDAO);
          return userDAO.toUser();
        },
        "Failed to update user",
        /* readOnly = */ false);
  }

  public void deleteUser(String id) {
    TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          UserDAO userDAO = getUserById(session, id);
          if (userDAO != null) {
            userDAO.setState(User.StateEnum.DISABLED.toString());
            session.merge(userDAO);
            LOGGER.info("Deleted user: {}", id);
            return null;
          } else {
            throw new BaseException(ErrorCode.NOT_FOUND, "User not found: " + id);
          }
        },
        "Failed to delete user",
        /* readOnly = */ false);
  }

  /**
   * Every entity carrying an {@code owner} column that names a user by email.
   *
   * <p>Ownership lives in two places that have to move together: this column, which is metadata (no
   * server-side authorization reads it — the UI does, to decide whether to enable a button), and
   * the OWNER rows in the policy store, which are what actually authorize. Reassigning one without
   * the other leaves an object whose displayed owner and effective owner disagree.
   *
   * <p>{@code ModelVersionInfoDAO} is here but absent from {@link #listOwnedSecurables}: it has an
   * owner column, yet MODEL_VERSION is not a SecurableType — a version is governed by its
   * registered model — so a version follows a reassignment without ever blocking one.
   */
  private static final List<String> OWNER_BEARING_ENTITIES =
      List.of(
          "CatalogInfoDAO",
          "SchemaInfoDAO",
          "TableInfoDAO",
          "VolumeInfoDAO",
          "FunctionInfoDAO",
          "RegisteredModelInfoDAO",
          "ModelVersionInfoDAO",
          "ExternalLocationDAO",
          "CredentialDAO");

  /**
   * A securable whose {@code owner} column names a particular user.
   *
   * <p>Serialized with the snake_case names the rest of the catalog API uses for securables, so a
   * caller can feed {@code securable_type} and {@code full_name} straight back into
   * /permissions/{securable_type}/{full_name}.
   */
  public record OwnedSecurable(
      @JsonProperty("securable_type") String securableType,
      @JsonProperty("full_name") String fullName,
      @JsonProperty("securable_id") String securableId) {}

  /**
   * Everything the given user owns, named the way an administrator would recognise it.
   *
   * <p>One indexed query per entity type, not the per-object walk the UI's access view has to do:
   * this has to be cheap enough to run before every delete.
   */
  public List<OwnedSecurable> listOwnedSecurables(String ownerEmail) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          List<OwnedSecurable> owned = new ArrayList<>();
          AncestorNames names = new AncestorNames(session);

          // Top-level securables: the name is already the full name.
          for (CatalogInfoDAO dao : ownedBy(session, CatalogInfoDAO.class, ownerEmail)) {
            owned.add(securable("catalog", dao.getName(), dao.getId()));
          }
          for (ExternalLocationDAO dao : ownedBy(session, ExternalLocationDAO.class, ownerEmail)) {
            owned.add(securable("external_location", dao.getName(), dao.getId()));
          }
          for (CredentialDAO dao : ownedBy(session, CredentialDAO.class, ownerEmail)) {
            owned.add(securable("credential", dao.getName(), dao.getId()));
          }

          for (SchemaInfoDAO dao : ownedBy(session, SchemaInfoDAO.class, ownerEmail)) {
            String parent = names.catalog(dao.getCatalogId());
            owned.add(securable("schema", qualify(parent, dao.getName()), dao.getId()));
          }
          for (TableInfoDAO dao : ownedBy(session, TableInfoDAO.class, ownerEmail)) {
            String parent = names.schema(dao.getSchemaId());
            owned.add(securable("table", qualify(parent, dao.getName()), dao.getId()));
          }
          for (VolumeInfoDAO dao : ownedBy(session, VolumeInfoDAO.class, ownerEmail)) {
            String parent = names.schema(dao.getSchemaId());
            owned.add(securable("volume", qualify(parent, dao.getName()), dao.getId()));
          }
          for (FunctionInfoDAO dao : ownedBy(session, FunctionInfoDAO.class, ownerEmail)) {
            String parent = names.schema(dao.getSchemaId());
            owned.add(securable("function", qualify(parent, dao.getName()), dao.getId()));
          }
          for (RegisteredModelInfoDAO dao :
              ownedBy(session, RegisteredModelInfoDAO.class, ownerEmail)) {
            String parent = names.schema(dao.getSchemaId());
            owned.add(securable("registered_model", qualify(parent, dao.getName()), dao.getId()));
          }

          return owned;
        },
        "Failed to list owned securables",
        /* readOnly = */ true);
  }

  /**
   * Moves every {@code owner} column naming {@code fromEmail} to {@code toEmail}.
   *
   * <p>{@code created_by} and {@code updated_by} are deliberately left alone: they record who did
   * something, which stays true after the account is gone. Only ownership — a live responsibility —
   * transfers.
   *
   * <p>This covers the metadata half of ownership only. The caller is responsible for granting the
   * new owner the corresponding OWNER policies; see {@link #OWNER_BEARING_ENTITIES}.
   */
  public void reassignOwnership(String fromEmail, String toEmail) {
    TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          int reassigned = 0;
          for (String entity : OWNER_BEARING_ENTITIES) {
            reassigned +=
                session
                    .createMutationQuery(
                        "UPDATE " + entity + " SET owner = :newOwner WHERE owner = :oldOwner")
                    .setParameter("newOwner", toEmail)
                    .setParameter("oldOwner", fromEmail)
                    .executeUpdate();
          }
          LOGGER.info("Reassigned {} object(s) from {} to {}", reassigned, fromEmail, toEmail);
          return null;
        },
        "Failed to reassign ownership",
        /* readOnly = */ false);
  }

  /**
   * Removes the user row outright, which is what frees the email and externalId for reuse.
   *
   * <p>Unlike {@link #deleteUser}, this cannot be undone and is not what stops the user reaching
   * the server — a DISABLED state already does that on every request. Callers must have dealt with
   * the user's ownership and policies first.
   */
  public void purgeUser(String id) {
    TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          UserDAO userDAO = getUserById(session, id);
          if (userDAO == null) {
            throw new BaseException(ErrorCode.NOT_FOUND, "User not found: " + id);
          }
          session.remove(userDAO);
          LOGGER.info("Purged user: {} ({})", id, userDAO.getEmail());
          return null;
        },
        "Failed to purge user",
        /* readOnly = */ false);
  }

  private static <T> List<T> ownedBy(Session session, Class<T> entity, String ownerEmail) {
    return session
        .createQuery("FROM " + entity.getSimpleName() + " WHERE owner = :owner", entity)
        .setParameter("owner", ownerEmail)
        .getResultList();
  }

  private static OwnedSecurable securable(String type, String fullName, UUID id) {
    return new OwnedSecurable(type, fullName, id == null ? null : id.toString());
  }

  /** {@code parent.name}, or the bare name when the parent could not be resolved. */
  private static String qualify(String parent, String name) {
    return parent == null ? name : parent + "." + name;
  }

  /**
   * Resolves the qualified names of the catalogs and schemas an owned object sits under, looking
   * each one up at most once.
   *
   * <p>Every lookup returns null rather than throwing when the ancestor is already gone: deleting a
   * catalog does not remove what was under it from the policy store (CatalogService#deleteCatalog
   * clears only the catalog's own), so a user's owned set can name objects whose parents no longer
   * exist — and a delete must not be blocked by one of those.
   */
  private static final class AncestorNames {
    private final Session session;
    private final Map<UUID, String> catalogs = new HashMap<>();
    private final Map<UUID, String> schemas = new HashMap<>();

    AncestorNames(Session session) {
      this.session = session;
    }

    /** The catalog's name, or null. */
    String catalog(UUID catalogId) {
      if (catalogId == null) {
        return null;
      }
      if (catalogs.containsKey(catalogId)) {
        return catalogs.get(catalogId);
      }
      CatalogInfoDAO dao = session.get(CatalogInfoDAO.class, catalogId);
      String name = dao == null ? null : dao.getName();
      catalogs.put(catalogId, name);
      return name;
    }

    /** {@code catalog.schema}, or null. */
    String schema(UUID schemaId) {
      if (schemaId == null) {
        return null;
      }
      if (schemas.containsKey(schemaId)) {
        return schemas.get(schemaId);
      }
      SchemaInfoDAO dao = session.get(SchemaInfoDAO.class, schemaId);
      String name = dao == null ? null : qualify(catalog(dao.getCatalogId()), dao.getName());
      schemas.put(schemaId, name);
      return name;
    }
  }

  public UUID findPrincipalId() {
    String principalEmailAddress = IdentityUtils.findPrincipalEmailAddress();
    if (principalEmailAddress != null) {
      return UUID.fromString(getUserByEmail(principalEmailAddress).getId());
    } else {
      return null;
    }
  }
}
