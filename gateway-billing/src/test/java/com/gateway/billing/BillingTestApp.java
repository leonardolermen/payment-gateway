package com.gateway.billing;

import com.gateway.billing.support.BillingTestConfig;
import com.gateway.payments.PaymentsConfiguration;
import com.gateway.payments.support.ServiceTestConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication(scanBasePackages = "com.gateway.billing.none")
@Import({
  PaymentsConfiguration.class,
  ServiceTestConfig.class,
  BillingConfiguration.class,
  BillingTestConfig.class
})
public class BillingTestApp {}
