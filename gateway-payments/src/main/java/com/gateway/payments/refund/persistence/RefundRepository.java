package com.gateway.payments.refund.persistence;

import com.gateway.payments.refund.Refund;
import com.gateway.payments.refund.RefundState;
import java.util.List;
import java.util.Optional;

public interface RefundRepository {
  Refund save(Refund refund);

  Optional<Refund> findById(String id);

  List<Refund> findByPayment(String paymentId);

  List<Refund> findByState(RefundState state);
}
