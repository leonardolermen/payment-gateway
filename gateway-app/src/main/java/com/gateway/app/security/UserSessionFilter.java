package com.gateway.app.security;

import com.gateway.merchants.apikey.ApiKeyEnvironment;
import com.gateway.merchants.merchant.Merchant;
import com.gateway.merchants.merchant.MerchantService;
import com.gateway.merchants.session.Session;
import com.gateway.merchants.session.SessionService;
import com.gateway.merchants.user.Role;
import com.gateway.merchants.user.User;
import com.gateway.merchants.user.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code Authorization: Bearer gs_…} (a panel session) on the same routes as {@link
 * ApiKeyAuthFilter}, one order earlier: it sets {@link MerchantContext} and the key filter then
 * steps aside. Unlike a key, a session carries no environment, so it comes from {@code
 * X-Environment}, and a user has a role, so the route's least role is enforced here.
 */
@Component
@Order(19)
public class UserSessionFilter extends OncePerRequestFilter {
  private final SessionService sessions;
  private final UserService users;
  private final MerchantService merchants;

  public UserSessionFilter(SessionService sessions, UserService users, MerchantService merchants) {
    this.sessions = sessions;
    this.users = users;
    this.merchants = merchants;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String authorization = request.getHeader("Authorization");
    return !ProtectedRoutes.requiresApiKey(RequestPath.of(request).normalized())
        || authorization == null
        || !authorization.startsWith("Bearer gs_");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = RequestPath.of(request).normalized();

    Optional<Session> session =
        sessions.authenticate(request.getHeader("Authorization").substring(7).trim());
    if (session.isEmpty()) {
      Problems.write(response, 401, "SESSION_EXPIRED", "sign in again");
      return;
    }

    User user = users.get(session.get().userId());
    Merchant merchant = merchants.get(user.merchantId());
    if (!merchant.isActive()) {
      Problems.write(response, 401, "SESSION_EXPIRED", "merchant is suspended");
      return;
    }

    ApiKeyEnvironment environment = environmentOf(request.getHeader("X-Environment"));
    if (environment == ApiKeyEnvironment.LIVE && !user.isEmailVerified()) {
      Problems.write(response, 403, "EMAIL_NOT_VERIFIED", "confirm your e-mail to use LIVE");
      return;
    }

    Optional<Role> required = RoleRoutes.required(request.getMethod(), path);
    if (required.isPresent() && !user.role().atLeast(required.get())) {
      Problems.writeWithExtra(
          response,
          403,
          "FORBIDDEN_FOR_ROLE",
          "this action needs the " + required.get() + " role",
          "required_role",
          required.get().name());
      return;
    }

    Actor.User actor =
        new Actor.User(user.id(), session.get().id(), user.role(), user.isEmailVerified());
    MerchantContext.set(request, new MerchantContext.Current(merchant.id(), environment, actor));

    chain.doFilter(request, response);
  }

  // Default TEST; anything that is not exactly LIVE stays TEST, never a 400: a wrong header must
  // not
  // widen what the caller reaches.
  private static ApiKeyEnvironment environmentOf(String header) {
    return "LIVE".equals(header) ? ApiKeyEnvironment.LIVE : ApiKeyEnvironment.TEST;
  }
}
