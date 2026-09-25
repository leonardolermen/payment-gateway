package com.gateway.payments.refund.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface RefundJpaRepository extends JpaRepository<RefundEntity, String> {
  List<RefundEntity> findByPaymentId(String paymentId);

  List<RefundEntity> findByState(String state);
}
