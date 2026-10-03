package com.gateway.billing.order;

import com.gateway.billing.customer.Customer;
import com.gateway.billing.customer.CustomerAddress;
import com.gateway.billing.customer.CustomerService;
import com.gateway.billing.order.persistence.OrderRepository;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.party.Document;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentQueries;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.payment.create.CardCustomerData;
import com.gateway.payments.payment.create.CreateBolecodePayment;
import com.gateway.payments.payment.create.CreateCardPayment;
import com.gateway.payments.payment.create.CreatePaymentCommand;
import com.gateway.payments.payment.create.CreatePixPayment;
import com.gateway.payments.payment.create.PayerData;
import com.gateway.payments.payment.create.PaymentFlows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The one door from an order to a payment attempt. Builds the method's command from the order
 * (amount, currency, payer) and what the caller chose, and translates the database's refusal of a
 * second active attempt into the 409 the API promises (spec §4.3).
 *
 * <p>Named a Service, not {@code OrderPayments}: it catches Spring's {@link
 * DataIntegrityViolationException}, and the architecture test keeps Spring out of plain model
 * types. The partial unique index is the guard, not a read-then-write here, because two racing
 * requests both read "no active attempt" and only the index serializes them.
 */
public class OrderAttemptService {
  private static final Logger log = LoggerFactory.getLogger(OrderAttemptService.class);

  private final PaymentFlows flows;
  private final PaymentQueries payments;
  private final CustomerService customers;
  private final OrderRepository orders;

  public OrderAttemptService(
      PaymentFlows flows,
      PaymentQueries payments,
      CustomerService customers,
      OrderRepository orders) {
    this.flows = flows;
    this.payments = payments;
    this.customers = customers;
    this.orders = orders;
  }

  /**
   * {@code requested} is a key, not the truth: a caller holding an Order read before a cancel would
   * otherwise open a payable charge on a CANCELED order. The row is re-read here and that row's
   * status decides. {@code by} is kept for the callers' audit trail; the flows record API today.
   */
  public Payment attempt(Order requested, AttemptRequest request, EventSource by) {
    Order order =
        orders
            .find(requested.merchantId(), requested.id())
            .orElseThrow(() -> new NotFoundException("order", requested.id()));
    if (!order.isOpen()) {
      throw new DomainException("ORDER_CLOSED", "order " + order.id() + " is " + order.status());
    }
    refuseIfAlreadyPaid(order);

    Payer payer = payerOf(order);
    CreatePaymentCommand command = commandFor(order, payer, request);

    Payment payment;
    try {
      payment = flows.forMethod(command.method()).create(command);
    } catch (DataIntegrityViolationException refused) {
      // Only the partial unique index is a 409: with no active attempt to name, the violation was
      // something else, and dressing it as ORDER_HAS_ACTIVE_PAYMENT would hide a real fault.
      Payment active = payments.activeAttempt(order.id()).orElseThrow(() -> refused);
      throw new OrderHasActivePaymentException(order.id(), active.id());
    }

    if (order.customerId() != null && savesANewCard(request) && payment.card().cardId() != null) {
      adoptQuietly(order, payment);
    }

    return payment;
  }

  /**
   * The order turns PAID only when the outbox relay delivers the settlement, and the partial unique
   * index covers active statuses only, so a COMPLETED attempt not yet relayed would let an API
   * retry, the cycle or a dunning retry charge the payer a second time. Every one of them comes
   * through here, so this one read guards all three.
   */
  private void refuseIfAlreadyPaid(Order order) {
    payments.listByOrder(order.merchantId(), order.id()).stream()
        .filter(payment -> payment.status() == PaymentStatus.COMPLETED)
        .findFirst()
        .ifPresent(
            paid -> {
              throw new DomainException(
                  "ALREADY_PAID", "order " + order.id() + " was paid by " + paid.id());
            });
  }

  private static boolean savesANewCard(AttemptRequest request) {
    return request instanceof AttemptRequest.CardAttempt card
        && card.card() instanceof CardChoice.NewCard newCard
        && newCard.save();
  }

  /**
   * Spec §5: a card saved on a customer's order is born his; the card flow saved it by document
   * hash only. This runs after the charge committed, so it must never fail the request: the payment
   * response is the contract, and an error here would be a 404/500 for money already taken, which
   * IdempotencyFilter would then replay on every retry. A card left unlinked is repairable
   * (adoption by document hash links it on the next CustomerService adoption); a lost charge
   * response is not.
   */
  private void adoptQuietly(Order order, Payment payment) {
    try {
      customers.adoptSavedCards(customers.get(order.merchantId(), order.customerId()));
    } catch (RuntimeException e) {
      // The class only: a message may carry SQL or customer data.
      log.warn(
          "card {} of payment {} was saved but not linked to customer {} ({});"
              + " CustomerService.create/adopt repairs it later",
          payment.card().cardId(),
          payment.id(),
          order.customerId(),
          e.getClass().getSimpleName());
    }
  }

  /** The dispatch point: the one switch over the attempt kinds. */
  private CreatePaymentCommand commandFor(Order order, Payer payer, AttemptRequest request) {
    return switch (request) {
      case AttemptRequest.PixAttempt pix ->
          new CreatePixPayment(
              order.merchantId(),
              order.environment(),
              order.amount(),
              order.reference(),
              order.description(),
              payer.documentDigits(),
              pix.expiresInSeconds(),
              order.id());
      case AttemptRequest.BolecodeAttempt boleto ->
          new CreateBolecodePayment(
              order.merchantId(),
              order.environment(),
              order.amount(),
              order.reference(),
              order.description(),
              payer.asPayerData(),
              boleto.dueDate(),
              boleto.paymentLimitDays(),
              order.id());
      case AttemptRequest.CardAttempt card ->
          new CreateCardPayment(
              order.merchantId(),
              order.environment(),
              order.amount(),
              order.reference(),
              order.description(),
              card.card(),
              card.installments(),
              card.capture(),
              card.softDescriptor(),
              payer.asCardCustomer(),
              order.id());
      case AttemptRequest.RecurringCardAttempt recurring ->
          new CreateCardPayment(
              order.merchantId(),
              order.environment(),
              order.amount(),
              order.reference(),
              order.description(),
              new CardChoice.RecurringCard(recurring.cardId()),
              recurring.installments(),
              true,
              null,
              payer.asCardCustomer(),
              order.id());
    };
  }

  private Payer payerOf(Order order) {
    if (order.customerId() != null) {
      Customer customer = customers.get(order.merchantId(), order.customerId());
      return new Payer(
          customer.name().value(), customer.document(), customer.email(), customer.address());
    }

    OrderPayer inline = order.payer();
    return new Payer(inline.name().value(), inline.document(), inline.email(), inline.address());
  }

  /** The payer in the shapes the three commands take. */
  record Payer(String name, Document document, String email, CustomerAddress address) {
    PayerData asPayerData() {
      if (address == null) {
        throw new DomainException(
            "CUSTOMER_ADDRESS_REQUIRED", "customer.address is required for a boleto");
      }

      return new PayerData(
          name,
          documentDigits(),
          new PayerData.AddressData(
              address.street(),
              address.district(),
              address.city(),
              address.state().value(),
              address.zip().digits()));
    }

    CardCustomerData asCardCustomer() {
      return new CardCustomerData(name, documentDigits(), email);
    }

    String documentDigits() {
      return document == null ? null : document.digits();
    }
  }
}
