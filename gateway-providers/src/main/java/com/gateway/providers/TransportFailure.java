package com.gateway.providers;

import java.net.URI;

/**
 * The text of an UNAVAILABLE: {@code "... failed: " + e.getMessage()} printed "failed: null" for a
 * refused connection, whose exception has no message. Host only — a path or query can carry a txid
 * or a token, and this text lands in the log and in provider_requests.
 */
public final class TransportFailure {

  private TransportFailure() {}

  public static String describe(String what, URI target, Throwable cause) {
    String host =
        target.getPort() < 0 ? target.getHost() : target.getHost() + ":" + target.getPort();
    String reason =
        cause.getMessage() == null || cause.getMessage().isBlank()
            ? cause.getClass().getSimpleName()
            : cause.getClass().getSimpleName() + ": " + cause.getMessage();

    return what + " to " + host + " failed: " + reason;
  }
}
