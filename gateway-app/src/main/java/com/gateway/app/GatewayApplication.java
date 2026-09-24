package com.gateway.app;

import com.gateway.merchants.MerchantsConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The gateway's single deployable. Scans only {@code com.gateway.app}: business modules come in
 * through explicit {@code @Import} of their configuration classes, one by one, so what each module
 * exposes stays readable. The webhook-delivery library registers itself via its autoconfiguration.
 */
@SpringBootApplication
@EnableScheduling
@Import(MerchantsConfiguration.class)
public class GatewayApplication {
  public static void main(String[] args) {
    SpringApplication.run(GatewayApplication.class, args);
  }
}
