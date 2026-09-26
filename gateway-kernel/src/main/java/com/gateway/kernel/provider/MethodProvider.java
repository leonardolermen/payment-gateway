package com.gateway.kernel.provider;

import com.gateway.kernel.payment.PaymentMethod;
import java.util.Optional;

/**
 * What every bank product has in common: register the charge, ask the bank about it by the bank's
 * own reference, take it down. Implemented per bank and per method in {@code gateway-providers};
 * consumed by payments through {@code ProviderGateway}.
 *
 * <p>{@code bankReference} is the bank's handle on the charge — the txid for Pix (which is ours: it
 * is the payment id), the nosso número for a boleto. Deliberately not called {@code reference}:
 * {@code Payment.reference} is the merchant's order id, and one word for both would be read wrong
 * the first time.
 *
 * <p>Every method takes the credential because the bank's account data (beneficiary id, wallet, Pix
 * key) lives inside it, and only the provider knows that credential's shape.
 */
public interface MethodProvider<ISSUE, ISSUED, STATUS> {
  String id();

  PaymentMethod method();

  /**
   * Fails with {@code CREDENTIALS_INCOMPLETE} ({@code providerType} = the missing field) before any
   * HTTP and before payments writes a row: a merchant with an incomplete credential has nothing to
   * clean up.
   */
  void requireIssueCredentials(ProviderCredentials credentials);

  ISSUED issue(ProviderCredentials credentials, ISSUE request);

  /** Empty when the bank does not know the reference (404, or an empty list). */
  Optional<STATUS> find(ProviderCredentials credentials, String bankReference);

  void cancel(ProviderCredentials credentials, String bankReference);
}
