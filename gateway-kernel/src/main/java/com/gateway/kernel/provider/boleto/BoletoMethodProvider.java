package com.gateway.kernel.provider.boleto;

import com.gateway.kernel.provider.MethodProvider;
import com.gateway.kernel.provider.ProviderCredentials;

/**
 * The boleto side: the spine ({@code issue}, {@code find}, {@code cancel} — the bank's baixa, which
 * answers {@code CONFLICT} when it already shows the boleto paid or settled) plus the Pix txid the
 * bank derives for the same charge. Implemented per bank in {@code gateway-providers}
 * (ItauBoletoProvider); consumed by payments.
 */
public interface BoletoMethodProvider
    extends MethodProvider<BoletoIssueRequest, IssuedBoleto, BoletoStatus> {
  /**
   * The Pix txid the bank derives for this boleto, so a charge adopted from the query can be
   * matched to Pix webhooks.
   */
  String pixTxidFor(ProviderCredentials credentials, String nossoNumero);
}
