package com.gateway.app.security;

import com.gateway.app.api.checkout.CheckoutProperties;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Exact origins or nothing. A wildcard would let any site call the API with a key the browser
 * already holds (a merchant who pasted it into an extension), so the default is CORS off, and each
 * front-end environment is listed by hand. Runs before PathSanityFilter so a preflight is answered
 * here and never reaches the key filters; a real request is only annotated and still goes through
 * them.
 */
@Configuration(proxyBeanMethods = false)
public class CorsFilterConfiguration {
  @Bean
  FilterRegistrationBean<CorsFilter> corsFilter(CheckoutProperties properties) {
    CorsConfiguration cors = new CorsConfiguration();
    cors.setAllowedOrigins(properties.corsOrigins());
    cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
    cors.setAllowedHeaders(List.of("Content-Type", "Authorization", "Idempotency-Key"));
    cors.setExposedHeaders(List.of("X-Next-Cursor", "Retry-After"));
    cors.setAllowCredentials(false);
    cors.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    if (!properties.corsOrigins().isEmpty()) {
      source.registerCorsConfiguration("/v1/**", cors);
    }

    FilterRegistrationBean<CorsFilter> registration =
        new FilterRegistrationBean<>(new CorsFilter(source));
    // After MtlsPortFilter (-10), which only gates the mTLS port, and before PathSanityFilter (0).
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
    return registration;
  }
}
