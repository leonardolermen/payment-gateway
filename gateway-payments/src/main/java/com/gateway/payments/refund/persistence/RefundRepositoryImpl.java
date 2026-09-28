package com.gateway.payments.refund.persistence;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.payments.refund.Refund;
import com.gateway.payments.refund.RefundState;
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
    RefundEntity entity = jpa.findById(refund.id()).orElseGet(RefundEntity::new);
    entity.id = refund.id();
    entity.paymentId = refund.paymentId();
    entity.merchantId = refund.merchantId().value();
    entity.amount = refund.amount().cents();
    entity.state = refund.state().name();
    entity.providerRefundId = null;
    entity.reason = refund.failureReason();
    entity.requestedAt = refund.createdAt();
    entity.settledAt = refund.settledAt();
    entity.updatedAt = refund.settledAt() != null ? refund.settledAt() : refund.createdAt();
    return toDomain(jpa.save(entity));
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

  private static Refund toDomain(RefundEntity entity) {
    return Refund.rehydrate(
        entity.id,
        entity.paymentId,
        new MerchantId(entity.merchantId),
        new Money(entity.amount, "BRL"),
        RefundState.valueOf(entity.state),
        entity.settledAt,
        entity.reason,
        entity.requestedAt);
  }
}
