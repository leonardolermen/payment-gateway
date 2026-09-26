package com.gateway.payments.payment.create;

import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.payments.payment.Payment;

/**
 * Creating a payment of one method, end to end. Each flow owns its whole create and shares the
 * common trunk by composition ({@link PaymentDraftFactory}, {@link ProviderFailures}, {@link
 * PendingAdoption}) rather than by inheriting a template.
 *
 * <p>The reason is the part that differs: on a bank call that may have landed, Pix asks and decides
 * on the spot, while a bolecode is left CREATED for the sweeper. As a flag on a shared method that
 * difference would be invisible; as each flow's own code it is the first thing you read.
 */
public interface PaymentFlow {

  PaymentMethod method();

  Payment create(CreatePaymentCommand command);
}
