package com.gateway.app.inbound;

import com.gateway.app.security.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;

/** The body of a provider's notification, read with a ceiling. */
public final class InboundBody {
  private InboundBody() {}

  /**
   * Reads at most {@code maxBytes}, or writes the 413 and returns empty. Through a bounded stream,
   * not @RequestBody byte[]: MtlsPortFilter refuses an oversized Content-Length on its port, but a
   * chunked body declares none, and an unbounded read would buffer it all.
   */
  public static Optional<byte[]> read(
      HttpServletRequest request, HttpServletResponse response, int maxBytes) throws IOException {
    byte[] body = request.getInputStream().readNBytes(maxBytes + 1);
    if (body.length > maxBytes) {
      Problems.write(
          response, 413, "PAYLOAD_TOO_LARGE", "webhook body exceeds " + maxBytes + " bytes");
      return Optional.empty();
    }

    return Optional.of(body);
  }
}
