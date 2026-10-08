package com.gateway.app.api.auth;

import com.gateway.kernel.security.Secret;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.ResponseCookie;

/**
 * The refresh token's cookie. HttpOnly so no script reads it; SameSite=None (and so Secure) because
 * the panel is served from another origin; Path=/v1/auth so it travels only to refresh and logout.
 */
public final class SessionCookies {
  static final String NAME = "gw_refresh";

  private SessionCookies() {}

  public static ResponseCookie refresh(Secret token, Duration maxAge) {
    return base(token.reveal()).maxAge(maxAge).build();
  }

  public static ResponseCookie cleared() {
    return base("").maxAge(Duration.ZERO).build();
  }

  public static Optional<String> read(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return Optional.empty();
    }

    return Arrays.stream(cookies)
        .filter(cookie -> NAME.equals(cookie.getName()))
        .map(Cookie::getValue)
        .filter(value -> !value.isBlank())
        .findFirst();
  }

  private static ResponseCookie.ResponseCookieBuilder base(String value) {
    return ResponseCookie.from(NAME, value)
        .httpOnly(true)
        .secure(true)
        .sameSite("None")
        .path("/v1/auth");
  }
}
