package com.gateway.kernel.provider.pix;

import com.gateway.kernel.money.Money;

/**
 * What a Pix charge needs at the bank. {@code txid} is ours — we PUT /cob/{payment id} — so a retry
 * after a timeout can ask the bank whether the charge exists.
 */
public record PixIssueRequest(
    String txid,
    Money amount,
    int expiresInSeconds,
    String payerDocument,
    String payerName,
    String description) {}
