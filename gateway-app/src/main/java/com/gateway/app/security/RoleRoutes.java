package com.gateway.app.security;

import com.gateway.merchants.user.Role;
import java.util.List;
import java.util.Optional;

/**
 * The least role a signed-in user needs for a merchant route. Reading is for everyone; writing
 * money-moving resources needs FINANCE; settings, integrations and the team need OWNER. API keys
 * are not gated here: a key is the merchant's own server, with the whole merchant's reach.
 */
final class RoleRoutes {
  private static final List<String> OWNER_PREFIXES =
      List.of(
          "/v1/webhooks/endpoints",
          "/v1/merchant",
          "/v1/providers",
          "/v1/installment-settings",
          "/v1/invites");

  private RoleRoutes() {}

  /** {@code path} is {@link RequestPath#normalized()}; empty means not a merchant route. */
  static Optional<Role> required(String method, String path) {
    if (!path.startsWith("/v1/")
        || ProtectedRoutes.isAdmin(path)
        || ProtectedRoutes.isCheckout(path)
        || ProtectedRoutes.isAuth(path)) {
      return Optional.empty();
    }
    if (method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS")) {
      return Optional.of(Role.READONLY);
    }
    // The user's own account (name, password, sessions): every role manages its own.
    if (path.startsWith("/v1/me")) {
      return Optional.of(Role.READONLY);
    }
    // Deleting a customer erases a payer's history, so it is an owner's call, not finance's.
    if (method.equals("DELETE") && path.startsWith("/v1/customers/")) {
      return Optional.of(Role.OWNER);
    }
    if (OWNER_PREFIXES.stream().anyMatch(path::startsWith)) {
      return Optional.of(Role.OWNER);
    }

    return Optional.of(Role.FINANCE);
  }

  /** Routes about a person, which an API key has no person to answer for. */
  static boolean userOnly(String path) {
    return path.startsWith("/v1/me")
        || path.startsWith("/v1/merchant/users")
        || path.startsWith("/v1/invites");
  }
}
