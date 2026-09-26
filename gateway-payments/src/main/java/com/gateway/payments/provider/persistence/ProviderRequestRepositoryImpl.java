package com.gateway.payments.provider.persistence;

import com.gateway.kernel.ids.Ulid;
import java.time.Instant;
import org.springframework.stereotype.Repository;

@Repository
public class ProviderRequestRepositoryImpl implements ProviderRequestRepository {
  private final ProviderRequestJpaRepository jpa;

  public ProviderRequestRepositoryImpl(ProviderRequestJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public void record(
      String paymentId,
      String provider,
      String operation,
      String request,
      String response,
      int status,
      long latencyMs) {
    ProviderRequestEntity e = new ProviderRequestEntity();
    e.id = Ulid.next();
    e.paymentId = paymentId;
    e.provider = provider;
    e.operation = operation;
    e.request = request;
    e.response = response;
    e.status = status;
    e.latencyMs = latencyMs;
    e.createdAt = Instant.now();
    jpa.save(e);
  }
}
