package com.gateway.payments.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** The beans the app provides in production: a clock, the bank, and where credentials live. */
@TestConfiguration(proxyBeanMethods = false)
public class ServiceTestConfig {
  @Bean
  MutableClock clock() {
    return new MutableClock();
  }

  @Bean
  RecordingPixProvider recordingPixProvider(MutableClock clock) {
    return new RecordingPixProvider(clock);
  }

  @Bean
  InMemoryCredentialLookup credentialLookup() {
    return new InMemoryCredentialLookup();
  }

  @Bean
  RecordingBoletoProvider recordingBoletoProvider(MutableClock clock, RecordingPixProvider pix) {
    return new RecordingBoletoProvider(clock, pix);
  }
}
