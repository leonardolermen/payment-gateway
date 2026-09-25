package com.gateway.payments;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.gateway.payments.support.ServiceTestConfig;
import org.springframework.context.annotation.Import;

/** Minimal module context: only what PaymentsConfiguration imports, plus the beans the app provides (clock, bank, credentials). No scan — the app works the same way. */
@SpringBootApplication(scanBasePackages = "com.gateway.payments.none")
@Import({PaymentsConfiguration.class, ServiceTestConfig.class})
public class TestApp {}
