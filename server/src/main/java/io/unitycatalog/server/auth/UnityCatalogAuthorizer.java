package io.unitycatalog.server.auth;

import io.unitycatalog.server.persist.model.Privileges;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The definition of an authorizer for Unity Catalog.
 *
 * <p>This interface defines the methods that an authorizer for Unity Catalog must implement. An
 * authorizer is responsible for enforcing access control policies for the Unity Catalog API.
 *
 * <p>This definition provides the ability to manage parent-child relationships between resources
 * and with the appropriate implementation can enforce access control policies based on these
 * relationships.
 */
public interface UnityCatalogAuthorizer {
  boolean grantAuthorization(UUID principal, UUID resource, Privileges action);

  boolean revokeAuthorization(UUID principal, UUID resource, Privileges action);

  boolean clearAuthorizationsForPrincipal(UUID principal);

  boolean clearAuthorizationsForResource(UUID resource);

  boolean addHierarchyChild(UUID parent, UUID child);

  boolean removeHierarchyChild(UUID parent, UUID child);

  boolean removeHierarchyChildren(UUID resource);

  UUID getHierarchyParent(UUID resource);

  boolean authorize(UUID principal, UUID resource, Privileges action);

  boolean authorizeAny(UUID principal, UUID resource, Privileges... actions);

  boolean authorizeAll(UUID principal, UUID resource, Privileges... actions);

  List<Privileges> listAuthorizations(UUID principal, UUID resource);

  Map<UUID, List<Privileges>> listAuthorizations(UUID resource);

  /**
   * Every authorization held by one principal, keyed by the resource it applies to.
   *
   * <p>The inverse of {@link #listAuthorizations(UUID)}, and the only way to answer "what does this
   * user hold?" without walking the whole catalog tree and asking per object. Deleting a user needs
   * exactly this: the resources they own have to be handed to someone else before their policies go
   * away.
   *
   * <p>The resources are whatever the policy store still names, which is not the same as what still
   * exists — deleting a catalog does not clear the policies of the schemas and tables under it (see
   * CatalogService#deleteCatalog), so callers that resolve these ids must tolerate misses.
   */
  Map<UUID, List<Privileges>> listAuthorizationsForPrincipal(UUID principal);
}
