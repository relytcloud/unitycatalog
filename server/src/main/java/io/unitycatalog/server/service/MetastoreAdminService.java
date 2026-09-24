package io.unitycatalog.server.service;

import static io.unitycatalog.server.model.SecurableType.METASTORE;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.armeria.server.annotation.StatusCode;
import io.unitycatalog.server.auth.MetastoreAdmins;
import io.unitycatalog.server.auth.UnityCatalogAuthorizer;
import io.unitycatalog.server.auth.annotation.AuthorizeExpression;
import io.unitycatalog.server.auth.annotation.AuthorizeResourceKey;
import io.unitycatalog.server.exception.GlobalExceptionHandler;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.utils.IdentityUtils;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Managing who administers the metastore.
 *
 * <p>An administrator is a user holding OWNER on the metastore, and until this service there was no
 * way to make one: OWNER is deliberately absent from the public Privilege enum, so it cannot travel
 * through /permissions, and the only grant in the codebase was the bootstrap that runs once on an
 * empty database. A deployment therefore had exactly one administrator — the built-in {@code admin}
 * account reached with a shared password — and no supported way to promote a real person, which is
 * the opposite of what the deployment guide asks for.
 *
 * <p>A dedicated endpoint rather than adding OWNER to Privilege: that enum is shared by every
 * securable, so admitting OWNER would simultaneously open ownership transfer on catalogs, schemas
 * and tables through /permissions — a larger feature that needs its own design. Keeping this narrow
 * also keeps the "one enabled administrator always remains" invariant in one place.
 */
@ExceptionHandler(GlobalExceptionHandler.class)
public class MetastoreAdminService {
  private static final Logger LOGGER = LoggerFactory.getLogger(MetastoreAdminService.class);

  private final MetastoreAdmins metastoreAdmins;

  public MetastoreAdminService(UnityCatalogAuthorizer authorizer, Repositories repositories) {
    this.metastoreAdmins = new MetastoreAdmins(authorizer, repositories);
  }

  /**
   * The metastore's administrators.
   *
   * <p>Readable by any administrator, and the only way to see this at all: OWNER is filtered out of
   * /permissions responses, so no other endpoint reveals who holds it.
   */
  @Get("")
  @ProducesJson
  @StatusCode(200)
  @AuthorizeExpression("#authorizeAny(#principal, #metastore, OWNER)")
  @AuthorizeResourceKey(METASTORE)
  public Map<String, List<String>> listAdmins() {
    return Map.of("admins", metastoreAdmins.list());
  }

  @Put("/{email}")
  @AuthorizeExpression("#authorizeAny(#principal, #metastore, OWNER)")
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse grantAdmin(@Param("email") String email) {
    metastoreAdmins.grant(email);
    LOGGER.warn(
        "{} made {} a metastore administrator", IdentityUtils.findPrincipalEmailAddress(), email);
    return HttpResponse.of(HttpStatus.OK);
  }

  /**
   * Takes the privilege away.
   *
   * <p>Revoking your own is allowed — handing over is a real thing to want — but only while someone
   * else still holds it, which {@link MetastoreAdmins} enforces for every caller.
   */
  @Delete("/{email}")
  @AuthorizeExpression("#authorizeAny(#principal, #metastore, OWNER)")
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse revokeAdmin(@Param("email") String email) {
    metastoreAdmins.revoke(email);
    LOGGER.warn(
        "{} revoked the metastore administrator privilege from {}",
        IdentityUtils.findPrincipalEmailAddress(),
        email);
    return HttpResponse.of(HttpStatus.OK);
  }
}
