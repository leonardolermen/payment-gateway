package com.gateway.app.api.payment.dto;

import com.gateway.payments.payment.create.PayerData;

/**
 * Who is being charged, as the merchant sent it. Both methods accept one, and each uses what it
 * needs: a Pix charge takes only the document, to restrict the QR to whoever was charged; a
 * registered boleto needs the whole thing, and the domain refuses it naming the missing field.
 */
public record Customer(String name, String document, Address address) {

  /**
   * Copied, not validated: {@code PayerFactory} owns the 422, and it names the field the way it
   * arrived.
   */
  PayerData toPayerData() {
    return new PayerData(
        name,
        document,
        address == null
            ? null
            : new PayerData.AddressData(
                address.street(),
                address.district(),
                address.city(),
                address.state(),
                address.zip()));
  }
}
