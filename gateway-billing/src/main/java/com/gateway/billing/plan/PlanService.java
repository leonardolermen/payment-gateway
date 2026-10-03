package com.gateway.billing.plan;

import com.gateway.billing.plan.persistence.PlanRepository;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.payments.UnitOfWork;
import java.time.Clock;
import java.util.List;

/** No events: spec 9 lists none for plans, they are catalogue. */
public class PlanService {
  private final PlanRepository plans;
  private final UnitOfWork unitOfWork;
  private final Clock clock;

  public PlanService(PlanRepository plans, UnitOfWork unitOfWork, Clock clock) {
    this.plans = plans;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  public Plan create(Plan plan) {
    return unitOfWork.inTransaction(
        () -> {
          plans.insert(plan);

          return plan;
        });
  }

  public Plan get(MerchantId merchantId, String id) {
    return plans.find(merchantId, id).orElseThrow(() -> new NotFoundException("plan", id));
  }

  /** {@code active == null} lists every plan. */
  public List<Plan> list(MerchantId merchantId, Boolean active) {
    return plans.list(merchantId, active);
  }

  public Plan rename(MerchantId merchantId, String id, String name) {
    String validName = PlanFactory.validName(name);

    return save(get(merchantId, id).rename(validName, clock.instant()));
  }

  public Plan setActive(MerchantId merchantId, String id, boolean active) {
    Plan plan = get(merchantId, id);

    return save(active ? plan.activate(clock.instant()) : plan.deactivate(clock.instant()));
  }

  private Plan save(Plan changed) {
    if (!plans.update(changed)) {
      throw new DomainException("CONFLICT", "plan " + changed.id() + " changed concurrently");
    }

    return changed;
  }
}
