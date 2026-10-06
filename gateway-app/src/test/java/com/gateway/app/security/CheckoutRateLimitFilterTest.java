package com.gateway.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.app.api.checkout.CheckoutProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CheckoutRateLimitFilterTest {
  private final CheckoutRateLimitFilter filter =
      new CheckoutRateLimitFilter(new CheckoutProperties(null, List.of(), 3));

  private MockHttpServletResponse call(String path, String remoteAddress) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setRemoteAddr(remoteAddress);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }

  @Test
  void theFourthRequestFromOneIpIsLimitedAndAnotherIpStillPasses() throws Exception {
    for (int i = 0; i < 3; i++) {
      assertThat(call("/v1/checkout/chk_x", "198.51.100.7").getStatus()).isEqualTo(200);
    }

    MockHttpServletResponse limited = call("/v1/checkout/chk_x", "198.51.100.7");

    assertThat(limited.getStatus()).isEqualTo(429);
    assertThat(limited.getHeader("Retry-After")).isNotNull();
    assertThat(limited.getContentAsString()).contains("RATE_LIMITED").doesNotContain("chk_x");
    assertThat(call("/v1/checkout/chk_x", "198.51.100.8").getStatus()).isEqualTo(200);
  }

  @Test
  void nonCheckoutRoutesAreNotFiltered() throws Exception {
    for (int i = 0; i < 10; i++) {
      assertThat(call("/v1/orders", "198.51.100.7").getStatus()).isEqualTo(200);
    }
  }
}
