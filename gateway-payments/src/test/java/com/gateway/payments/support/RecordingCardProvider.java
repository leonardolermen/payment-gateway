package com.gateway.payments.support;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardAuthorization;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.kernel.provider.card.CardDeclineCode;
import com.gateway.kernel.provider.card.CardIssueRequest;
import com.gateway.kernel.provider.card.CardMethodProvider;
import com.gateway.kernel.provider.card.CardNotification;
import com.gateway.kernel.provider.card.CardNotificationKind;
import com.gateway.kernel.provider.card.CardRefundResult;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.kernel.provider.card.StoredCard;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory {@link CardMethodProvider} for the payments module's tests only. Behaves like the Cielo
 * sandbox by the card's last digit (reference/credito-sandbox), with production-like codes: 2 is
 * declined with 51 (insufficient funds), 3 with 57 (do not honor), anything else approves — PAID
 * with capture, AUTHORIZED without. The hooks below play what the sandbox cannot on demand: a lost
 * answer, an in-doubt 201, a query that fails.
 */
public class RecordingCardProvider implements CardMethodProvider {
  private static final Pattern PAYMENT_ID = Pattern.compile("\"PaymentId\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern CHANGE_TYPE = Pattern.compile("\"ChangeType\"\\s*:\\s*(\\d+)");

  private final Clock clock;
  private final Map<String, CardAuthorization> sales = new ConcurrentHashMap<>();
  private final Map<String, String> paymentIdByOrder = new ConcurrentHashMap<>();
  private final List<CardIssueRequest> issued = new CopyOnWriteArrayList<>();
  private final List<String> calls = new CopyOnWriteArrayList<>();
  private volatile ProviderException failNextAuthorize;
  private volatile ProviderException landThenFail;
  private volatile ProviderException failNextFindByOrder;
  private volatile ProviderException failNextCapture;
  private volatile ProviderException failNextRefund;
  private volatile String nextRefundReturnCode;
  private volatile boolean nextCaptureLags;
  private volatile Runnable duringNextCapture;
  private final Map<String, Money> refundedByPayment = new ConcurrentHashMap<>();
  private volatile CardStatus nextAuthorizeStatus;
  private volatile CardStatus nextFindByOrderStatus;
  private volatile boolean withholdNextToken;
  private volatile Duration delayNextIssue;

  public RecordingCardProvider(Clock clock) {
    this.clock = clock;
  }

  /** The next sale stays at the Cielo for {@code delay} before answering: a slow acquirer. */
  public void delayNextIssue(Duration delay) {
    this.delayNextIssue = delay;
  }

  public void failNextAuthorizeWith(ProviderException e) {
    this.failNextAuthorize = e;
  }

  /** The sale reaches the Cielo, but the caller sees {@code e} (a timeout, a 503). */
  public void landNextAuthorizeThenFailWith(ProviderException e) {
    this.landThenFail = e;
  }

  /** The next 201 carries this status (NOT_FINISHED, PENDING …) instead of the digit's answer. */
  public void nextAuthorizeStatus(CardStatus status) {
    this.nextAuthorizeStatus = status;
  }

  /** Between the in-doubt answer and the query, the acquirer decides this. */
  public void nextFindByOrderStatus(CardStatus status) {
    this.nextFindByOrderStatus = status;
  }

  public void failNextFindByOrderWith(ProviderException e) {
    this.failNextFindByOrder = e;
  }

  /** The Cielo approved but returned no CardToken for a SaveCard request. */
  public void withholdNextToken() {
    this.withholdNextToken = true;
  }

  public void failNextCaptureWith(ProviderException e) {
    this.failNextCapture = e;
  }

  public void failNextRefundWith(ProviderException e) {
    this.failNextRefund = e;
  }

  /**
   * The next capture lands, but the answer is what a lagging query host shows: PAID without
   * CapturedAmount.
   */
  public void nextCaptureAnswersWithoutAmount() {
    this.nextCaptureLags = true;
  }

  /** Runs while the next capture is at the Cielo: a cancel or a notification racing it. */
  public void duringNextCapture(Runnable race) {
    this.duringNextCapture = race;
  }

  /** The next void answers 200 with this ReturnCode and changes nothing (100, 101 …). */
  public void nextRefundReturnCode(String returnCode) {
    this.nextRefundReturnCode = returnCode;
  }

