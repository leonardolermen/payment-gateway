package com.gateway.app.api.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.webhookdelivery.domain.DeliveryStatus;
import com.gateway.billing.customer.CustomerExistsException;
import com.gateway.billing.order.OrderHasActivePaymentException;
import com.gateway.kernel.errors.DomainException;
import com.gateway.payments.payment.card.CardDeclinedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * The status a merchant's code branches on. ALREADY_PAID was only ever asserted through the
 * payments service (the exception), never at the edge: a refactor of the handler's one-line ternary
 * would have turned the cancel that lost to the payer back into a 422 with every test green.
 */
class ErrorHandlerTest {
  private final ErrorHandler handler = new ErrorHandler();

  @Test
  void alreadyPaidIsAConflict() {
    ProblemDetail p =
        handler.domainError(
            new DomainException(
                "ALREADY_PAID", "the bank shows this boleto paid; the payment is now COMPLETED"));
    assertThat(p.getStatus()).isEqualTo(409);
    assertThat(p.getType()).hasToString("urn:gateway:ALREADY_PAID");
  }

  @Test
  void otherDomainErrorsStayUnprocessable() {
    assertThat(handler.domainError(new DomainException("INVALID_STATE", "not pending")).getStatus())
        .isEqualTo(422);
  }

  @Test
  void panelAuthCodesHaveTheirStatuses() {
    assertThat(handler.domainError(new DomainException("AUTH_BUSY", "x")).getStatus())
        .isEqualTo(503);
    assertThat(handler.domainError(new DomainException("EMAIL_NOT_VERIFIED", "x")).getStatus())
        .isEqualTo(403);
    assertThat(handler.domainError(new DomainException("OWN_ACCOUNT", "x")).getStatus())
        .isEqualTo(400);
    assertThat(handler.domainError(new DomainException("ORIGIN_NOT_ALLOWED", "x")).getStatus())
        .isEqualTo(403);
  }

  @Test
  void cardCodesHaveTheirStatuses() {
    ErrorHandler handler = new ErrorHandler();

    assertThat(handler.domainError(new DomainException("CAPTURE_NOT_ALLOWED", "x")).getStatus())
        .isEqualTo(409);
    assertThat(handler.domainError(new DomainException("ALREADY_CAPTURED", "x")).getStatus())
        .isEqualTo(409);
    assertThat(handler.domainError(new DomainException("ALREADY_PAID", "x")).getStatus())
        .isEqualTo(409);
    assertThat(handler.domainError(new DomainException("CAPTURE_AMOUNT_INVALID", "x")).getStatus())
        .isEqualTo(422);
    assertThat(handler.domainError(new DomainException("CARD_NOT_FOUND", "x")).getStatus())
        .isEqualTo(422);
    assertThat(handler.domainError(new DomainException("CARD_INVALID", "x")).getStatus())
        .isEqualTo(422);
  }

  /** Spec §9: a decline is 402 with our decline_code; the issuer's text is never there. */
  @Test
  void aDeclineIs402WithTheDeclineCodeAndThePayment() {
    ProblemDetail problem =
        new ErrorHandler().cardDeclined(new CardDeclinedException("01K0PAY", "INSUFFICIENT_FUNDS"));

    assertThat(problem.getStatus()).isEqualTo(402);
    assertThat(problem.getType()).hasToString("urn:gateway:CARD_DECLINED");
    assertThat(problem.getDetail()).isEqualTo("The card was declined.");
    assertThat(problem.getProperties())
        .containsEntry("decline_code", "INSUFFICIENT_FUNDS")
        .containsEntry("payment_id", "01K0PAY");
  }

  @Test
  void billingConflictsAre409() {
    for (String code :
        new String[] {
          "CUSTOMER_EXISTS",
          "CUSTOMER_HAS_ACTIVE_SUBSCRIPTION",
          "ORDER_CLOSED",
          "ORDER_HAS_ACTIVE_PAYMENT",
          "SUBSCRIPTION_NOT_ACTIVE",
          "CONFLICT"
        }) {
      assertThat(handler.domainError(new DomainException(code, "x")).getStatus())
          .as(code)
          .isEqualTo(409);
    }
  }

  @Test
  void billingValidationStays422() {
    for (String code : new String[] {"PLAN_INACTIVE", "PLAN_IMMUTABLE", "CARD_REQUIRED"}) {
      assertThat(handler.domainError(new DomainException(code, "x")).getStatus())
          .as(code)
          .isEqualTo(422);
    }
  }

  @Test
  void anExistingCustomerNamesItsId() {
    ProblemDetail problem = handler.customerExists(new CustomerExistsException("cus_1"));

    assertThat(problem.getStatus()).isEqualTo(409);
    assertThat(problem.getProperties()).containsEntry("customer_id", "cus_1");
  }

  @Test
  void anActiveAttemptNamesItsPayment() {
    ProblemDetail problem =
        handler.orderHasActivePayment(new OrderHasActivePaymentException("ord_1", "pay_1"));

    assertThat(problem.getStatus()).isEqualTo(409);
    assertThat(problem.getType()).hasToString("urn:gateway:ORDER_HAS_ACTIVE_PAYMENT");
    assertThat(problem.getProperties()).containsEntry("payment_id", "pay_1");
  }

  @Test
  void aDeliveryThatCannotBeRedeliveredIsAConflict() {
    ProblemDetail problem =
        handler.domainError(new DomainException("DELIVERY_NOT_REDELIVERABLE", "x"));

    assertThat(problem.getStatus()).isEqualTo(409);
  }

  /**
   * {@code GET /v1/webhooks/deliveries?status=NOPE}: the detail names the query parameter, never
   * the Java enum Spring failed to convert to.
   */
  @Test
  void anUnconvertibleParameterIsAnInvalidRequest() {
    ProblemDetail problem =
        handler.parameterTypeMismatch(
            new MethodArgumentTypeMismatchException(
                "NOPE", DeliveryStatus.class, "status", null, new IllegalArgumentException()));

    assertThat(problem.getStatus()).isEqualTo(400);
    assertThat(problem.getType()).hasToString("urn:gateway:INVALID_REQUEST");
    assertThat(problem.getDetail()).isEqualTo("status is not valid");
  }
}
