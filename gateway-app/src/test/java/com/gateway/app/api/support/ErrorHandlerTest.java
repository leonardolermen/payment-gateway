package com.gateway.app.api.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.errors.DomainException;
import com.gateway.payments.payment.card.CardDeclinedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

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
}
