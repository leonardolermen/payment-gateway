package com.gateway.app.inbound.card;

import com.gateway.app.inbound.InboundBody;
import com.gateway.app.inbound.InboundHeaders;
import com.gateway.app.inbound.pix.WebhookTokenGuard;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.merchants.merchant.Merchant;
import com.gateway.merchants.notification.InboundNotificationKeyService;
import com.gateway.payments.inbox.WebhookInboxService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * The Cielo's "Post de Notificação" (spec §8, docs/webhook). No mTLS and no signature exist, so the
 * caller is authenticated by two things together: the merchant's URL token and the fixed header the
 * merchant configured at the Cielo. Either missing or wrong is the same 404 as an unknown token.
 *
 * <p>200, not the 202 of the Itaú path: "A loja deverá retornar como resposta à notificação: HTTP
 * Status Code 200 OK" (plan D13). The body is only stored; the inbox job queries the sale.
 *
 * <p>Named without the acquirer: ArchitectureTest.cieloVocabularyStaysInProviders.
 */
@RestController
public class CardNotificationController {
  static final String KEY_HEADER = "X-Gateway-Notification-Key";
  static final String PROVIDER = "CIELO";

  /** Three fields (docs/webhook); 16 KB is generous and still bounded for a chunked body. */
  private static final int MAX_BODY_BYTES = 16 * 1024;

  private final WebhookTokenGuard guard;
  private final InboundNotificationKeyService keys;
  private final WebhookInboxService inbox;
  private final ObjectMapper json;

  public CardNotificationController(
      WebhookTokenGuard guard,
      InboundNotificationKeyService keys,
      WebhookInboxService inbox,
      ObjectMapper json) {
    this.guard = guard;
    this.keys = keys;
    this.inbox = inbox;
    this.json = json;
  }

  @PostMapping("/v1/providers/cielo/webhooks/{token}")
  public ResponseEntity<Void> receive(
      @PathVariable String token, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    Merchant merchant = guard.resolve(token);
    if (!keys.matches(merchant.id(), PROVIDER, request.getHeader(KEY_HEADER))) {
      throw new NotFoundException("webhook", "unknown");
    }

    Optional<byte[]> body = InboundBody.read(request, response, MAX_BODY_BYTES);
    if (body.isEmpty()) {
      return null;
    }

    inbox.accept(
        PROVIDER,
        merchant.id(),
        json.writeValueAsString(InboundHeaders.traced(request)),
        body.get());
    return ResponseEntity.ok().build();
  }
}
