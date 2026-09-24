package com.gateway.payments.repository;

import com.gateway.payments.domain.Refund;
import com.gateway.payments.domain.RefundState;
import java.util.List;
import java.util.Optional;

public interface RefundRepository {
  Refund save(Refund refund);

  Optional<Refund> findById(String id);

  List<Refund> findByPayment(String paymentId);

  List<Refund> findByState(RefundState state);
}
