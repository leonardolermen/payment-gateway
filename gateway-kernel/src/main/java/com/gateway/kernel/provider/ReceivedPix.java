package com.gateway.kernel.provider;

import com.gateway.kernel.money.Money;
import java.time.Instant;

/** A Pix payment received against a charge. {@code endToEndId} is the Bacen-wide dedup key. */
public record ReceivedPix(String endToEndId, Money amount, Instant paidAt, String payerInfo) {}
