package com.gateway.payments.provider.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface ProviderRequestJpaRepository extends JpaRepository<ProviderRequestEntity, String> {}
