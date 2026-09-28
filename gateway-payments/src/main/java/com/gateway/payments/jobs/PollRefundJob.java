package com.gateway.payments.jobs;

import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.refund.RefundPollingService;
import com.gateway.payments.refund.RefundService;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Asks the bank whether a PROCESSING refund settled, and gives up on it once the job goes DEAD. */
public class PollRefundJob implements JobHandler {
  private static final Logger log = LoggerFactory.getLogger(PollRefundJob.class);

  private final RefundPollingService polling;
  private final RefundService refunds;
  private final PaymentsProperties properties;

  public PollRefundJob(
      RefundPollingService polling, RefundService refunds, PaymentsProperties properties) {
    this.polling = polling;
    this.refunds = refunds;
    this.properties = properties;
  }

  @Override
  public JobType type() {
    return JobType.POLL_REFUND;
  }

  @Override
  public boolean run(String refId, Instant now) {
    return polling.poll(refId);
  }

  /**
   * POLL_REFUND polls every 5 minutes for {@code refundPollMaxAttempts} (288 = 24 h): exponential
   * backoff with 8 attempts gave up after about 4 h, while the bank may take a day to settle a
   * devolucao.
   */
  @Override
  public Job afterFailure(Job job, Instant now, String error) {
    return job.reschedule(
        now.plus(RefundService.POLL_EVERY), error, properties.refundPollMaxAttempts());
  }

  @Override
  public Job finish(Job job, Job next, Instant now) {
    if ("DEAD".equals(next.status())) {
      try {
        refunds.giveUp(job.refId());
      } catch (RuntimeException e) {
        // Stays DEAD with the error; the refund is still PROCESSING and visible to support.
        log.error("could not give up on refund {}", job.refId(), e);
      }
    }
    return next;
  }
}
