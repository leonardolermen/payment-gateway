package com.gateway.payments.support;

import com.gateway.payments.card.persistence.SavedCardRepositoryImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The beans the app provides in production: a clock, the bank, where credentials live and a meter
 * registry.
 */
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

  @Bean
  RecordingCardProvider recordingCardProvider(MutableClock clock) {
    return new RecordingCardProvider(clock);
  }

  @Bean
  SimpleMeterRegistry meterRegistry() {
    return new SimpleMeterRegistry();
  }

  @Bean
  TestSealer testSealer() {
    return new TestSealer();
  }

  @Bean
  @Primary
  FailableSavedCardRepository failableSavedCardRepository(SavedCardRepositoryImpl real) {
    return new FailableSavedCardRepository(real);
  }
}
