package com.gateway.billing.support;

import com.gateway.billing.BillingTestApp;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.payments.payment.PaymentQueries;
import com.gateway.payments.payment.PaymentService;
import com.gateway.payments.support.MutableClock;
import com.gateway.payments.support.RecordingBoletoProvider;
import com.gateway.payments.support.RecordingCardProvider;
import com.gateway.payments.support.RecordingPixProvider;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/** One container and one context per module test run; each test uses a fresh merchant. */
@SpringBootTest(classes = BillingTestApp.class)
public abstract class BillingIntegrationTestBase {

  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired protected MutableClock clock;
  @Autowired protected RecordingPixProvider bank;
  @Autowired protected RecordingBoletoProvider boletos;
  @Autowired protected RecordingCardProvider cards;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected PaymentService paymentService;
  @Autowired protected PaymentQueries paymentQueries;

  protected MerchantId merchant;

  @BeforeEach
  void freshMerchant() {
    clock.reset();
    merchant = MerchantId.next();
  }
}
