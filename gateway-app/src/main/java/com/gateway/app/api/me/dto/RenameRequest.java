package com.gateway.app.api.me.dto;

import com.gateway.app.api.support.Required;

/** PATCH /v1/me. */
public record RenameRequest(String name) {
  private static final int MAX = 120;

  public void validate() {
    Required.field(name, "name");

    if (name.trim().length() > MAX) {
      throw new IllegalArgumentException("name is required (up to 120 characters)");
    }
  }
}
