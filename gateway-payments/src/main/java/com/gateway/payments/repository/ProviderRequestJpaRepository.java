package com.gateway.payments.repository;

import org.springframework.data.jpa.repository.JpaRepository;

interface ProviderRequestJpaRepository extends JpaRepository<ProviderRequestEntity, String> {}
