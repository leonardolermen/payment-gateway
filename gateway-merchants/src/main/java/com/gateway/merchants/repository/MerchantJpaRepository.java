package com.gateway.merchants.repository;

import org.springframework.data.jpa.repository.JpaRepository;

interface MerchantJpaRepository extends JpaRepository<MerchantEntity, String> {}
