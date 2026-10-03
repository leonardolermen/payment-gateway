package com.gateway.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.create.CreatePixPayment;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class PaymentOrderIdIntegrationTest extends ServiceIntegrationTestBase {

  CreatePixPayment pixFor(String orderId) {
    return new CreatePixPayment(
        merchant, ProviderEnvironment.TEST, Money.brl(1000), "o-1", null, null, null, orderId);
  }

  @Test
  void theOrderIdTravelsToTheRowAndTheEvent() {
    String orderId = Ulid.next();

    Payment payment = paymentService.create(pixFor(orderId));

    assertThat(paymentQueries.get(merchant, payment.id()).orderId()).isEqualTo(orderId);
    assertThat(outboxPayload(payment.id(), "payment.pending"))
        .contains("\"order_id\":\"" + orderId);
  }

  @Test
  void aSecondActiveAttemptOnTheSameOrderIsRefusedByTheDatabase() {
    String orderId = Ulid.next();
    paymentService.create(pixFor(orderId));

    assertThatThrownBy(() -> paymentService.create(pixFor(orderId)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
