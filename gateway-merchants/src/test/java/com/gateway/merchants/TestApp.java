package com.gateway.merchants;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Minimal module context: only what MerchantsConfiguration imports. No scan — the app works the
 * same way.
 */
@SpringBootApplication(scanBasePackages = "com.gateway.merchants.none")
@Import(MerchantsConfiguration.class)
public class TestApp {}
