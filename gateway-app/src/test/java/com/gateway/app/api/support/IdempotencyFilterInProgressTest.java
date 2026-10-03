package com.gateway.app.api.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gateway.app.security.MerchantContext;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.apikey.ApiKeyEnvironment;
import com.gateway.payments.idempotency.IdempotencyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The 409 IN_PROGRESS text is contract. It once pointed at GET /v1/payments?reference=…, which says
 * nothing to a client whose key belongs to an order, customer or subscription POST.
 */
class IdempotencyFilterInProgressTest {

  @AfterEach
  void clearRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void aKeyStillBeingProcessedAnswersWithoutNamingARoute() throws Exception {
    IdempotencyService idempotency = mock(IdempotencyService.class);
    when(idempotency.begin(any(), any(), any()))
        .thenReturn(new IdempotencyService.Outcome.InProgress());
    IdempotencyFilter filter = new IdempotencyFilter(idempotency, "key", "");
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/orders");
    request.addHeader("Idempotency-Key", "k-1");
    request.setContent("{}".getBytes());
    request.setAttribute(
        MerchantContext.class.getName(),
        new MerchantContext.Current(MerchantId.next(), ApiKeyEnvironment.TEST, "key-1"));
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(response.getContentAsString())
        .contains("\"title\":\"IN_PROGRESS\"")
        .contains(
            "\"detail\":\"a request with this Idempotency-Key is still being processed;"
                + " retry in a moment\"");
  }
}
