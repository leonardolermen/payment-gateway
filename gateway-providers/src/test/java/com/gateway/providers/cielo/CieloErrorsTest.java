package com.gateway.providers.cielo;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.ProviderException.Code;
import org.junit.jupiter.api.Test;

class CieloErrorsTest {

  /** reference/api-errors-code-message, the page's own example body. */
  @Test
  void aFourHundredListIsInvalidWithItsCodes() {
    ProviderException e =
        CieloErrors.from(400, "[{\"Code\":322,\"Message\":\"Zero Dollar Auth is not enabled\"}]");

    assertThat(e.code()).isEqualTo(Code.INVALID);
    assertThat(e.httpStatus()).isEqualTo(400);
    assertThat(e.providerType()).isEqualTo("322");
    assertThat(e.getMessage()).isEqualTo("322 Zero Dollar Auth is not enabled");
  }

  @Test
  void severalErrorsKeepEveryCode() {
    ProviderException e =
        CieloErrors.from(
            400,
            "[{\"Code\":126,\"Message\":\"Credit Card Expiration Date is invalid\"},"
                + "{\"Code\":182,\"Message\":\"Brand is required\"}]");

    assertThat(e.providerType()).isEqualTo("126,182");
  }

  /** The creation page's OpenAPI documents 400/401 bodies as bare strings (plan D9). */
  @Test
  void aBodyThatIsNotJsonFallsBackToTheStatus() {
    assertThat(CieloErrors.from(400, "Bad request").code()).isEqualTo(Code.INVALID);
    assertThat(CieloErrors.from(401, "Unauthorized").code()).isEqualTo(Code.UNAUTHENTICATED);
    assertThat(CieloErrors.from(404, "").code()).isEqualTo(Code.NOT_FOUND);
    assertThat(CieloErrors.from(500, null).code()).isEqualTo(Code.UNAVAILABLE);
    assertThat(CieloErrors.from(503, "<html>").code()).isEqualTo(Code.UNAVAILABLE);
    assertThat(CieloErrors.from(504, "").code()).isEqualTo(Code.TIMEOUT);
    assertThat(CieloErrors.from(302, "").code()).isEqualTo(Code.UNKNOWN);
  }

  /** api-codes: 307 "Transaction not found" is the only documented not-found answer (plan D10). */
  @Test
  void transactionNotFoundIsA404OrCode307() {
    assertThat(CieloErrors.isTransactionNotFound(CieloErrors.from(404, ""))).isTrue();
    assertThat(
            CieloErrors.isTransactionNotFound(
                CieloErrors.from(400, "[{\"Code\":307,\"Message\":\"Transaction not found\"}]")))
        .isTrue();
    assertThat(
            CieloErrors.isTransactionNotFound(
                CieloErrors.from(400, "[{\"Code\":3070,\"Message\":\"x\"}]")))
        .isFalse();
  }

  @Test
  void aCardNumberInARawBodyIsMaskedBeforeItBecomesTheMessage() {
    ProviderException e = CieloErrors.from(502, "{\"CardNumber\":\"4024007153763171\"}");

    assertThat(e.getMessage()).doesNotContain("4024007153763171").contains("402400******3171");
  }
}
