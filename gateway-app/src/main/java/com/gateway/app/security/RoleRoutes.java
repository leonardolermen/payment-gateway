package com.gateway.app.security;

import com.gateway.merchants.user.Role;
import java.util.List;
import java.util.Optional;

/**
 * The least role a signed-in user needs for a merchant route. Reading is for everyone; writing
 * money-moving resources needs FINANCE; settings, integrations and the team need OWNER. API keys
 * are not gated here: a key is the merchant's own server, with the whole merchant's reach.
 *
 * <p>Prefixes match whole path segments: a bare {@code startsWith("/v1/me")} also matched {@code
 * /v1/merchant} and {@code /v1/metrics}, which made a merchant write READONLY and closed {@code GET
 * /v1/merchant} to API keys.
 */
final class RoleRoutes {
  private static final String PROVIDER_SETTINGS = "/v1/merchant/providers";

  private static final List<String> OWNER_PREFIXES =
      List.of("/v1/webhooks/endpoints", "/v1/merchant", "/v1/installment-settings", "/v1/invites");

  private RoleRoutes() {}

  /** {@code path} is {@link RequestPath#normalized()}; empty means not a merchant route. */
  static Optional<Role> required(String method, String path) {
    if (!path.startsWith("/v1/")
        || ProtectedRoutes.isAdmin(path)
        || ProtectedRoutes.isCheckout(path)
        || ProtectedRoutes.isAuth(path)) {
      return Optional.empty();
    }
    // Even to read: the page shows client ids, the Pix key and the certificate — the relationship
    // with the bank is the owner's, not something finance or a reader needs to see.
    if (under(path, PROVIDER_SETTINGS)) {
      return Optional.of(Role.OWNER);
    }
    if (method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS")) {
      return Optional.of(Role.READONLY);
    }
    // The user's own account (name, password, sessions): every role manages its own.
    if (under(path, "/v1/me")) {
      return Optional.of(Role.READONLY);
    }
    // Deleting a customer erases a payer's history, so it is an owner's call, not finance's.
    if (method.equals("DELETE") && path.startsWith("/v1/customers/")) {
      return Optional.of(Role.OWNER);
    }
    if (OWNER_PREFIXES.stream().anyMatch(prefix -> under(path, prefix))) {
      return Optional.of(Role.OWNER);
    }

    return Optional.of(Role.FINANCE);
  }

  /**
   * Routes about a person, which an API key has no person to answer for — and the provider
   * settings, because a leaked key must not be able to swap the bank credentials.
   */
  static boolean userOnly(String path) {
    return under(path, "/v1/me")
        || under(path, "/v1/merchant/users")
        || under(path, "/v1/invites")
        || under(path, PROVIDER_SETTINGS);
  }

  private static boolean under(String path, String prefix) {
    return path.equals(prefix) || path.startsWith(prefix + "/");
  }
}
