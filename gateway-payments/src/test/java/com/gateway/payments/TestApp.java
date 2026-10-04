package com.gateway.payments;

import com.gateway.payments.support.BillingJobOwnersStub;
import com.gateway.payments.support.ServiceTestConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Minimal module context: only what PaymentsConfiguration imports, plus the beans the app provides
 * (clock, bank, credentials). No scan — the app works the same way.
 */
@SpringBootApplication(scanBasePackages = "com.gateway.payments.none")
@Import({PaymentsConfiguration.class, ServiceTestConfig.class, BillingJobOwnersStub.class})
public class TestApp {}
