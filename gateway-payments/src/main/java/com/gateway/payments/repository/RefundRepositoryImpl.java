package com.gateway.payments.repository;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.payments.domain.Refund;
import com.gateway.payments.domain.RefundState;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class RefundRepositoryImpl implements RefundRepository {
  private final RefundJpaRepository jpa;

  public RefundRepositoryImpl(RefundJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Refund save(Refund refund) {
    RefundEntity e = jpa.findById(refund.id()).orElseGet(RefundEntity::new);
    e.id = refund.id();
    e.paymentId = refund.paymentId();
    e.merchantId = refund.merchantId().value();
    e.amount = refund.amount().cents();
    e.state = refund.state().name();
    e.providerRefundId = null;
    e.reason = refund.failureReason();
    e.requestedAt = refund.createdAt();
    e.settledAt = refund.settledAt();
    e.updatedAt = refund.settledAt() != null ? refund.settledAt() : refund.createdAt();
    return toDomain(jpa.save(e));
  }

  @Override
  public Optional<Refund> findById(String id) {
    return jpa.findById(id).map(RefundRepositoryImpl::toDomain);
  }

  @Override
  public List<Refund> findByPayment(String paymentId) {
    return jpa.findByPaymentId(paymentId).stream().map(RefundRepositoryImpl::toDomain).toList();
  }

  @Override
  public List<Refund> findByState(RefundState state) {
    return jpa.findByState(state.name()).stream().map(RefundRepositoryImpl::toDomain).toList();
  }

  private static Refund toDomain(RefundEntity e) {
    return Refund.rehydrate(
        e.id,
        e.paymentId,
        new MerchantId(e.merchantId),
        new Money(e.amount, "BRL"),
        RefundState.valueOf(e.state),
        e.settledAt,
        e.reason,
        e.requestedAt);
  }
}
