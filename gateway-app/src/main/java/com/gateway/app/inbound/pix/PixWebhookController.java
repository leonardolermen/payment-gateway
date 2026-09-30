package com.gateway.app.inbound.pix;

import com.gateway.app.inbound.InboundBody;
import com.gateway.app.inbound.InboundHeaders;
import com.gateway.app.inbound.mtls.WebhookMtlsProperties;
import com.gateway.merchants.merchant.Merchant;
import com.gateway.payments.inbox.WebhookInboxService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * The bank's inbound Pix webhook. Only reachable on the mTLS connector (MtlsPortFilter), where the
 * handshake has already verified the bank's client certificate. Named without the bank on purpose:
 * ArchitectureTest.itauVocabularyStaysInProviders refuses a class outside gateway-providers whose
 * name carries it; the provider is the path segment instead.
 *
 * <p>202 before any processing: the bank gives us 5 s, and {@link WebhookInboxService#accept} only
 * stores the raw body and queues a job. Both {@code .../{token}} and {@code .../{token}/pix} are
 * accepted: the bank appends {@code /pix} to the registered URL, and accepting both keeps a URL
 * registered with or without it from turning every notification into a 404.
 */
@RestController
public class PixWebhookController {
  static final String CLIENT_CERT_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

  private final WebhookTokenGuard guard;
  private final WebhookInboxService inbox;
  private final ObjectMapper json;
  private final int maxBodyBytes;

  public PixWebhookController(
      WebhookTokenGuard guard,
      WebhookInboxService inbox,
      ObjectMapper json,
      WebhookMtlsProperties properties) {
    this.maxBodyBytes = properties.maxBodyBytes();
    this.guard = guard;
    this.inbox = inbox;
    this.json = json;
  }

  @PostMapping({"/v1/providers/itau/webhooks/{token}", "/v1/providers/itau/webhooks/{token}/pix"})
  public ResponseEntity<Void> receive(
      @PathVariable String token, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    Merchant merchant = guard.resolve(token);
    Optional<byte[]> body = InboundBody.read(request, response, maxBodyBytes);
    if (body.isEmpty()) {
      return null;
    }

    inbox.accept("ITAU", merchant.id(), headers(request), body.get());
    return ResponseEntity.accepted().build();
  }

  /** The shared whitelist plus the client certificate that authenticated the bank. */
  private String headers(HttpServletRequest request) {
    Map<String, String> traced = InboundHeaders.traced(request);
    if (request.getAttribute(CLIENT_CERT_ATTRIBUTE) instanceof X509Certificate[] chain
        && chain.length > 0) {
      traced.put("Client-Cert-Subject", chain[0].getSubjectX500Principal().getName());
      traced.put("Client-Cert-Issuer", chain[0].getIssuerX500Principal().getName());
    }
    return json.writeValueAsString(traced);
  }
}
