package com.gateway.app.security;

/**
 * No authenticated merchant on the request. Its own type so {@code ErrorHandler} maps exactly this to
 * 401: the previous catch-all on {@code IllegalStateException} turned any unrelated bug into a 401
 * and echoed its message to the caller.
 */
public class UnauthenticatedException extends RuntimeException {
  public UnauthenticatedException(String message) {
    super(message);
  }
}
