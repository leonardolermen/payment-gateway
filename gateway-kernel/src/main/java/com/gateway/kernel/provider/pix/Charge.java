package com.gateway.kernel.provider.pix;

import com.gateway.kernel.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The gateway's view of a Pix charge, whatever bank issued it. */
public record Charge(String txid, ChargeStatus status, Money amount, String pixCopiaECola, String location,
                     Instant createdAt, int expiresInSeconds, List<ReceivedPix> received) {
  public Optional<ReceivedPix> firstPix() { return received == null || received.isEmpty() ? Optional.empty() : Optional.of(received.getFirst()); }
}