  /** What the Cielo shows now: a capture or a void done outside the gateway. */
  public void setStatus(String paymentId, CardStatus status) {
    CardAuthorization sale = sales.get(paymentId);
    Money captured = status == CardStatus.PAID ? sale.amount() : sale.capturedAmount();
    sales.put(paymentId, with(sale, status, captured));
  }

  public CardAuthorization sale(String paymentId) {
    return sales.get(paymentId);
  }

  public CardIssueRequest lastIssued() {
    return issued.getLast();
  }

  /** The calls naming {@code key} (a MerchantOrderId or a PaymentId), as "operation:key". */
  public List<String> callsFor(String key) {
    return calls.stream().filter(call -> call.endsWith(":" + key)).toList();
  }

  @Override
  public String id() {
    return "CIELO";
  }

  @Override
  public PaymentMethod method() {
    return PaymentMethod.CARD;
  }

  @Override
  public void requireIssueCredentials(ProviderCredentials credentials) {}

  @Override
  public CardAuthorization issue(ProviderCredentials credentials, CardIssueRequest request) {
    calls.add("authorize:" + request.merchantOrderId());
    issued.add(request);

    Duration delay = delayNextIssue;
    if (delay != null) {
      delayNextIssue = null;
      try {
        Thread.sleep(delay);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    ProviderException fail = failNextAuthorize;
    if (fail != null) {
      failNextAuthorize = null;
      throw fail;
    }

    CardAuthorization sale = newSale(request);
    sales.put(sale.paymentId(), sale);
    paymentIdByOrder.put(request.merchantOrderId(), sale.paymentId());

    ProviderException after = landThenFail;
    if (after != null) {
      landThenFail = null;
      throw after;
    }

    return sale;
  }

  @Override
  public Optional<CardAuthorization> find(ProviderCredentials credentials, String paymentId) {
    calls.add("find:" + paymentId);
    return Optional.ofNullable(sales.get(paymentId));
  }

  @Override
  public Optional<CardAuthorization> findByOrder(
      ProviderCredentials credentials, String merchantOrderId) {
    calls.add("findByOrder:" + merchantOrderId);

    ProviderException fail = failNextFindByOrder;
    if (fail != null) {
      failNextFindByOrder = null;
      throw fail;
    }

    String paymentId = paymentIdByOrder.get(merchantOrderId);
    CardStatus decided = nextFindByOrderStatus;
    if (paymentId != null && decided != null) {
      nextFindByOrderStatus = null;
      setStatus(paymentId, decided);
    }

    return Optional.ofNullable(paymentId == null ? null : sales.get(paymentId));
  }

  @Override
  public void cancel(ProviderCredentials credentials, String paymentId) {
    calls.add("void:" + paymentId);
    CardAuthorization sale = sales.get(paymentId);
    if (sale == null || sale.status() != CardStatus.AUTHORIZED) {
      throw new ProviderException(
          ProviderException.Code.CONFLICT, 200, "309", "void answered status");
    }
    sales.put(paymentId, with(sale, CardStatus.VOIDED, sale.capturedAmount()));
  }

  /** One capture per sale, like the Cielo: a second one is 308. */
  @Override
  public CardAuthorization capture(
      ProviderCredentials credentials, String paymentId, Optional<Money> amount) {
    calls.add("capture:" + paymentId);

    ProviderException fail = failNextCapture;
    if (fail != null) {
      failNextCapture = null;
      throw fail;
    }

    CardAuthorization sale = sales.get(paymentId);
    if (sale.status() != CardStatus.AUTHORIZED) {
      throw new ProviderException(
          ProviderException.Code.INVALID, 400, "308", "308 Transaction not available to capture");
    }

    Runnable race = duringNextCapture;
    if (race != null) {
      duringNextCapture = null;
      race.run();
    }

    CardAuthorization captured = with(sale, CardStatus.PAID, amount.orElse(sale.amount()));
    sales.put(paymentId, captured);

    if (nextCaptureLags) {
      nextCaptureLags = false;
      return with(sale, CardStatus.PAID, null);
    }

    return captured;
  }

  @Override
  public CardRefundResult refund(
      ProviderCredentials credentials, String paymentId, Optional<Money> amount) {
    calls.add("refund:" + paymentId);

    ProviderException fail = failNextRefund;
    if (fail != null) {
      failNextRefund = null;
      throw fail;
    }

    CardAuthorization sale = sales.get(paymentId);
    String refused = nextRefundReturnCode;
    if (refused != null) {
      nextRefundReturnCode = null;
      return new CardRefundResult(sale.status(), amount.orElse(null), refused, "not performed");
    }

    // Like the Cielo: a void that leaves money on the sale keeps it PAID (Status 2) and answers 0;
    // only the one that empties it moves the status and answers 9.
    Money refunded =
        refundedByPayment.merge(paymentId, amount.orElse(sale.capturedAmount()), Money::plus);
    boolean leavesMoneyOnTheSale = sale.capturedAmount().greaterThan(refunded);
    if (leavesMoneyOnTheSale) {
      return new CardRefundResult(sale.status(), amount.orElse(null), "0", "Operation Successful");
    }

    sales.put(paymentId, with(sale, CardStatus.REFUNDED, sale.capturedAmount()));
    return new CardRefundResult(
        CardStatus.REFUNDED, amount.orElse(null), "9", "Operation Successful");
  }

  @Override
  public StoredCard tokenize(ProviderCredentials credentials, CardData card, String customerName) {
    return new StoredCard(
        UUID.randomUUID().toString(), card.brand(), card.last4(), card.expiry().value());
  }

  @Override
  public CardNotification parseWebhook(byte[] body) {
    String text = new String(body, StandardCharsets.UTF_8);
    Matcher paymentId = PAYMENT_ID.matcher(text);
    Matcher changeType = CHANGE_TYPE.matcher(text);
    if (!paymentId.find() || !changeType.find()) {
      throw new IllegalArgumentException("notification without PaymentId");
    }

    int change = Integer.parseInt(changeType.group(1));
    CardNotificationKind kind =
        switch (change) {
          case 1 -> CardNotificationKind.STATUS_CHANGED;
          case 25 -> CardNotificationKind.PARTIAL_REFUND;
          case 5 -> CardNotificationKind.VOID_DENIED;
          case 8 -> CardNotificationKind.FRAUD_ALERT;
          default -> CardNotificationKind.IGNORED;
        };
    return new CardNotification(paymentId.group(1), kind, change);
  }

  private CardAuthorization newSale(CardIssueRequest request) {
    CardStatus status = nextAuthorizeStatus;
    nextAuthorizeStatus = null;
    String returnCode = "6";

    if (status == null) {
      String lastDigit =
          request.source() instanceof CardData card ? card.last4().substring(3) : "1";
      returnCode =
          switch (lastDigit) {
            case "2" -> "51";
            case "3" -> "57";
            default -> request.capture() ? "6" : "4";
          };
      status =
          returnCode.equals("51") || returnCode.equals("57")
              ? CardStatus.DENIED
              : request.capture() ? CardStatus.PAID : CardStatus.AUTHORIZED;
    }

    boolean approved = status == CardStatus.PAID || status == CardStatus.AUTHORIZED;
    boolean token = approved && request.saveCard() && !withholdNextToken;
    withholdNextToken = false;
    CardDeclineCode decline =
        status == CardStatus.DENIED
            ? (returnCode.equals("51")
                ? CardDeclineCode.INSUFFICIENT_FUNDS
                : CardDeclineCode.DO_NOT_HONOR)
            : null;

    return new CardAuthorization(
        UUID.randomUUID().toString(),
        status,
        returnCode,
        "sandbox-like",
        decline,
        "tid-" + request.merchantOrderId(),
        approved ? "123456" : null,
        "654321",
        request.amount(),
        status == CardStatus.PAID ? request.amount() : null,
        request.source().brand(),
        request.source() instanceof CardData card ? card.last4() : null,
        token ? Optional.of(UUID.randomUUID().toString()) : Optional.empty(),
        clock.instant(),
        status == CardStatus.PAID ? Optional.of(clock.instant()) : Optional.empty());
  }

  private CardAuthorization with(CardAuthorization sale, CardStatus status, Money captured) {
    return new CardAuthorization(
        sale.paymentId(),
        status,
        sale.returnCode(),
        sale.returnMessage(),
        sale.declineCode(),
        sale.tid(),
        sale.authorizationCode(),
        sale.proofOfSale(),
        sale.amount(),
        captured,
        sale.brand(),
        sale.last4(),
        sale.cardToken(),
        sale.receivedAt(),
        status == CardStatus.PAID ? Optional.of(clock.instant()) : sale.capturedAt());
  }
}
