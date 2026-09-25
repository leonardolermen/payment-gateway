package com.gateway.app.inbound.mtls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.cert.X509Certificate;
import java.util.List;
import javax.security.auth.x500.X500Principal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The allow-list mismatch needs a second certificate from the trusted CA, which TestCertificates does
 * not issue; the filter only reads the servlet attribute Tomcat fills after the handshake, so a
 * mocked certificate exercises the same decision.
 */
class MtlsPortFilterTest {
  static final int PORT = 8443;

  private static MockHttpServletResponse run(List<String> allowed, String presentedSubject) throws Exception {
    MtlsPortFilter filter = new MtlsPortFilter(new WebhookMtlsProperties(PORT, "ks", "", "ts", "", null, 1024, allowed));
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/providers/itau/webhooks/T/pix");
    req.setLocalPort(PORT);
    X509Certificate cert = mock(X509Certificate.class);
    when(cert.getSubjectX500Principal()).thenReturn(new X500Principal(presentedSubject));
    req.setAttribute("jakarta.servlet.request.X509Certificate", new X509Certificate[] {cert});
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(req, res, chain);
    if (chain.getRequest() != null) res.setStatus(202); // reached the controller
    return res;
  }

  @Test
  void listedSubjectPassesEvenWithDifferentSpacingAndCase() throws Exception {
    assertThat(run(List.of("CN=itau-webhook, O=Itau"), "cn=itau-webhook,o=Itau").getStatus()).isEqualTo(202);
  }

  @Test
  void unlistedSubjectIs403() throws Exception {
    MockHttpServletResponse res = run(List.of("CN=itau-webhook"), "CN=someone-else");
    assertThat(res.getStatus()).isEqualTo(403);
    assertThat(res.getContentAsString()).contains("urn:gateway:FORBIDDEN");
  }

  @Test
  void emptyListAcceptsAnyCertificateTheCaSigned() throws Exception {
    assertThat(run(List.of(), "CN=anyone").getStatus()).isEqualTo(202);
  }
}
