package com.gateway.payments.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.jobs.persistence.JobRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class JobRunnerTest {
  private static final Instant NOW = Instant.parse("2026-10-07T17:55:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final JobRepository jobs = mock(JobRepository.class);
  private final TransactionTemplate tx = mock(TransactionTemplate.class);

  @AfterEach
  void clear() {
    MDC.clear();
  }

  @SuppressWarnings("unchecked")
  private JobRunner runnerWith(JobHandler handler, Job claimed) {
    when(tx.execute(any()))
        .thenAnswer(
            invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0))
                    .doInTransaction(new SimpleTransactionStatus()));
    when(jobs.claimDue(any(), anyInt(), any(), any())).thenReturn(List.of(claimed));

    // JobHandlers is complete-or-nothing: every other type gets an inert handler.
    List<JobHandler> all = new ArrayList<>(List.of(handler));
    for (JobType type : JobType.values()) {
      if (type != handler.type()) {
        all.add(new InertHandler(type));
      }
    }

    return new JobRunner(jobs, new JobHandlers(all), PaymentsProperties.defaults(), tx, CLOCK);
  }

  @Test
  void theHandlerRunsWithTheJobInTheContext() {
    Job claimed = Job.reconcile(CLOCK);
    Map<String, String> seen = new HashMap<>();
    JobHandler handler = new RecordingHandler(seen, false);

    runnerWith(handler, claimed).runDue(NOW);

    assertThat(seen).containsEntry("job", "RECONCILE").containsEntry("jobId", claimed.id());
    assertThat(MDC.get("job")).isNull();
    assertThat(MDC.get("jobId")).isNull();
  }

  @Test
  void clearsTheContextEvenWhenTheHandlerThrows() {
    Job claimed = Job.reconcile(CLOCK);
    JobHandler handler = new RecordingHandler(new HashMap<>(), true);

    runnerWith(handler, claimed).runDue(NOW);

    assertThat(MDC.get("job")).isNull();
    assertThat(MDC.get("jobId")).isNull();
  }

  private record InertHandler(JobType type) implements JobHandler {
    @Override
    public boolean run(String refId, Instant now) {
      return true;
    }

    @Override
    public Job afterFailure(Job job, Instant now, String error) {
      return job;
    }
  }

  private record RecordingHandler(Map<String, String> seen, boolean fails) implements JobHandler {
    @Override
    public JobType type() {
      return JobType.RECONCILE;
    }

    @Override
    public boolean run(String refId, Instant now) {
      seen.put("job", MDC.get("job"));
      seen.put("jobId", MDC.get("jobId"));
      if (fails) {
        throw new IllegalStateException("handler failed");
      }
      return true;
    }

    @Override
    public Job afterFailure(Job job, Instant now, String error) {
      return job;
    }
  }
}
