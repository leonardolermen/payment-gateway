package com.gateway.app.api.auth.dto;

/** The access token for the browser's memory; the refresh token travels only in the cookie. */
public record SessionResponse(String accessToken, long expiresIn) {
  @Override
  public String toString() {
    return "SessionResponse[***]";
  }
}
