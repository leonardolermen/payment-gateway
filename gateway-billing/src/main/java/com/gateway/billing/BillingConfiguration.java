package com.gateway.billing;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * What billing exposes, chosen one by one; no component scan, same reasoning as {@code
 * PaymentsConfiguration}. Repositories are {@code @Import}ed as they appear.
 */
@Configuration(proxyBeanMethods = false)
@EntityScan("com.gateway.billing")
@EnableJpaRepositories("com.gateway.billing")
@EnableConfigurationProperties(BillingProperties.class)
public class BillingConfiguration {}
