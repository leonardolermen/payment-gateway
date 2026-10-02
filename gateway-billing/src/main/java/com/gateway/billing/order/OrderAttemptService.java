package com.gateway.billing.order;

import com.gateway.billing.customer.Customer;
import com.gateway.billing.customer.CustomerAddress;
import com.gateway.billing.customer.CustomerService;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.party.Document;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentQueries;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.payment.create.CardCustomerData;
import com.gateway.payments.payment.create.CreateBolecodePayment;
import com.gateway.payments.payment.create.CreateCardPayment;
import com.gateway.payments.payment.create.CreatePaymentCommand;
import com.gateway.payments.payment.create.CreatePixPayment;
import com.gateway.payments.payment.create.PayerData;
import com.gateway.payments.payment.create.PaymentFlows;
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
  private final PaymentFlows flows;
  private final PaymentQueries payments;
  private final CustomerService customers;

  public OrderAttemptService(
      PaymentFlows flows, PaymentQueries payments, CustomerService customers) {
    this.flows = flows;
    this.payments = payments;
    this.customers = customers;
  }

  /** {@code by} is kept for the callers' audit trail; the flows record API themselves today. */
  public Payment attempt(Order order, AttemptRequest request, EventSource by) {
    if (!order.isOpen()) {
      throw new DomainException("ORDER_CLOSED", "order " + order.id() + " is " + order.status());
    }

    Payer payer = payerOf(order);
    CreatePaymentCommand command = commandFor(order, payer, request);

    try {
      return flows.forMethod(command.method()).create(command);
    } catch (DataIntegrityViolationException refused) {
      // The partial unique index spoke: name the attempt that holds the slot.
      String active = payments.activeAttempt(order.id()).map(Payment::id).orElse("unknown");
      throw new OrderHasActivePaymentException(order.id(), active);
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
