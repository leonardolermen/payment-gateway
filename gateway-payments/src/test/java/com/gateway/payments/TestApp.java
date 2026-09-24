package com.gateway.payments;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** Minimal module context: only what PaymentsConfiguration imports. No scan — the app works the same way. */
@SpringBootApplication(scanBasePackages = "com.gateway.payments.none")
@Import(PaymentsConfiguration.class)
public class TestApp {}
