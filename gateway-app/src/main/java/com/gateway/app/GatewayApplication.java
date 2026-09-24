package com.gateway.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The gateway's single deployable. Scans only {@code com.gateway.app}: business modules come in
 * through explicit {@code @Import} of their configuration classes, one by one, so what each module
 * exposes stays readable. The webhook-delivery library registers itself via its autoconfiguration.
 */
@SpringBootApplication
@EnableScheduling
public class GatewayApplication {
  public static void main(String[] args) {
    SpringApplication.run(GatewayApplication.class, args);
  }
}
