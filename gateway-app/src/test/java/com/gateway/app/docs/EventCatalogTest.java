package com.gateway.app.docs;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.billing.customer.Customer;
import com.gateway.billing.customer.CustomerAddress;
import com.gateway.billing.customer.CustomerFactory;
import com.gateway.billing.customer.CustomerService;
import com.gateway.billing.order.Order;
import com.gateway.billing.order.OrderFactory;
import com.gateway.billing.order.OrderService;
import com.gateway.billing.plan.Plan;
import com.gateway.billing.plan.PlanFactory;
import com.gateway.billing.plan.PlanInterval;
import com.gateway.billing.subscription.BillingPeriod;
import com.gateway.billing.subscription.Subscription;
import com.gateway.billing.subscription.SubscriptionFactory;
import com.gateway.billing.subscription.SubscriptionService;
import com.gateway.billing.subscription.billing.InvoicePayloads;
import com.gateway.billing.subscription.billing.IssuedInvoice;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.dispute.DisputeEvents;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentEvents;
import com.gateway.payments.payment.boleto.BoletoDetails;
import com.gateway.payments.payment.card.CardDetails;
import com.gateway.payments.payment.pix.PixDetails;
import com.gateway.payments.reconciliation.ReconciliationDivergence;
import com.gateway.payments.refund.Refund;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds the README event catalog to the payloads the gateway really emits. Values in the README are
 * illustrations; the keys are the contract a merchant codes against, so a field added to or dropped
 * from a builder without touching the README fails here instead of in a merchant's parser.
 */
class EventCatalogTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC);
  private static final MerchantId MERCHANT = MerchantId.next();
  private static final ProviderEnvironment ENVIRONMENT = ProviderEnvironment.TEST;
  private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
  private static final String COPIA_E_COLA = "00020101021226...6304ABCD";

  private static final List<CatalogEntry> CATALOG =
      List.of(
          // One method per example, so each sub-object (pix, boleto, card) is shown with its keys
          // at
          // least once; the README says which are null for which method.
          new CatalogEntry("payment.pending", () -> PaymentEvents.paymentJson(pixPayment())),
          new CatalogEntry("payment.authorized", () -> PaymentEvents.paymentJson(cardPayment())),
          new CatalogEntry("payment.completed", () -> PaymentEvents.paymentJson(bolecodePayment())),
          new CatalogEntry("payment.failed", () -> PaymentEvents.paymentJson(cardPayment())),
          new CatalogEntry("payment.expired", () -> PaymentEvents.paymentJson(pixPayment())),
          new CatalogEntry("payment.canceled", () -> PaymentEvents.paymentJson(bolecodePayment())),
          new CatalogEntry("refund.requested", () -> PaymentEvents.refundJson(refund())),
          new CatalogEntry("refund.completed", () -> PaymentEvents.refundJson(refund())),
          new CatalogEntry("refund.failed", () -> PaymentEvents.refundJson(refund())),
          new CatalogEntry("refund.unknown", () -> PaymentEvents.refundJson(refund())),
          new CatalogEntry("customer.created", () -> CustomerService.json(customer())),
          new CatalogEntry("customer.updated", () -> CustomerService.json(customer())),
          new CatalogEntry("order.created", () -> OrderService.json(order())),
          new CatalogEntry("order.paid", () -> OrderService.json(order())),
          new CatalogEntry("order.canceled", () -> OrderService.json(order())),
          new CatalogEntry("order.expired", () -> OrderService.json(order())),
          new CatalogEntry("invoice.created", EventCatalogTest::invoiceCreated),
          new CatalogEntry("invoice.updated", EventCatalogTest::invoiceUpdated),
          new CatalogEntry("subscription.created", EventCatalogTest::subscriptionJson),
          new CatalogEntry("subscription.past_due", EventCatalogTest::subscriptionJson),
          new CatalogEntry("subscription.recovered", EventCatalogTest::subscriptionJson),
          new CatalogEntry("subscription.dunning_exhausted", EventCatalogTest::dunningExhausted),
          new CatalogEntry("subscription.canceled", EventCatalogTest::subscriptionJson),
          new CatalogEntry("subscription.ended", EventCatalogTest::subscriptionJson),
          new CatalogEntry("dispute.updated", EventCatalogTest::disputeJson));

  @Test
  void everyEventTypeIsDocumentedWithTheKeysItsBuilderEmits() {
    for (CatalogEntry entry : CATALOG) {
      assertThat(ReadmeBlocks.keysOf("#### " + entry.type()))
          .as(entry.type())
          .isEqualTo(ReadmeBlocks.keysOf(entry.example().get()));
    }
  }

  // Run by hand to render the README examples; the test above keeps them honest afterwards.
  @Test
  @Disabled("renders the README examples on demand")
  void render() {
    JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    for (CatalogEntry entry : CATALOG) {
      System.out.println("#### " + entry.type());
      System.out.println(json.writeValueAsString(entry.example().get()));
    }
  }

  private static Map<String, Object> disputeJson() {
    ReconciliationDivergence dispute =
        ReconciliationDivergence.merchant(
            pixPayment().id(), "COMPLETED", "DUPLICATE", "charged twice", CLOCK.instant());

    return DisputeEvents.json(dispute);
  }

  private static Payment pixPayment() {
    Payment payment =
        Payment.create(
            MERCHANT,
            ENVIRONMENT,
            "itau",
            Money.brl(15000),
            "order-1234",
            "Order 1234",
            null,
            3600,
            null,
            CLOCK);

    payment.markPending(
        new PixDetails(payment.id(), COPIA_E_COLA, null, null), CLOCK.instant().plusSeconds(3600));

    return payment;
  }

  private static Payment bolecodePayment() {
    Instant expiresAt = CLOCK.instant().plusSeconds(3 * 86_400);
    BoletoDetails requested =
        new BoletoDetails(
            "00000123", null, null, null, TODAY.plusDays(3), TODAY.plusDays(33), null);
    Payment payment =
        Payment.createBolecode(
            MERCHANT,
            ENVIRONMENT,
            "itau",
            Money.brl(4990),
            null,
            null,
            null,
            requested,
            expiresAt,
            null,
            CLOCK);

    BoletoDetails issued =
        new BoletoDetails(
            "00000123",
            "boleto-1",
            "34191.09008 00012.300000 00000.000000 1 00000000004990",
            "34191000000000049901090000012300000000000000",
            TODAY.plusDays(3),
            TODAY.plusDays(33),
            null);
    payment.markPendingBolecode(
        new PixDetails("txid-1", COPIA_E_COLA, null, null), issued, expiresAt, EventSource.API);

    return payment;
  }

  private static Payment cardPayment() {
    return Payment.createCard(
        MERCHANT,
        ENVIRONMENT,
        "cielo",
        Money.brl(15000),
        "order-1234",
        "Order 1234",
        null,
        CardDetails.requested(1, "Visa", "0004", null),
        null,
        CLOCK);
  }

  private static Refund refund() {
    return Refund.request(pixPayment().id(), MERCHANT, Money.brl(5000), CLOCK);
  }

  private static Customer customer() {
    return CustomerFactory.fromRequest(
        MERCHANT,
        ENVIRONMENT,
        "Maria Silva",
        "52998224725",
        "maria@example.com",
        new CustomerAddress.Raw("Rua Exemplo, 100", "Centro", "Sao Paulo", "SP", "01001000"),
        CLOCK);
  }

  private static Order order() {
    return OrderFactory.standalone(
        MERCHANT,
        ENVIRONMENT,
        Money.brl(15000),
        "order-1234",
        "Order 1234",
        customer().id(),
        null,
        CLOCK.instant().plusSeconds(86_400),
        null,
        CLOCK);
  }

  private static Subscription subscription() {
    Plan plan =
        PlanFactory.fromRequest(
            MERCHANT, "Monthly", Money.brl(4990), PlanInterval.MONTH, 1, 0, CLOCK);
    Subscription subscription =
        SubscriptionFactory.fromRequest(
            MERCHANT, ENVIRONMENT, customer(), plan, PaymentMethod.BOLECODE, null, TODAY, CLOCK);

    // Opened, so current_period carries its start and end instead of null.
    subscription.openPeriod(
        new BillingPeriod(TODAY, TODAY.plusMonths(1).minusDays(1)),
        CLOCK.instant().plusSeconds(31 * 86_400),
        CLOCK.instant());

    return subscription;
  }

  private static Map<String, Object> subscriptionJson() {
    return SubscriptionService.json(subscription());
  }

  private static Order invoice(Subscription subscription) {
    return OrderFactory.invoice(
        MERCHANT,
        ENVIRONMENT,
        subscription.customerId(),
        Money.brl(4990),
        subscription.id(),
        1,
        TODAY,
        TODAY.plusMonths(1).minusDays(1),
        CLOCK.instant().plusSeconds(3 * 86_400),
        null,
        CLOCK);
  }

  private static Map<String, Object> invoiceCreated() {
    Subscription subscription = subscription();
    IssuedInvoice issued = new IssuedInvoice(bolecodePayment(), false, null);

    return InvoicePayloads.created(subscription, invoice(subscription), issued, null);
  }

  private static Map<String, Object> invoiceUpdated() {
    Subscription subscription = subscription();

    return InvoicePayloads.updated(invoice(subscription), subscription, 2, bolecodePayment());
  }

  private static Map<String, Object> dunningExhausted() {
    Subscription subscription = subscription();

    Map<String, Object> body = new LinkedHashMap<>(SubscriptionService.json(subscription));
    body.put("invoice_id", invoice(subscription).id());

    return body;
  }

  private record CatalogEntry(String type, Supplier<Map<String, Object>> example) {}
}
