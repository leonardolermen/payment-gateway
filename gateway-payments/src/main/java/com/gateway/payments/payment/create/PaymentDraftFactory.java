package com.gateway.payments.payment.create;

import com.gateway.kernel.party.Payer;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.boleto.BoletoDates;
import com.gateway.payments.payment.boleto.BoletoDetails;
import com.gateway.payments.payment.boleto.persistence.BoletoNumberRepository;
import com.gateway.payments.payment.persistence.PaymentRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * The CREATED row, written before the bank is ever called: a timeout can then never lose a charge
 * the bank accepted, because there is always a row to reconcile against.
 *
 * <p>For a bolecode the nosso número is reserved in the same transaction as the row (spec
 * 2026-09-25 §7). Two transactions would let a crash between them burn a number, and a number the
 * bank already knows cannot be reused for 45 days.
 */
public class PaymentDraftFactory {
  private final PaymentRepository payments;
  private final BoletoNumberRepository boletoNumbers;
  private final UnitOfWork unitOfWork;
  private final Clock clock;

  public PaymentDraftFactory(
      PaymentRepository payments,
      BoletoNumberRepository boletoNumbers,
      UnitOfWork unitOfWork,
      Clock clock) {
    this.payments = payments;
    this.boletoNumbers = boletoNumbers;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  public Payment pix(CreatePixPayment command, String providerId, int expiresInSeconds) {
    return unitOfWork.inTransaction(
        () -> {
          Payment draft =
              Payment.create(
                  command.merchantId(),
                  command.environment(),
                  providerId,
                  command.amount(),
                  command.reference(),
                  command.description(),
                  CustomerDocumentHash.of(command.customerDocument()),
                  expiresInSeconds,
                  clock);

          return payments.save(draft, List.of(draft.createdEvent()));
        });
  }

  public Payment bolecode(
      CreateBolecodePayment command,
      String providerId,
      Payer payer,
      LocalDate dueDate,
      LocalDate paymentLimitDate) {
    return unitOfWork.inTransaction(
        () -> {
          String nossoNumero = boletoNumbers.next(command.merchantId());
          BoletoDetails details =
              new BoletoDetails(nossoNumero, null, null, null, dueDate, paymentLimitDate, null);

          Payment draft =
              Payment.createBolecode(
                  command.merchantId(),
                  command.environment(),
                  providerId,
                  command.amount(),
                  command.reference(),
                  command.description(),
                  CustomerDocumentHash.of(payer.document().digits()),
                  details,
                  BoletoDates.endOfDay(paymentLimitDate),
                  clock);

          return payments.save(draft, List.of(draft.createdEvent()));
        });
  }
}
