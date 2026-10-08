package com.gateway.app.security;

import com.gateway.merchants.user.Role;

/**
 * Who, inside the merchant, is calling: an API key (a server) or a signed-in user of the panel.
 * Sealed so that whatever needs to tell them apart is forced to handle both.
 */
public sealed interface Actor permits Actor.ApiKey, Actor.User {
  /** The key id or the user id: what an audit trail or a rate-limit bucket records. */
  String id();

  record ApiKey(String apiKeyId) implements Actor {
    @Override
    public String id() {
      return apiKeyId;
    }
  }

  record User(String userId, String sessionId, Role role, boolean emailVerified) implements Actor {
    @Override
    public String id() {
      return userId;
    }
  }
}
