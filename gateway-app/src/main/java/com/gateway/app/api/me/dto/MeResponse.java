package com.gateway.app.api.me.dto;

import com.gateway.merchants.merchant.Merchant;
import com.gateway.merchants.user.User;

/** GET /v1/me: who is signed in, for which store, and what is still missing to go live. */
public record MeResponse(Account user, Store merchant, Onboarding onboarding) {
  public record Account(String id, String name, String email, String role, boolean emailVerified) {}

  public record Store(String id, String name) {}

  public record Onboarding(boolean emailVerified, boolean liveEnabled) {}

  // live_enabled is only the e-mail for now; the provider credential joins it in B5.
  public static MeResponse of(User user, Merchant merchant) {
    Account account =
        new Account(
            user.id(),
            user.name(),
            user.email().value(),
            user.role().name(),
            user.isEmailVerified());
    Store store = new Store(merchant.id().value(), merchant.name());
    Onboarding onboarding = new Onboarding(user.isEmailVerified(), user.isEmailVerified());

    return new MeResponse(account, store, onboarding);
  }
}
