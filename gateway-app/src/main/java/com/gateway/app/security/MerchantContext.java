package com.gateway.app.security;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.apikey.ApiKeyEnvironment;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Who is calling. A request attribute, not a ThreadLocal of our own: with virtual threads the
 * request is what matters.
 */
public final class MerchantContext {
  public record Current(MerchantId merchantId, ApiKeyEnvironment environment, String apiKeyId) {}

  static final String ATTRIBUTE = MerchantContext.class.getName();

  private MerchantContext() {}

  static void set(HttpServletRequest req, Current current) {
    req.setAttribute(ATTRIBUTE, current);
  }

  public static Current current() {
    var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    Object a = attrs == null ? null : attrs.getRequest().getAttribute(ATTRIBUTE);
    if (a == null) {
      throw new UnauthenticatedException("no authenticated merchant on this request");
    }
    return (Current) a;
  }
}
