package com.gateway.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpTest {
  @Test
  void withoutAForwardedHeaderItIsTheRemoteAddress() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("198.51.100.7");

    assertThat(ClientIp.of(request).value()).isEqualTo("198.51.100.7");
  }

  @Test
  void aForwardedHeaderIsTrustedOnlyBehindAPrivateHop() {
    for (String hop :
        new String[] {
          "10.0.0.5", "172.16.3.4", "172.31.255.1", "192.168.1.9", "127.0.0.1", "::1", "fd00::5"
        }) {
      MockHttpServletRequest request = new MockHttpServletRequest();
      request.setRemoteAddr(hop);
      request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.5");

      assertThat(ClientIp.of(request).value()).as(hop).isEqualTo("203.0.113.9");
    }
  }

  @Test
  void aForwardedHeaderFromAPublicHopIsIgnored() {
    for (String hop : new String[] {"198.51.100.7", "172.32.0.1", "172.15.0.1"}) {
      MockHttpServletRequest request = new MockHttpServletRequest();
      request.setRemoteAddr(hop);
      request.addHeader("X-Forwarded-For", "1.2.3.4");

      assertThat(ClientIp.of(request).value()).as(hop).isEqualTo(hop);
    }
  }

  @Test
  void aBlankForwardedHeaderFallsBackToTheRemoteAddress() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("10.0.0.5");
    request.addHeader("X-Forwarded-For", " ");

    assertThat(ClientIp.of(request).value()).isEqualTo("10.0.0.5");
  }
}
