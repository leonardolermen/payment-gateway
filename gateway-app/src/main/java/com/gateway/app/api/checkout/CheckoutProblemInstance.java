package com.gateway.app.api.checkout;

import com.gateway.app.security.RequestPath;
import java.net.URI;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Spring fills a problem's {@code instance} with the request path, and on these routes the path is
 * the token: the first CheckoutApiIntegrationTest run showed a 404 for an unknown token echoing it
 * back in full. An error body ends up in browser consoles, support tickets and screenshots, so the
 * instance is cut to the route without the token. Runs after Spring sets the instance, which is why
 * this is an advice and not a field set in {@code ErrorHandler}.
 */
@RestControllerAdvice
class CheckoutProblemInstance implements ResponseBodyAdvice<Object> {
  private static final String PREFIX = "/v1/checkout/";
  private static final URI REDACTED = URI.create("/v1/checkout");

  @Override
  public boolean supports(
      MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
    return true;
  }

  @Override
  public Object beforeBodyWrite(
      Object body,
      MethodParameter returnType,
      MediaType selectedContentType,
      Class<? extends HttpMessageConverter<?>> selectedConverterType,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    if (body instanceof ProblemDetail problem && isCheckout(request)) {
      problem.setInstance(REDACTED);
    }

    return body;
  }

  private static boolean isCheckout(ServerHttpRequest request) {
    if (request instanceof ServletServerHttpRequest servlet) {
      return RequestPath.of(servlet.getServletRequest()).normalized().startsWith(PREFIX);
    }

    return request.getURI().getPath().startsWith(PREFIX);
  }
}
