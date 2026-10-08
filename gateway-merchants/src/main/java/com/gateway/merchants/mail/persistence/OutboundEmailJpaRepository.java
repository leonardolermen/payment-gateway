package com.gateway.merchants.mail.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface OutboundEmailJpaRepository extends JpaRepository<OutboundEmailEntity, String> {}
