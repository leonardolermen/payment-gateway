package com.gateway.billing.order.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.billing.customer.Customer;
import com.gateway.billing.customer.CustomerAddress;
import com.gateway.billing.customer.CustomerFactory;
import com.gateway.billing.customer.CustomerService;
import com.gateway.billing.order.AttemptRequest;
import com.gateway.billing.order.Order;
import com.gateway.billing.order.OrderFactory;
import com.gateway.billing.order.OrderService;
import com.gateway.billing.support.BillingIntegrationTestBase;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.payment.create.CardDataFactory;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CheckoutServiceIntegrationTest extends BillingIntegrationTestBase {
  @Autowired CheckoutService checkout;
  @Autowired CheckoutTokens tokens;
  @Autowired OrderService orders;
  @Autowired CustomerService customers;

  /** An order born with a token hash, like every order created through the service. */
  record Opened(Order order, String token) {}

  Opened openOrder(String reference) {
    return openOrder(reference, newCustomer());
  }

  // One document per merchant (uq_customers_document), so a test with two orders shares a customer.
  Customer newCustomer() {
    return customers.create(
        CustomerFactory.fromRequest(
            merchant,
            ProviderEnvironment.TEST,
            "Ana Silva",
            "52998224725",
            null,
            new CustomerAddress.Raw("Rua A 1", "Centro", "Sao Paulo", "SP", "01310100"),
            clock));
  }

  Opened openOrder(String reference, Customer customer) {
    CheckoutTokens.Issued issued = tokens.issue();
    Order order =
        orders.create(
            OrderFactory.standalone(
                merchant,
                ProviderEnvironment.TEST,
                Money.brl(5000),
                reference,
                null,
                customer.id(),
                null,
                null,
                issued.hash(),
                clock));

    return new Opened(order, issued.token().value());
  }

  AttemptRequest.CardAttempt cardAttempt() {
    return new AttemptRequest.CardAttempt(
        new CardChoice.NewCard(
            CardDataFactory.from(
                "4024007153763171", "ANA SILVA", "12/2030", "123", null, YearMonth.of(2026, 9)),
            false),
        1,
        true,
        "LOJA");
  }

  @Test
  void aValidTokenResolvesTheOrderAndItsAttempts() {
    Opened opened = openOrder("checkout-1");

    CheckoutView view = checkout.get(opened.token());

    assertThat(view.order().id()).isEqualTo(opened.order().id());
    assertThat(view.attempts()).isEmpty();
    assertThat(view.activeAttempt()).isEmpty();
  }

  @Test
  void anUnknownOrMalformedTokenIs404() {
    String unknown = "chk_" + "z".repeat(43);

    assertThatThrownBy(() -> checkout.get(unknown))
        .isInstanceOf(NotFoundException.class)
        .hasMessageNotContaining(unknown);
    assertThatThrownBy(() -> checkout.get("garbage"))
        .isInstanceOf(NotFoundException.class)
        .hasMessageNotContaining("garbage");
  }

  @Test
  void aPixAttemptThroughTheCheckoutCreatesAPendingPayment() {
    Opened opened = openOrder("checkout-3");

    Payment pix = checkout.attempt(opened.token(), new AttemptRequest.PixAttempt(600));

    assertThat(pix.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(pix.orderId()).isEqualTo(opened.order().id());
    assertThat(checkout.get(opened.token()).activeAttempt()).map(Payment::id).hasValue(pix.id());
    // The flows record API on the created event whatever the caller passed; threading the source
    // through them is deferred, so this pins what is recorded today.
    String source =
        jdbc.queryForObject(
            "SELECT source FROM payments.payment_events WHERE payment_id = ? AND sequence = 1",
            String.class,
            pix.id());
    assertThat(source).isEqualTo("API");
  }

  @Test
  void aClosedOrderRefusesAnAttemptAndACancel() {
    Opened opened = openOrder("checkout-4");
    Payment pix = checkout.attempt(opened.token(), new AttemptRequest.PixAttempt(600));
    orders.cancel(merchant, opened.order().id());

    assertThatThrownBy(() -> checkout.attempt(opened.token(), new AttemptRequest.PixAttempt(600)))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("CHECKOUT_ORDER_CLOSED");
    assertThatThrownBy(() -> checkout.cancelAttempt(opened.token(), pix.id()))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("CHECKOUT_ORDER_CLOSED");
  }

  @Test
  void aPaymentOfAnotherOrderIs404() {
    Customer customer = newCustomer();
    Opened mine = openOrder("checkout-5a", customer);
    Opened other = openOrder("checkout-5b", customer);
    Payment ofOther = checkout.attempt(other.token(), new AttemptRequest.PixAttempt(600));

    assertThatThrownBy(() -> checkout.payment(mine.token(), ofOther.id()))
        .isInstanceOf(NotFoundException.class);
    assertThat(checkout.payment(other.token(), ofOther.id()).id()).isEqualTo(ofOther.id());
  }

  @Test
  void cancelAttemptCancelsAPendingPixAndRefusesACard() {
    Customer customer = newCustomer();
    Opened pixOrder = openOrder("checkout-6a", customer);
    Payment pix = checkout.attempt(pixOrder.token(), new AttemptRequest.PixAttempt(600));

    Payment canceled = checkout.cancelAttempt(pixOrder.token(), pix.id());

    assertThat(canceled.status()).isEqualTo(PaymentStatus.CANCELED);

    Opened cardOrder = openOrder("checkout-6b", customer);
    Payment card = checkout.attempt(cardOrder.token(), cardAttempt());

    assertThatThrownBy(() -> checkout.cancelAttempt(cardOrder.token(), card.id()))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("CHECKOUT_CANNOT_CANCEL_CARD");
  }
}
