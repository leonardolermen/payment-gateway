package com.gateway.app.api.dispute;

import com.gateway.app.api.dispute.dto.DisputeResponse;
import com.gateway.app.api.dispute.dto.OpenDisputeRequest;
import com.gateway.app.api.support.IdempotencyFilter;
import com.gateway.app.security.MerchantContext;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.payments.dispute.Dispute;
import com.gateway.payments.dispute.DisputePage;
import com.gateway.payments.dispute.DisputeService;
import com.gateway.payments.reconciliation.DivergenceStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The merchant's disputes. Opening hangs off the payment it is about; reading has its own root,
 * because a merchant follows disputes as a queue, not one payment at a time. The open is wrapped by
 * {@link IdempotencyFilter}. The operator's side is {@code DivergencesAdminController}.
 */
@RestController
public class DisputesController {
  static final String NEXT_CURSOR_HEADER = "X-Next-Cursor";

  private final DisputeService disputes;

  public DisputesController(DisputeService disputes) {
    this.disputes = disputes;
  }

  @PostMapping("/v1/payments/{paymentId}/disputes")
  public ResponseEntity<DisputeResponse> open(
      @PathVariable String paymentId, @RequestBody OpenDisputeRequest request) {
    Dispute dispute = disputes.open(merchantId(), paymentId, request.reason(), request.note());

    return ResponseEntity.status(HttpStatus.CREATED)
        .header(IdempotencyFilter.RESOURCE_ID_HEADER, dispute.id())
        .body(DisputeResponse.from(dispute));
  }

  @GetMapping("/v1/disputes")
  public ResponseEntity<List<DisputeResponse>> list(
      @RequestParam(required = false) DivergenceStatus status,
      @RequestParam(required = false) Instant since,
      @RequestParam(required = false) String after,
      @RequestParam(defaultValue = "20") int limit) {
    DisputePage page = disputes.list(merchantId(), status, since, after, limit);

    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (page.nextCursor() != null) {
      response.header(NEXT_CURSOR_HEADER, page.nextCursor());
    }

    return response.body(page.disputes().stream().map(DisputeResponse::from).toList());
  }

  @GetMapping("/v1/disputes/{id}")
  public DisputeResponse get(@PathVariable String id) {
    return DisputeResponse.from(disputes.get(merchantId(), id));
  }

  private static MerchantId merchantId() {
    return MerchantContext.current().merchantId();
  }
}
