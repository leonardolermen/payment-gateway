package com.gateway.app.api.payment;

import com.gateway.app.api.payment.dto.CaptureRequestBody;
import com.gateway.app.api.payment.dto.CreatePaymentRequest;
import com.gateway.app.api.payment.dto.PaymentEventResponse;
import com.gateway.app.api.payment.dto.PaymentResponse;
import com.gateway.app.api.support.Environments;
import com.gateway.app.api.support.IdempotencyFilter;
import com.gateway.app.security.MerchantContext;
import com.gateway.kernel.money.Money;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentCancellation;
import com.gateway.payments.payment.PaymentQueries;
import com.gateway.payments.payment.PaymentService;
import com.gateway.payments.payment.card.CardCapture;
import com.gateway.payments.payment.create.CreatePaymentCommand;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payments for the calling merchant. The environment is never a request field: it is the API key's
 * ({@link MerchantContext}), so a TEST key cannot reach the merchant's LIVE bank credential no
 * matter what the body says. POSTs are wrapped by {@link IdempotencyFilter}.
 */
@RestController
@RequestMapping("/v1/payments")
public class PaymentsController {
  private static final int MAX_PAGE = 100;

  private final PaymentService payments;
  private final PaymentQueries queries;
  private final PaymentCancellation cancellation;
  private final CardCapture capture;

  public PaymentsController(
      PaymentService payments,
      PaymentQueries queries,
      PaymentCancellation cancellation,
      CardCapture capture) {
    this.payments = payments;
    this.queries = queries;
    this.cancellation = cancellation;
    this.capture = capture;
  }

  @PostMapping
  public ResponseEntity<PaymentResponse> create(@RequestBody CreatePaymentRequest request) {
    request.validate();

    MerchantContext.Current caller = MerchantContext.current();
    CreatePaymentCommand paymentCommand =
        request.toCommand(caller.merchantId(), Environments.toProvider(caller.environment()));

    return withResource(HttpStatus.CREATED, payments.create(paymentCommand));
  }

  @GetMapping("/{id}")
  public PaymentResponse get(@PathVariable String id) {
    return PaymentResponse.from(queries.get(MerchantContext.current().merchantId(), id));
  }

  /**
   * {@code reference} is the merchant's own order id: how a client whose create was interrupted
   * finds out whether the payment exists before trying again with a new Idempotency-Key.
   */
  @GetMapping
  public List<PaymentResponse> list(
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) String reference) {

    if (limit <= 0 || limit > MAX_PAGE) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE);
    }

    var merchantId = MerchantContext.current().merchantId();

    if (reference != null) {

      if (cursor != null) {
        throw new IllegalArgumentException("cursor and reference cannot be combined");
      }
      return queries.listByReference(merchantId, reference, limit).stream()
          .map(PaymentResponse::from)
          .toList();
    }

    return queries.list(merchantId, limit, cursor).stream().map(PaymentResponse::from).toList();
  }

  @GetMapping("/{id}/events")
  public List<PaymentEventResponse> events(@PathVariable String id) {
    return queries.events(MerchantContext.current().merchantId(), id).stream()
        .map(PaymentEventResponse::from)
        .toList();
  }

  @PostMapping("/{id}/cancel")
  public ResponseEntity<PaymentResponse> cancel(@PathVariable String id) {
    return withResource(
        HttpStatus.OK, cancellation.cancel(MerchantContext.current().merchantId(), id));
  }

  /** Spec §6. Wrapped by IdempotencyFilter like every POST action on a payment. */
  @PostMapping("/{id}/capture")
  public ResponseEntity<PaymentResponse> capture(
      @PathVariable String id, @RequestBody(required = false) CaptureRequestBody body) {
    Long cents = body == null ? null : body.amount();
    if (cents != null && cents <= 0) {
      throw new IllegalArgumentException("amount must be a positive number of cents");
    }

    Payment captured =
        capture.capture(
            MerchantContext.current().merchantId(), id, cents == null ? null : Money.brl(cents));

    return withResource(HttpStatus.OK, captured);
  }

  /**
   * {@link IdempotencyFilter#RESOURCE_ID_HEADER} is read and stripped by the filter; clients never
   * see it.
   */
  private static ResponseEntity<PaymentResponse> withResource(HttpStatus status, Payment payment) {
    return ResponseEntity.status(status)
        .header(IdempotencyFilter.RESOURCE_ID_HEADER, payment.id())
        .body(PaymentResponse.from(payment));
  }
}
