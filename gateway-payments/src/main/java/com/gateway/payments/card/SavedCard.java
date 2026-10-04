package com.gateway.payments.card;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.card.CardBrand;
import java.time.Instant;
import java.time.YearMonth;

/**
 * A card a merchant saved: our {@code card_id} (spec §11 — never the acquirer's token) and the face
 * the merchant's checkout shows. The sealed token is not a field: only {@link SavedCards} opens it,
 * at the moment of a charge.
 */
public record SavedCard(
    String id,
    MerchantId merchantId,
    String provider,
    ProviderEnvironment environment,
    CardBrand brand,
    String last4,
    YearMonth expiry,
    String holder,
    String customerDocumentHash,
    String customerId,
    Instant createdAt,
    Instant deletedAt) {}
