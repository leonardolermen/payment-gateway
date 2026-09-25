package com.gateway.app.api.refund;

import com.gateway.app.api.support.IdempotencyFilter;
import com.gateway.app.api.refund.dto.RefundRequestBody;
import com.gateway.app.api.refund.dto.RefundResponse;
import com.gateway.app.security.MerchantContext;
import com.gateway.kernel.money.Money;
import com.gateway.payments.refund.Refund;
import com.gateway.payments.refund.RefundService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Refunds of the calling merchant's payments. Only BRL exists, so a refund amount is BRL cents. */
@RestController
public class RefundsController {
  private final RefundService refunds;

  public RefundsController(RefundService refunds) { this.refunds = refunds; }

  @PostMapping("/v1/payments/{paymentId}/refunds")
  public ResponseEntity<RefundResponse> request(@PathVariable String paymentId, @RequestBody(required = false) RefundRequestBody body) {
    Long cents = body == null ? null : body.amount();
    if (cents != null && cents <= 0) throw new IllegalArgumentException("amount must be a positive number of cents");
    Refund r = refunds.request(MerchantContext.current().merchantId(), paymentId, cents == null ? null : Money.brl(cents));
    return ResponseEntity.status(HttpStatus.CREATED).header(IdempotencyFilter.RESOURCE_ID_HEADER, r.id()).body(RefundResponse.from(r));
  }

  @GetMapping("/v1/payments/{paymentId}/refunds")
  public List<RefundResponse> list(@PathVariable String paymentId) {
    return refunds.list(MerchantContext.current().merchantId(), paymentId).stream().map(RefundResponse::from).toList();
  }

  @GetMapping("/v1/refunds/{id}")
  public RefundResponse get(@PathVariable String id) {
    return RefundResponse.from(refunds.get(MerchantContext.current().merchantId(), id));
  }
}
