package com.gateway.billing.support;

import com.gateway.billing.order.checkout.TokenHasher;
import com.gateway.kernel.security.Sha256;
import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.JobHandler;
import com.gateway.payments.jobs.JobType;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class BillingTestConfig {

  /** The real hasher is the app's (it needs the merchants pepper); billing tests only need one. */
  @Bean
  TokenHasher tokenHasher() {
    return token -> Sha256.hex(token);
  }

  /**
   * SEND_EMAIL's handler is the app's; here the type only needs an owner so the registry starts.
   */
  @Bean
  JobHandler sendEmailStub() {
    return new JobHandler() {
      @Override
      public JobType type() {
        return JobType.SEND_EMAIL;
      }

      @Override
      public boolean run(String refId, Instant now) {
        return true;
      }

      @Override
      public Job afterFailure(Job job, Instant now, String error) {
        return job;
      }
    };
  }
}
