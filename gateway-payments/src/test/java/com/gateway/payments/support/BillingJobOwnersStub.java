package com.gateway.payments.support;

import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.JobHandler;
import com.gateway.payments.jobs.JobType;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Billing's handlers live in gateway-billing; here the type only needs an owner so the registry
 * starts. Not in {@link ServiceTestConfig}: gateway-billing's tests import that one too, and there
 * the real handler would collide with this stub ("two job handlers").
 */
@TestConfiguration(proxyBeanMethods = false)
public class BillingJobOwnersStub {
  @Bean
  JobHandler expireOrderStub() {
    return new JobHandler() {
      @Override
      public JobType type() {
        return JobType.EXPIRE_ORDER;
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

  @Bean
  JobHandler billSubscriptionStub() {
    return new JobHandler() {
      @Override
      public JobType type() {
        return JobType.BILL_SUBSCRIPTION;
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

  @Bean
  JobHandler dunningRetryStub() {
    return new JobHandler() {
      @Override
      public JobType type() {
        return JobType.DUNNING_RETRY;
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
