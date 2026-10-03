package com.gateway.app.api.plan;

import com.gateway.app.api.plan.dto.PlanPatchRequest;
import com.gateway.app.api.plan.dto.PlanRequest;
import com.gateway.app.api.plan.dto.PlanResponse;
import com.gateway.app.api.support.IdempotencyFilter;
import com.gateway.app.security.MerchantContext;
import com.gateway.billing.plan.Plan;
import com.gateway.billing.plan.PlanService;
import com.gateway.kernel.ids.MerchantId;
import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A plan has no environment: it is a price list, and only what charges it is TEST or LIVE. */
@RestController
@RequestMapping("/v1/plans")
public class PlansController {
  private final PlanService plans;
  private final Clock clock;

  public PlansController(PlanService plans, Clock clock) {
    this.plans = plans;
    this.clock = clock;
  }

  @PostMapping
  public ResponseEntity<PlanResponse> create(@RequestBody PlanRequest request) {
    Plan created = plans.create(request.toPlan(MerchantContext.current().merchantId(), clock));

    // The header is read and stripped by IdempotencyFilter; clients never see it.
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(IdempotencyFilter.RESOURCE_ID_HEADER, created.id())
        .body(PlanResponse.from(created));
  }

  @GetMapping("/{id}")
  public PlanResponse get(@PathVariable String id) {
    return PlanResponse.from(plans.get(MerchantContext.current().merchantId(), id));
  }

  @GetMapping
  public List<PlanResponse> list(@RequestParam(required = false) Boolean active) {
    return plans.list(MerchantContext.current().merchantId(), active).stream()
        .map(PlanResponse::from)
        .toList();
  }

  /**
   * Two writes when both fields are sent; each is a whole, versioned change of its own, so a
   * failure between them leaves a plan that is renamed but not yet (de)activated, never a torn one.
   */
  @PatchMapping("/{id}")
  public PlanResponse update(@PathVariable String id, @RequestBody PlanPatchRequest request) {
    request.validate();

    MerchantId merchantId = MerchantContext.current().merchantId();
    Plan plan = plans.get(merchantId, id);

    if (request.name() != null) {
      plan = plans.rename(merchantId, id, request.name());
    }
    if (request.active() != null) {
      plan = plans.setActive(merchantId, id, request.active());
    }

    return PlanResponse.from(plan);
  }
}
