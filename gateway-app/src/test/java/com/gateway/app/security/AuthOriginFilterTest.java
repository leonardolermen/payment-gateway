package com.gateway.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.app.api.checkout.CheckoutProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthOriginFilterTest {
  private static MockHttpServletResponse call(List<String> origins, String path, String origin)
      throws Exception {
    AuthOriginFilter filter = new AuthOriginFilter(new CheckoutProperties(null, origins, 60, 10));
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    if (origin != null) {
      request.addHeader("Origin", origin);
    }

    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());

    return response;
  }

  @Test
  void aForeignOriginIsRefusedOnTheAuthRoutes() throws Exception {
    MockHttpServletResponse refused =
        call(List.of("http://panel.test"), "/v1/auth/refresh", "http://evil.test");

    assertThat(refused.getStatus()).isEqualTo(403);
    assertThat(refused.getContentAsString()).contains("ORIGIN_NOT_ALLOWED");
  }

  @Test
  void theListedOriginNoOriginAndOtherRoutesPass() throws Exception {
    List<String> origins = List.of("http://panel.test");

    assertThat(call(origins, "/v1/auth/refresh", "http://panel.test").getStatus()).isEqualTo(200);
    assertThat(call(origins, "/v1/auth/refresh", null).getStatus()).isEqualTo(200);
    assertThat(call(origins, "/v1/orders", "http://evil.test").getStatus()).isEqualTo(200);
  }

  @Test
  void noConfiguredOriginsMeansNoCheck() throws Exception {
    assertThat(call(List.of(), "/v1/auth/login", "http://evil.test").getStatus()).isEqualTo(200);
  }
}
