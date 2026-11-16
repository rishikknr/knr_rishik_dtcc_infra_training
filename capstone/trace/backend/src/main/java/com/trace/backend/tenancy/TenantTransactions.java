package com.trace.backend.tenancy;

import java.util.HashMap;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Component
public class TenantTransactions {
  private final JdbcTemplate jdbc;
  private final TenantIdentityProvider identities;
  private final TransactionTemplate transactions;
  private final TransactionTemplate snapshots;

  public TenantTransactions(
      JdbcTemplate jdbc, TenantIdentityProvider identities, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.identities = identities;
    transactions = new TransactionTemplate(manager);
    // Never change an organization's GUC inside an existing resource transaction.
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    snapshots = new TransactionTemplate(manager);
    snapshots.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    snapshots.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    snapshots.setReadOnly(true);
  }

  public <T> T execute(Function<TenantContext, T> work) {
    var identity = identities.selectedIdentity();
    if (identity == null)
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    return executeSelected(identity, work);
  }

  /** A consistent, read-only registry snapshot for graph counts and paged payloads. */
  public <T> T executeSnapshot(Function<TenantContext, T> work) {
    var identity = identities.selectedIdentity();
    if (identity == null)
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    return executeSelected(identity, work, snapshots);
  }

  /**
   * Internal worker entry: identity comes only from a persisted leased job, never a request body.
   */
  public <T> T executeForRun(
      UUID org, UUID user, UUID run, UUID owner, UUID token, Function<TenantContext, T> work) {
    return executeSelected(
        new TenantIdentityProvider.Selection(org, user),
        t -> {
          boolean owned =
              Boolean.TRUE.equals(
                  jdbc.queryForObject(
                      "SELECT trace_lock_discovery(?,?,?,?,?)",
                      Boolean.class,
                      org,
                      user,
                      run,
                      owner,
                      token));
          if (!owned) throw new IllegalStateException("Discovery lease lost");
          return work.apply(t);
        });
  }

  private <T> T executeSelected(
      TenantIdentityProvider.Selection identity, Function<TenantContext, T> work) {
    return executeSelected(identity, work, transactions);
  }

  private <T> T executeSelected(
      TenantIdentityProvider.Selection identity,
      Function<TenantContext, T> work,
      TransactionTemplate template) {
    return template.execute(
        status -> {
          // LOCAL to this transaction; commit/rollback clears it before pool reuse.
          jdbc.queryForObject(
              "SELECT set_config('trace.organization_id', ?, true)",
              String.class,
              identity.organizationId().toString());
          jdbc.queryForObject(
              "SELECT set_config('trace.user_id', ?, true)",
              String.class,
              identity.userId().toString());
          var roles =
              jdbc.queryForList(
                  "SELECT organization_role FROM trace_active_membership(?, ?)",
                  String.class,
                  identity.organizationId(),
                  identity.userId());
          if (roles.size() != 1) {
            if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes attrs) {
              var session = attrs.getRequest().getSession(false);
              if (session != null)
                session.removeAttribute(com.trace.backend.config.TenantConfiguration.ORGANIZATION);
            }
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "Active organization membership required");
          }
          var teamRoles = new HashMap<UUID, String>();
          jdbc.query(
              "SELECT tm.team_id, tm.role FROM team_memberships tm JOIN teams team ON"
                  + " team.id=tm.team_id AND team.organization_id=tm.organization_id AND"
                  + " team.active WHERE tm.organization_id = ? AND tm.user_id = ?",
              rs -> {
                teamRoles.put(rs.getObject("team_id", UUID.class), rs.getString("role"));
              },
              identity.organizationId(),
              identity.userId());
          return work.apply(
              new TenantContext(
                  identity.organizationId(), identity.userId(), roles.getFirst(), teamRoles));
        });
  }
}
