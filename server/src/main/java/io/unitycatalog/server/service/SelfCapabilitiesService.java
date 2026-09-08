package io.unitycatalog.server.service;

import static io.unitycatalog.server.model.SecurableType.METASTORE;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.StatusCode;
import io.unitycatalog.control.model.User;
import io.unitycatalog.server.auth.UnityCatalogAuthorizer;
import io.unitycatalog.server.auth.annotation.AuthorizeExpression;
import io.unitycatalog.server.auth.annotation.AuthorizeResourceKey;
import io.unitycatalog.server.exception.GlobalExceptionHandler;
import io.unitycatalog.server.persist.MetastoreRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.UserRepository;
import io.unitycatalog.server.persist.model.Privileges;
import io.unitycatalog.server.security.JwtClaim;
import io.unitycatalog.server.utils.ServerProperties;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * Reports what the calling identity is allowed to do, for callers that cannot infer it themselves.
 *
 * <p>A metastore admin holds OWNER on the metastore resource, and that fact is not discoverable
 * through any read API: the metastore summary carries no owner and SCIM is readable by everyone. A
 * UI therefore cannot tell an admin apart from an ordinary user, and is forced to either grey out
 * actions the admin is in fact allowed to perform, or offer actions that fail with 403. This
 * endpoint removes the guess.
 */
@Slf4j
@ExceptionHandler(GlobalExceptionHandler.class)
public class SelfCapabilitiesService {
  private final UnityCatalogAuthorizer authorizer;
  private final UserRepository userRepository;
  private final MetastoreRepository metastoreRepository;
  private final ServerProperties serverProperties;

  public SelfCapabilitiesService(
      UnityCatalogAuthorizer authorizer,
      Repositories repositories,
      ServerProperties serverProperties) {
    this.authorizer = authorizer;
    this.userRepository = repositories.getUserRepository();
    this.metastoreRepository = repositories.getMetastoreRepository();
    this.serverProperties = serverProperties;
  }

  @Get("")
  @ProducesJson
  @StatusCode(200)
  @AuthorizeExpression("#principal != null")
  @AuthorizeResourceKey(METASTORE)
  public Map<String, Boolean> getCapabilities() {
    return Map.of("metastore_admin", isMetastoreAdmin());
  }

  private boolean isMetastoreAdmin() {
    // With authorization disabled every caller is unrestricted; reporting false would make a UI
    // hide actions that actually succeed.
    if (!serverProperties.isAuthorizationEnabled()) {
      return true;
    }
    ServiceRequestContext ctx = ServiceRequestContext.current();
    DecodedJWT decodedJWT = ctx.attr(AuthDecorator.DECODED_JWT_ATTR);
    if (decodedJWT == null) {
      return false;
    }
    Claim sub = decodedJWT.getClaim(JwtClaim.SUBJECT.key());
    if (sub == null || sub.asString() == null) {
      return false;
    }
    try {
      User user = userRepository.getUserByEmail(sub.asString());
      return user != null
          && user.getId() != null
          && authorizer.authorize(
              UUID.fromString(user.getId()),
              metastoreRepository.getMetastoreId(),
              Privileges.OWNER);
    } catch (Exception e) {
      // An unknown subject is simply not an admin; the endpoint must not fail the caller for it.
      log.debug("Could not resolve caller for capability lookup", e);
      return false;
    }
  }
}
