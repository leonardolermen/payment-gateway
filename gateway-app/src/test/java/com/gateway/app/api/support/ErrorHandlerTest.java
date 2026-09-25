package com.gateway.app.api.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.errors.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

/**
 * The status a merchant's code branches on. ALREADY_PAID was only ever asserted through the payments
 * service (the exception), never at the edge: a refactor of the handler's one-line ternary would
 * have turned the cancel that lost to the payer back into a 422 with every test green.
 */
class ErrorHandlerTest {
  private final ErrorHandler handler = new ErrorHandler();

  @Test
  void alreadyPaidIsAConflict() {
    ProblemDetail p = handler.domainError(new DomainException("ALREADY_PAID", "the bank shows this boleto paid; the payment is now COMPLETED"));
    assertThat(p.getStatus()).isEqualTo(409);
    assertThat(p.getType()).hasToString("urn:gateway:ALREADY_PAID");
  }

  @Test
  void otherDomainErrorsStayUnprocessable() {
    assertThat(handler.domainError(new DomainException("INVALID_STATE", "not pending")).getStatus()).isEqualTo(422);
  }
}
