package com.gateway.payments.service;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.Charge;
import com.gateway.kernel.provider.ChargeStatus;
import com.gateway.kernel.provider.PixProvider;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.ProviderWebhookEvent;
import com.gateway.kernel.provider.ReceivedPix;
import com.gateway.kernel.provider.RefundRequest;
import com.gateway.kernel.provider.RefundResult;
import com.gateway.kernel.provider.RefundStatus;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory implementation of the kernel's {@link PixProvider}, for the payments module's tests
 * only. It is not a product provider: the product talks to a real bank (the sandbox for TEST), and
 * this class never leaves {@code src/test}.
 *
 * <p>The Spring context is shared between test classes, so every call is recorded as
 * {@code op:key} and assertions filter by the test's own txid instead of resetting shared state.
 */
public class RecordingPixProvider implements PixProvider {
  private final Clock clock;
  private final Map<String, Charge> charges = new ConcurrentHashMap<>();
  private final Map<String, RefundResult> refunds = new ConcurrentHashMap<>();
  private final List<String> calls = new CopyOnWriteArrayList<>();
  private volatile ProviderException failNextCreate;
  private volatile ProviderException landThenFail;
  private volatile RefundStatus nextRefundStatus = RefundStatus.PROCESSING;

  public RecordingPixProvider(Clock clock) {
    this.clock = clock;
  }

  @Override
  public String id() {
    return "ITAU";
  }

  public void failNextCreateWith(ProviderException e) {
    this.failNextCreate = e;
  }

  /** The PUT reached the bank and created the charge, but the response never came back. */
  public void timeoutNextCreateButCreateAnyway() {
    landNextCreateThenFailWith(new ProviderException(ProviderException.Code.TIMEOUT, "read timed out", null));
  }

  /** The charge is created at the bank, but the caller sees {@code e} (a 503 from a proxy, say). */
  public void landNextCreateThenFailWith(ProviderException e) {
    this.landThenFail = e;
  }

  public void markPaid(String txid, String endToEndId, Money amount) {
    Charge c = charges.get(txid);
    charges.put(
        txid,
        new Charge(txid, ChargeStatus.COMPLETED, c.amount(), c.pixCopiaECola(), c.location(), c.createdAt(), c.expiresInSeconds(),
            List.of(new ReceivedPix(endToEndId, amount, clock.instant(), "payer"))));
  }

  public void setStatus(String txid, ChargeStatus status) {
    Charge c = charges.get(txid);
    charges.put(txid, new Charge(txid, status, c.amount(), c.pixCopiaECola(), c.location(), c.createdAt(), c.expiresInSeconds(), c.received()));
  }

  public void nextRefundStatus(RefundStatus s) {
    this.nextRefundStatus = s;
  }

  public void refundResult(RefundResult r) {
    refunds.put(r.refundId(), r);
  }

  public List<String> callsFor(String key) {
    return calls.stream().filter(c -> c.endsWith(":" + key)).toList();
  }

  @Override
  public Charge createCharge(
      ProviderCredentials c, String txid, Money amount, int expiresInSeconds, String payerDocument, String payerName, String description) {
    calls.add("createCharge:" + txid);
    ProviderException fail = failNextCreate;
    if (fail != null) {
      failNextCreate = null;
      throw fail;
    }
    Charge charge =
        new Charge(txid, ChargeStatus.ACTIVE, amount, "00020101021226" + txid, "pix.example/qr/" + txid, clock.instant(), expiresInSeconds, List.of());
    charges.put(txid, charge);
    ProviderException after = landThenFail;
    if (after != null) {
      landThenFail = null;
      throw after;
    }
    return charge;
  }

  @Override
  public Optional<Charge> findCharge(ProviderCredentials c, String txid) {
    calls.add("findCharge:" + txid);
    return Optional.ofNullable(charges.get(txid));
  }

  @Override
  public void cancelCharge(ProviderCredentials c, String txid) {
    calls.add("cancelCharge:" + txid);
    Charge charge = charges.get(txid);
    if (charge == null) {
      throw new ProviderException(ProviderException.Code.NOT_FOUND, 404, "CobNaoEncontrado", "not found");
    }
    if (charge.status() != ChargeStatus.ACTIVE) {
      throw new ProviderException(ProviderException.Code.INVALID, 400, "CobOperacaoInvalida", "not active");
    }
    setStatus(txid, ChargeStatus.REMOVED_BY_MERCHANT);
  }

  @Override
  public RefundResult requestRefund(ProviderCredentials c, RefundRequest r) {
    calls.add("requestRefund:" + r.refundId());
    RefundResult result = new RefundResult(r.refundId(), nextRefundStatus, r.amount(), null, clock.instant(), null);
    refunds.put(r.refundId(), result);
    return result;
  }

  @Override
  public Optional<RefundResult> findRefund(ProviderCredentials c, String endToEndId, String refundId) {
    calls.add("findRefund:" + refundId);
    return Optional.ofNullable(refunds.get(refundId));
  }

  @Override
  public List<Charge> listCharges(ProviderCredentials c, Instant from, Instant to) {
    calls.add("listCharges:all");
    return new ArrayList<>(charges.values());
  }

  /**
   * Test body format, one Pix per line: {@code endToEndId txid cents}. Anything else is
   * unreadable, which is what lets a test drive the FAILED path.
   */
  @Override
  public ProviderWebhookEvent parseWebhook(byte[] body) {
    List<ReceivedPix> received = new ArrayList<>();
    Map<String, String> txids = new HashMap<>();
    for (String line : new String(body, StandardCharsets.UTF_8).strip().split("\n")) {
      String[] parts = line.strip().split(" ");
      if (parts.length != 3) {
        throw new IllegalArgumentException("unreadable webhook line: " + line);
      }
      received.add(new ReceivedPix(parts[0], Money.brl(Long.parseLong(parts[2])), clock.instant(), "payer"));
      txids.put(parts[0], parts[1]);
    }
    return new ProviderWebhookEvent(received, List.of(), txids);
  }
}
