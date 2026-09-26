package com.gateway.payments.payment.create;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.ProviderException.Code;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Which codes may mean "the charge landed" is the flow's call, and this class only applies the set
 * it is given. That is the difference a boolean parameter would have hidden.
 */
class ProviderFailuresTest {
  private static final Set<Code> PIX = Set.of(Code.TIMEOUT, Code.UNAVAILABLE);
  private static final Set<Code> BOLETO = Set.of(Code.TIMEOUT, Code.UNAVAILABLE, Code.CONFLICT);

  private static ProviderException failure(Code code) {
    return new ProviderException(code, code + " from the bank", null);
  }

  @Test
  void aTimeoutMayHaveLandedForBothMethods() {
    assertThat(ProviderFailures.classify(failure(Code.TIMEOUT), PIX))
        .isEqualTo(ProviderFailures.Outcome.MAY_HAVE_LANDED);
    assertThat(ProviderFailures.classify(failure(Code.TIMEOUT), BOLETO))
        .isEqualTo(ProviderFailures.Outcome.MAY_HAVE_LANDED);
  }

  @Test
  void unavailableMayHaveLandedForBothMethods() {
    assertThat(ProviderFailures.classify(failure(Code.UNAVAILABLE), PIX))
        .isEqualTo(ProviderFailures.Outcome.MAY_HAVE_LANDED);
    assertThat(ProviderFailures.classify(failure(Code.UNAVAILABLE), BOLETO))
        .isEqualTo(ProviderFailures.Outcome.MAY_HAVE_LANDED);
  }

  /** CONFLICT is the bank saying the nosso numero already exists, which only a boleto can mean. */
  @Test
  void conflictMayHaveLandedOnlyWhenTheFlowSaysSo() {
    assertThat(ProviderFailures.classify(failure(Code.CONFLICT), BOLETO))
        .isEqualTo(ProviderFailures.Outcome.MAY_HAVE_LANDED);
    assertThat(ProviderFailures.classify(failure(Code.CONFLICT), PIX))
        .isEqualTo(ProviderFailures.Outcome.UNAVAILABLE);
  }

  @Test
  void invalidAndDeclinedAreDeclined() {
    for (Code code : new Code[] {Code.INVALID, Code.DECLINED}) {
      assertThat(ProviderFailures.classify(failure(code), PIX))
          .as("%s", code)
          .isEqualTo(ProviderFailures.Outcome.DECLINED);
      assertThat(ProviderFailures.classify(failure(code), BOLETO))
          .as("%s", code)
          .isEqualTo(ProviderFailures.Outcome.DECLINED);
    }
  }

  @Test
  void everythingElseIsUnavailable() {
    for (Code code :
        new Code[] {
          Code.NOT_FOUND, Code.UNAUTHENTICATED, Code.CREDENTIALS_INCOMPLETE, Code.UNKNOWN
        }) {
      assertThat(ProviderFailures.classify(failure(code), PIX))
          .as("%s", code)
          .isEqualTo(ProviderFailures.Outcome.UNAVAILABLE);
    }
  }

  /**
   * A timeout is the one failure that reaches the merchant as PROVIDER_TIMEOUT rather than
   * UNAVAILABLE.
   */
  @Test
  void theMerchantFacingCodeSeparatesTimeoutFromTheRest() {
    assertThat(ProviderFailures.timeoutCodeOf(failure(Code.TIMEOUT))).isEqualTo("PROVIDER_TIMEOUT");
    assertThat(ProviderFailures.timeoutCodeOf(failure(Code.UNAVAILABLE)))
        .isEqualTo("PROVIDER_UNAVAILABLE");
    assertThat(ProviderFailures.timeoutCodeOf(failure(Code.CONFLICT)))
        .isEqualTo("PROVIDER_UNAVAILABLE");
  }
}
