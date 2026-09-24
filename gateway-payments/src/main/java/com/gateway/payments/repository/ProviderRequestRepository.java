package com.gateway.payments.repository;

public interface ProviderRequestRepository {
  void record(String paymentId, String provider, String operation, String request, String response, int status, long latencyMs);
}
