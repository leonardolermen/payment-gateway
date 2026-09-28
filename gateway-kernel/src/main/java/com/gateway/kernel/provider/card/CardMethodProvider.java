package com.gateway.kernel.provider.card;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.MethodProvider;
import com.gateway.kernel.provider.ProviderCredentials;
import java.util.Optional;

/**
 * The card side: the spine ({@code issue} = authorize, {@code find} by the acquirer's payment id,
 * {@code cancel} = void of an authorization) plus what only a card has. Implemented per acquirer in
 * {@code gateway-providers} (CieloCardProvider); consumed by payments.
 *
 * <p>{@code bankReference} is the acquirer's PaymentId, which capture, void and query all take. The
 * MerchantOrderId (ours, the payment id) is only for {@link #findByOrder}, the recovery after a
 * lost answer (spec §3). {@code find}'s type is the whole authorization, not just a status: a
 * recovery must adopt tid, codes and amounts from it (plan C3).
 */
public interface CardMethodProvider
    extends MethodProvider<CardIssueRequest, CardAuthorization, CardAuthorization> {

  /** Empty amount captures everything authorized. The acquirer allows one capture per sale. */
  CardAuthorization capture(
      ProviderCredentials credentials, String bankReference, Optional<Money> amount);

  /** A void with an amount after capture: synchronous at the acquirer. */
  CardRefundResult refund(
      ProviderCredentials credentials, String bankReference, Optional<Money> amount);

  /** The most recent sale sent with this MerchantOrderId, or empty when the acquirer has none. */
  Optional<CardAuthorization> findByOrder(ProviderCredentials credentials, String merchantOrderId);

  /** Stores a card without charging it (spec §5). Not called by payments in this phase (C13). */
  StoredCard tokenize(ProviderCredentials credentials, CardData card, String customerName);

  /** Parsing needs no credential, like the Pix webhook: a rotated key must not lose the inbox. */
  CardNotification parseWebhook(byte[] body);
}
