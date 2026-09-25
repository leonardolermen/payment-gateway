package com.gateway.payments.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface PaymentEventJpaRepository extends JpaRepository<PaymentEventEntity, String> {
  List<PaymentEventEntity> findByPaymentIdOrderBySequenceAsc(String paymentId);
}
