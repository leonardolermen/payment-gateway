package com.gateway.kernel.provider.boleto;

import com.gateway.kernel.provider.ProviderCredentials;
import java.util.Optional;

/**
 * Implemented per bank in {@code gateway-providers} (ItauBoletoProvider); consumed by payments.
 * Every method takes the credential because the bank's account data (beneficiary id, wallet) lives
 * inside it, and only the provider knows the credential's shape.
 */
public interface BoletoProvider {
  String id();

  /** Fails fast with {@code CREDENTIALS_INCOMPLETE} (providerType = the field) before any HTTP and before payments writes a row. */
  void requireIssueCredentials(ProviderCredentials c);

  IssuedBoleto issue(ProviderCredentials c, BoletoIssueRequest r);

  /** Empty when the bank does not know the number (404 or an empty list). */
  Optional<BoletoStatus> find(ProviderCredentials c, String nossoNumero);

  /** The bank's baixa. {@code CONFLICT} when it already shows the boleto paid or settled. */
  void cancel(ProviderCredentials c, String nossoNumero);

  /** The Pix txid the bank derives for this boleto, so a charge adopted from the query can be matched to Pix webhooks. */
  String pixTxidFor(ProviderCredentials c, String nossoNumero);
}
