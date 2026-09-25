package com.gateway.payments.payment.boleto.persistence;

import com.gateway.kernel.ids.MerchantId;

public interface BoletoNumberRepository {
  /**
   * The next nosso número for the merchant, 8 digits zero-padded, sequential from 00000001. Runs
   * in the caller's transaction (MANDATORY): the number is reserved together with the CREATED row,
   * so a rolled-back create never leaves a hole the bank would later reject as a repeat.
   */
  String next(MerchantId merchantId);
}
