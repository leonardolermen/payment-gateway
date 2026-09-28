package com.gateway.payments.support;

import com.gateway.payments.card.persistence.SavedCardRepositoryImpl;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

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

  @Bean
  RecordingCardProvider recordingCardProvider(MutableClock clock) {
    return new RecordingCardProvider(clock);
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
