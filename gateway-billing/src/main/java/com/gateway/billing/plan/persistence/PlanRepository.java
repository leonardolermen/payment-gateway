package com.gateway.billing.plan.persistence;

import com.gateway.billing.plan.Plan;
import com.gateway.kernel.ids.MerchantId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlanRepository {
  /** Requires a transaction. */
  void insert(Plan plan);

  /** Optimistic: writes only when the stored version is {@code plan.version() - 1}. */
  boolean update(Plan plan);

  Optional<Plan> find(MerchantId merchantId, String id);

  /** The merchant's plans among {@code ids}, in one query. */
  List<Plan> findAll(MerchantId merchantId, Collection<String> ids);

  /** {@code active == null} returns every plan of the merchant, newest first. */
  List<Plan> list(MerchantId merchantId, Boolean active);
}
