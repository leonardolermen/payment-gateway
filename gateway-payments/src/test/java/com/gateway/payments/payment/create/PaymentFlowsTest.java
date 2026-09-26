package com.gateway.payments.payment.create;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.payments.payment.Payment;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The registry is complete at construction or the context does not come up. A method with no flow
 * would otherwise be a 500 the first time a merchant asked for it, which is the worst place to
 * learn about a wiring mistake.
 */
class PaymentFlowsTest {

  private static PaymentFlow flowFor(PaymentMethod method) {
    return new PaymentFlow() {
      @Override
      public PaymentMethod method() {
        return method;
      }

      @Override
      public Payment create(CreatePaymentCommand command) {
        throw new UnsupportedOperationException("not needed for the registry's own tests");
      }
    };
  }

  @Test
  void aMethodWithoutAFlowFailsAtConstruction() {
    assertThatThrownBy(() -> new PaymentFlows(List.of(flowFor(PaymentMethod.PIX))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("no payment flow for BOLECODE");
  }

  @Test
  void twoFlowsForTheSameMethodFailAtConstruction() {
    List<PaymentFlow> duplicated =
        List.of(
            flowFor(PaymentMethod.PIX),
            flowFor(PaymentMethod.PIX),
            flowFor(PaymentMethod.BOLECODE));

    assertThatThrownBy(() -> new PaymentFlows(duplicated))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("two payment flows for PIX");
  }

  @Test
  void noFlowAtAllFailsAtConstruction() {
    assertThatThrownBy(() -> new PaymentFlows(List.of())).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void resolvesTheFlowOfTheMethodAsked() {
    PaymentFlow pix = flowFor(PaymentMethod.PIX);
    PaymentFlow bolecode = flowFor(PaymentMethod.BOLECODE);

    PaymentFlows flows = new PaymentFlows(List.of(pix, bolecode));

    assertThat(flows.forMethod(PaymentMethod.PIX)).isSameAs(pix);
    assertThat(flows.forMethod(PaymentMethod.BOLECODE)).isSameAs(bolecode);
  }
}
