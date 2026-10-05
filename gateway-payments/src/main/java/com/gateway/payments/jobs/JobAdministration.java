package com.gateway.payments.jobs;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.jobs.persistence.JobRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * The operator's hands on the queue. The writes are conditional UPDATEs, so a refusal only says "0
 * rows"; this is where that becomes a 404 (no such job) or a 409 (a worker holds it, or it is not
 * PENDING), by reading the row once after the refusal.
 */
public class JobAdministration {
  private final JobRepository jobs;
  private final PaymentsProperties properties;
  private final Clock clock;

  public JobAdministration(JobRepository jobs, PaymentsProperties properties, Clock clock) {
    this.jobs = jobs;
    this.properties = properties;
    this.clock = clock;
  }

  public List<Job> list(JobQuery query) {
    return jobs.find(query);
  }

  public Job runNow(String id) {
    Instant now = clock.instant();

    boolean forced = jobs.forceDue(id, now, properties.jobLease(), properties.reconcileLease());
    Job job = get(id);

    if (!forced) {
      throw new DomainException("JOB_IN_FLIGHT", "job " + id + " is running; try after its lease");
    }

    return job;
  }

  public Job giveUp(String id, String note) {
    Instant now = clock.instant();

    boolean gaveUp = jobs.giveUp(id, note, now, properties.jobLease(), properties.reconcileLease());
    Job job = get(id);

    if (!gaveUp) {
      throw new DomainException(
          "JOB_NOT_PENDING",
          "job " + id + " is " + job.status() + " or running; nothing to give up");
    }

    return job;
  }

  private Job get(String id) {
    return jobs.findById(id).orElseThrow(() -> new NotFoundException("job", id));
  }
}
