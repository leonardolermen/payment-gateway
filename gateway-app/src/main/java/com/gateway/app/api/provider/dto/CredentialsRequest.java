package com.gateway.app.api.provider.dto;

import java.util.Map;

/**
 * The provider's fields as the panel sends them (spec section 2.1). A secret left out keeps the
 * stored value; {@code ""} removes it. Masked in {@code toString} so a log of the request never
 * carries a secret.
 *
 * <p>{@code environment} is accepted and ignored on purpose: the admin body carries one, and a
 * client that copies that shape must not get a 400 from fail-on-unknown — but the environment a
 * credential lands in is the session's {@code X-Environment}, never what the body asks. A TEST body
 * on a LIVE session still meets the LIVE shape rule.
 */
public record CredentialsRequest(Map<String, Object> payload, String environment) {

  @Override
  public String toString() {
    return "CredentialsRequest[***]";
  }
}
