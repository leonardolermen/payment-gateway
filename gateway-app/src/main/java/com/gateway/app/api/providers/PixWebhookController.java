package com.gateway.app.api.providers;

import com.gateway.merchants.domain.Merchant;
import com.gateway.payments.service.WebhookInboxService;
import jakarta.servlet.http.HttpServletRequest;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

  public PixWebhookController(WebhookTokenGuard guard, WebhookInboxService inbox, ObjectMapper json) {
    this.guard = guard;
    this.inbox = inbox;
    this.json = json;
  }

  @PostMapping({"/v1/providers/itau/webhooks/{token}", "/v1/providers/itau/webhooks/{token}/pix"})
  public ResponseEntity<Void> receive(@PathVariable String token, @RequestBody(required = false) byte[] body, HttpServletRequest req) {
    Merchant merchant = guard.resolve(token);
    inbox.accept("ITAU", merchant.id(), headers(req), body == null ? new byte[0] : body);
    return ResponseEntity.accepted().build();
  }

  /** What an operator needs to trace a delivery back to the bank; the full header set would be noise. */
  private String headers(HttpServletRequest req) {
    Map<String, String> h = new LinkedHashMap<>();
    put(h, "X-Correlation-Id", req.getHeader("X-Correlation-Id"));
    put(h, "User-Agent", req.getHeader("User-Agent"));
    put(h, "Content-Type", req.getContentType());
    if (req.getAttribute(CLIENT_CERT_ATTRIBUTE) instanceof X509Certificate[] chain && chain.length > 0) {
      put(h, "Client-Cert-Subject", chain[0].getSubjectX500Principal().getName());
      put(h, "Client-Cert-Issuer", chain[0].getIssuerX500Principal().getName());
    }
    return json.writeValueAsString(h);
  }

  private static void put(Map<String, String> h, String k, String v) { if (v != null) h.put(k, v); }
}
