package com.gateway.app.observability;

import com.gateway.payments.jobs.persistence.JobRepository;
import com.gateway.payments.payment.StuckPayments;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.reconciliation.Divergences;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class MetricsConfiguration {
  @Bean
  OperationsMetrics operationsMetrics(
      Divergences divergences,
      JobRepository jobs,
      StuckPayments stuck,
      PaymentRepository payments,
      JdbcTemplate jdbc,
      MeterRegistry registry,
      Clock clock) {
    return new OperationsMetrics(divergences, jobs, stuck, payments, jdbc, registry, clock);
  }
}
