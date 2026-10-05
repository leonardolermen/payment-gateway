package com.gateway.app.api.admin.divergence;

import com.gateway.app.api.admin.divergence.dto.DivergenceResponse;
import com.gateway.app.api.admin.divergence.dto.ResolveDivergenceRequest;
import com.gateway.payments.reconciliation.DivergenceAdministration;
import com.gateway.payments.reconciliation.DivergenceDetail;
import com.gateway.payments.reconciliation.DivergenceOrigin;
import com.gateway.payments.reconciliation.DivergenceQuery;
import com.gateway.payments.reconciliation.DivergenceStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The operator's divergence queue. {@code AdminKeyFilter} guards {@code /v1/admin/**}. */
@RestController
@RequestMapping("/v1/admin/divergences")
public class DivergencesAdminController {
  static final String NEXT_CURSOR_HEADER = "X-Next-Cursor";

  /**
   * Every admin call shares one key, so there is no one to name yet; operators arrive in plan H.
   */
  private static final String RESOLVED_BY = "admin";

  private final DivergenceAdministration administration;

  public DivergencesAdminController(DivergenceAdministration administration) {
    this.administration = administration;
  }

  @GetMapping
  public ResponseEntity<List<DivergenceResponse>> list(
      @RequestParam(required = false) DivergenceStatus status,
      @RequestParam(required = false) DivergenceOrigin origin,
      @RequestParam(required = false) String kind,
      @RequestParam(name = "merchant_id", required = false) String merchantId,
      @RequestParam(required = false) Instant since,
      @RequestParam(required = false) String after,
      @RequestParam(defaultValue = "20") int limit) {
    DivergenceQuery query =
        new DivergenceQuery(status, origin, kind, merchantId, since, after, limit);

    List<DivergenceDetail> page = administration.list(query);

    // A full page only means there MAY be more, as in the deliveries listing: the next call can
    // come back empty.
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (page.size() == query.limit()) {
      response.header(NEXT_CURSOR_HEADER, page.getLast().divergence().id());
    }

    return response.body(page.stream().map(DivergenceResponse::from).toList());
  }

  @GetMapping("/{id}")
  public DivergenceResponse get(@PathVariable String id) {
    return DivergenceResponse.from(administration.detail(id));
  }

  @PostMapping("/{id}/review")
  public DivergenceResponse review(@PathVariable String id) {
    administration.review(id, RESOLVED_BY);

    return DivergenceResponse.from(administration.detail(id));
  }

  @PostMapping("/{id}/resolve")
  public DivergenceResponse resolve(
      @PathVariable String id, @RequestBody ResolveDivergenceRequest request) {
    administration.resolve(id, request.resolution(), request.note(), RESOLVED_BY);

    return DivergenceResponse.from(administration.detail(id));
  }
}
