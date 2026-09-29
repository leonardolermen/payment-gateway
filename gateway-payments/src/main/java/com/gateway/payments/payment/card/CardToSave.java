package com.gateway.payments.payment.card;

import java.time.YearMonth;

/**
 * What a saved card keeps besides the acquirer's token, which only the answer brings: the face of
 * the card from the request. Null when the merchant did not ask for save_card.
 */
public record CardToSave(String holder, YearMonth expiry, String customerDocumentHash) {}
