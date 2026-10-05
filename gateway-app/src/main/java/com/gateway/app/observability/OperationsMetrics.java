package com.gateway.app.observability;

import com.gateway.payments.jobs.persistence.JobRepository;
import com.gateway.payments.payment.PaymentCount;
import com.gateway.payments.payment.StuckPayments;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.reconciliation.DivergenceCount;
import com.gateway.payments.reconciliation.Divergences;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The state of the operation as gauges: payments by status, stuck payments, the divergence queue,
 * jobs, webhook deliveries and the outbox. Counted on a schedule rather than on scrape, so a slow
 * query never holds a Prometheus scrape and a scrape storm never reaches the database.
 *
 * <p>Lives in the app because it reads the webhook-delivery lib's table and knows every module.
 */
public class OperationsMetrics {
  private static final Logger log = LoggerFactory.getLogger(OperationsMetrics.class);

  /**
   * The job runner polls every few seconds; a PENDING job due for longer than this means it is
   * behind, not merely between polls.
   */
  private static final Duration OVERDUE_GRACE = Duration.ofMinutes(5);

  /** Finished jobs are deleted, so these are the only statuses a row can have. */
  private static final List<String> JOB_STATUSES = List.of("PENDING", "DEAD");

  private final Divergences divergences;
  private final JobRepository jobs;
  private final StuckPayments stuck;
  private final PaymentRepository payments;
  private final JdbcTemplate jdbc;
  private final MeterRegistry registry;
  private final Clock clock;

  private final Map<String, Map<Tags, AtomicLong>> gauges = new ConcurrentHashMap<>();

  public OperationsMetrics(
      Divergences divergences,
      JobRepository jobs,
      StuckPayments stuck,
      PaymentRepository payments,
      JdbcTemplate jdbc,
      MeterRegistry registry,
      Clock clock) {
    this.divergences = divergences;
    this.jobs = jobs;
    this.stuck = stuck;
    this.payments = payments;
    this.jdbc = jdbc;
    this.registry = registry;
    this.clock = clock;
  }

  /**
   * Each family is refreshed on its own: one failing query must not blank the others, and nothing
   * may escape, so the scheduler keeps running the next refreshes and the WARN names the family
   * that went stale.
   */
  @Scheduled(fixedDelayString = "${gateway.metrics.refresh-ms:30000}")
  public void refresh() {
    Instant now = clock.instant();

    guarded("gateway_payments", this::refreshPayments);
    guarded("gateway_payments_stuck", () -> refreshStuck(now));
    guarded("gateway_divergences_open", this::refreshDivergences);
    guarded("gateway_jobs", () -> refreshJobs(now));
    guarded("gateway_webhook_deliveries", this::refreshDeliveries);
    guarded("gateway_outbox_pending", this::refreshOutbox);
  }

  // Not gateway_payments_total: the Prometheus client strips _total from a gauge (the suffix is
  // reserved for counters), so that name was exposed as gateway_payments anyway.
  private void refreshPayments() {
    Map<Tags, Long> counts = new HashMap<>();
    for (PaymentCount count : payments.countByStatusMethodProviderEnvironment()) {
      Tags tags =
          Tags.of(
              "status",
              count.status(),
              "method",
              count.method(),
              "provider",
              count.provider(),
              "environment",
              count.environment());
      counts.put(tags, count.count());
    }

    publish("gateway_payments", counts);
  }

  private void refreshStuck(Instant now) {
    StuckPayments.StuckCounts counts = stuck.counts(now);

    publish(
        "gateway_payments_stuck",
        Map.of(
            Tags.of("kind", "created_too_long"),
            counts.createdTooLong(),
            Tags.of("kind", "pending_past_expiry"),
            counts.pendingPastExpiry()));
  }

  private void refreshDivergences() {
    Map<Tags, Long> counts = new HashMap<>();
    for (DivergenceCount count : divergences.countOpen()) {
      counts.put(Tags.of("origin", count.origin().name(), "kind", count.kind()), count.count());
    }

    publish("gateway_divergences_open", counts);
  }

  private void refreshJobs(Instant now) {
    Map<Tags, Long> counts = new HashMap<>();
    for (String status : JOB_STATUSES) {
      counts.put(Tags.of("status", status), jobs.countByStatus(status));
    }

    publish("gateway_jobs", counts);
    publish(
        "gateway_jobs_overdue", Map.of(Tags.empty(), jobs.countOverdue(now.minus(OVERDUE_GRACE))));
  }

  // The webhook-delivery lib has no count API, and a lib release for one COUNT is not worth it; the
  // app already owns the lib's JPA wiring (AppConfiguration), so reading its table here adds no
  // coupling the app does not have. If the lib renames the table, this family WARNs and goes stale.
  private void refreshDeliveries() {
    Map<Tags, Long> counts = new HashMap<>();
    jdbc.query(
        "SELECT status, count(*) FROM webhook_delivery.deliveries GROUP BY status",
        row -> {
          counts.put(Tags.of("status", row.getString(1)), row.getLong(2));
        });

    publish("gateway_webhook_deliveries", counts);
  }

  private void refreshOutbox() {
    Long pending =
        jdbc.queryForObject(
            "SELECT count(*) FROM payments.outbox WHERE status = 'PENDING'", Long.class);

    publish("gateway_outbox_pending", Map.of(Tags.empty(), pending == null ? 0L : pending));
  }

  /**
   * A label set seen once keeps its gauge for the life of the process and reports 0 when its rows
   * are gone: Prometheus prefers a stable series over a vanishing one (a gap makes rate() and
   * absence alerts misfire). The cardinality is bounded by the enums behind the labels.
   */
  private void publish(String name, Map<Tags, Long> counts) {
    Map<Tags, AtomicLong> family = gauges.computeIfAbsent(name, key -> new ConcurrentHashMap<>());

    for (AtomicLong holder : family.values()) {
      holder.set(0);
    }

    counts.forEach(
        (tags, count) -> family.computeIfAbsent(tags, key -> register(name, key)).set(count));
  }

  private AtomicLong register(String name, Tags tags) {
    AtomicLong holder = new AtomicLong();
    Gauge.builder(name, holder, AtomicLong::get).tags(tags).register(registry);
    return holder;
  }

  private static void guarded(String family, Runnable refresh) {
    try {
      refresh.run();
    } catch (RuntimeException e) {
      log.warn("could not refresh the {} metrics", family, e);
    }
  }
}
