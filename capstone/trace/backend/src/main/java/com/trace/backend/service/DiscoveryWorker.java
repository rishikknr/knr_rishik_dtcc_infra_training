package com.trace.backend.service;

import com.trace.backend.exception.ApiException;
import com.trace.backend.integration.connectors.CredentialCipher;
import com.trace.backend.integration.processor.IntegrationProcessor;
import com.trace.backend.repository.*;
import com.trace.backend.repository.DiscoveryQueueRepository.Lease;
import com.trace.backend.tenancy.*;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(
    name = "trace.discovery.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class DiscoveryWorker {
  private static final Set<String> TRANSIENT =
      Set.of(
          "NETWORK_TIMEOUT",
          "UNAVAILABLE",
          "RATE_LIMITED",
          "PROCESSOR_UNAVAILABLE",
          "PROCESSOR_ERROR",
          "S3_THROTTLED",
          "S3_UNAVAILABLE");
  private final UUID owner = UUID.randomUUID();
  private final DiscoveryQueueRepository queue;
  private final TenantTransactions tenants;
  private final ConnectionRepository connections;
  private final RegistryRepository guard;
  private final CredentialCipher cipher;
  private final IntegrationProcessor processor;
  private final ImportService imports;
  private final ObjectMapper json;
  private final int parallelism, leaseSeconds;
  private final ExecutorService executor;
  private final ConcurrentMap<Lease, FutureTask<Void>> active = new ConcurrentHashMap<>();
  private volatile boolean stopping;

  public DiscoveryWorker(
      DiscoveryQueueRepository queue,
      TenantTransactions tenants,
      ConnectionRepository connections,
      RegistryRepository guard,
      CredentialCipher cipher,
      IntegrationProcessor processor,
      ImportService imports,
      ObjectMapper json,
      @Value("${trace.discovery.workers:2}") int workers,
      @Value("${trace.discovery.lease-seconds:45}") int seconds) {
    if (workers < 1 || workers > 4 || seconds < 5 || seconds > 300)
      throw new IllegalArgumentException("Invalid discovery worker bounds");
    this.queue = queue;
    this.tenants = tenants;
    this.connections = connections;
    this.guard = guard;
    this.cipher = cipher;
    this.processor = processor;
    this.imports = imports;
    this.json = json;
    parallelism = workers;
    leaseSeconds = seconds;
    executor =
        Executors.newFixedThreadPool(
            workers,
            r -> {
              var thread = new Thread(r, "trace-discovery");
              thread.setDaemon(true);
              return thread;
            });
  }

  @Scheduled(
      fixedDelayString = "${trace.discovery.poll-ms:1000}",
      initialDelayString = "${trace.discovery.initial-delay-ms:1000}")
  public synchronized void tick() {
    if (stopping) return;
    try {
      for (var entry : active.entrySet())
        if (!"LIVE".equals(queue.heartbeat(entry.getKey(), leaseSeconds)))
          entry.getValue().cancel(true);
      while (active.size() < parallelism) {
        var claimed = queue.claim(owner, leaseSeconds);
        if (claimed.isEmpty()) break;
        Lease lease = claimed.get();
        var task =
            new FutureTask<Void>(
                () -> {
                  execute(lease);
                  return null;
                });
        active.put(lease, task);
        executor.execute(
            () -> {
              try {
                task.run();
              } finally {
                active.remove(lease);
              }
            });
      }
    } catch (RuntimeException e) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Discovery dispatch unavailable ({})", e.getClass().getSimpleName());
    }
  }

  private <T> T scoped(Lease l, java.util.function.Function<TenantContext, T> work) {
    return tenants.executeForRun(l.org(), l.user(), l.run(), l.owner(), l.token(), work);
  }

  private Map<String, Object> checked(TenantContext t, Lease l) {
    var c = connections.get(t, l.connection(), true);
    guard.requireTeamWrite(t, (UUID) c.get("owner_team_id"));
    if ("DISABLED".equals(c.get("status")))
      throw new ApiException(
          org.springframework.http.HttpStatus.CONFLICT,
          "CONNECTION_DISABLED",
          "Connection is disabled");
    // Snapshot comparison uses the stored configuration timestamp, not a browser field.
    if (!connections.configurationMatches(t, l.run(), (Timestamp) c.get("updated_at")))
      throw new ApiException(
          org.springframework.http.HttpStatus.CONFLICT,
          "CONFIGURATION_CHANGED",
          "Connection changed after discovery was queued");
    return c;
  }

  private void execute(Lease lease) {
    try {
      var request =
          scoped(
              lease,
              t -> {
                var c = checked(t, lease);
                var secret = connections.secret(t, lease.connection());
                byte[] clear =
                    cipher.decrypt(
                        t.organizationId(),
                        lease.connection(),
                        (byte[]) secret.get("ciphertext"),
                        (byte[]) secret.get("nonce"));
                try {
                  return Map.of(
                      "type",
                      c.get("platform"),
                      "configuration",
                      c.get("configuration"),
                      "credentials",
                      json.readValue(clear, Map.class),
                      "test",
                      false);
                } finally {
                  Arrays.fill(clear, (byte) 0);
                }
              });
      var response = processor.call("acquire", request);
      String code = response.path("status").asString("INVALID_PROVIDER_RESPONSE");
      if (!"CONNECTED".equals(code)) {
        fail(
            lease,
            com.trace.backend.integration.connectors.ConnectorOutcomes.safe(
                response.path("reasonCode").asString(code)));
        return;
      }
      var batch = processor.batch(response.path("batch"));
      scoped(
          lease,
          t -> {
            checked(t, lease);
            UUID preview = imports.previewForWorker(t, lease.connection(), batch);
            if (!queue.finish(
                lease,
                batch.snapshotComplete() ? "COMPLETED" : "PARTIALLY_COMPLETED",
                preview,
                batch.datasets().size(),
                batch.warnings(),
                null,
                1)) throw new IllegalStateException("Discovery lease lost");
            return preview;
          });
    } catch (RuntimeException e) {
      if (stopping || Thread.currentThread().isInterrupted())
        return; // Restart recovery is the persisted lease's responsibility.
      fail(
          lease,
          e instanceof ApiException a
              ? a.code
              : e instanceof ResponseStatusException
                  ? "ACCESS_REVOKED"
                  : e instanceof IllegalStateException ? "LEASE_LOST" : "ACQUISITION_FAILED");
    }
  }

  private void fail(Lease lease, String code) {
    if (stopping) return;
    String safe = code != null && code.matches("[A-Z_]{1,80}") ? code : "ACQUISITION_FAILED";
    int delay =
        (int) Math.min(300, 5L * (1L << Math.max(0, lease.attempt() - 1)))
            + ThreadLocalRandom.current().nextInt(3);
    queue.finish(
        lease, TRANSIENT.contains(safe) ? "RETRY_WAIT" : "FAILED", null, 0, List.of(), safe, delay);
  }

  public int activeCount() {
    return active.size();
  }

  @PreDestroy
  public void close() {
    stopping = true;
    active.values().forEach(f -> f.cancel(true));
    executor.shutdownNow();
  }
}

@Configuration
@EnableScheduling
class DiscoveryScheduling {}
