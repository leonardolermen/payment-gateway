package com.gateway.app.api.admin;

import com.gateway.app.api.admin.dto.ApiKeyEnvironmentRequest;
import com.gateway.app.api.admin.dto.ApiKeyIssuedResponse;
import com.gateway.app.api.admin.dto.MerchantRequest;
import com.gateway.app.api.admin.dto.MerchantResponse;
import com.gateway.app.api.admin.dto.NotificationKeyRequest;
import com.gateway.app.api.admin.dto.ProviderCredentialRequest;
import com.gateway.app.inbound.mtls.WebhookMtlsProperties;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.security.Secret;
import com.gateway.merchants.apikey.ApiKeyService;
import com.gateway.merchants.credential.Provider;
import com.gateway.merchants.credential.ProviderCredentialService;
import com.gateway.merchants.merchant.Merchant;
import com.gateway.merchants.merchant.MerchantService;
import com.gateway.merchants.notification.InboundNotificationKeyService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/v1/admin/merchants")
public class MerchantsAdminController {
  private final MerchantService merchants;
  private final ApiKeyService apiKeys;
  private final ProviderCredentialService credentials;
  private final ObjectMapper objectMapper;
  private final WebhookMtlsProperties mtls;
  private final InboundNotificationKeyService notificationKeys;

  public MerchantsAdminController(
      MerchantService merchants,
      ApiKeyService apiKeys,
      ProviderCredentialService credentials,
      ObjectMapper objectMapper,
      WebhookMtlsProperties mtls,
      InboundNotificationKeyService notificationKeys) {
    this.merchants = merchants;
    this.apiKeys = apiKeys;
    this.credentials = credentials;
    this.objectMapper = objectMapper;
    this.mtls = mtls;
    this.notificationKeys = notificationKeys;
  }

  private MerchantResponse response(Merchant merchant) {
    return MerchantResponse.from(merchant, mtls.inboundWebhookUrl(merchant.inboundWebhookToken()));
  }

  @PostMapping
  public ResponseEntity<MerchantResponse> create(@RequestBody MerchantRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(response(merchants.create(request.name())));
  }

  @GetMapping
  public List<MerchantResponse> list() {
    return merchants.list().stream().map(this::response).toList();
  }

  @GetMapping("/{id}")
  public MerchantResponse get(@PathVariable String id) {
    return response(merchants.get(new MerchantId(id)));
  }

  @PostMapping("/{id}/suspend")
  public MerchantResponse suspend(@PathVariable String id) {
    return response(merchants.suspend(new MerchantId(id)));
  }

  @PostMapping("/{id}/activate")
  public MerchantResponse activate(@PathVariable String id) {
    return response(merchants.activate(new MerchantId(id)));
  }

  @PostMapping("/{id}/api-keys")
  public ResponseEntity<ApiKeyIssuedResponse> issueApiKey(
      @PathVariable String id, @RequestBody ApiKeyEnvironmentRequest request) {
    var issued = apiKeys.issue(new MerchantId(id), request.environment());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiKeyIssuedResponse.from(issued));
  }

  @PostMapping("/{id}/api-keys/rotate")
  public ResponseEntity<ApiKeyIssuedResponse> rotateApiKey(
      @PathVariable String id, @RequestBody ApiKeyEnvironmentRequest request) {
    var issued = apiKeys.rotate(new MerchantId(id), request.environment());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiKeyIssuedResponse.from(issued));
  }

  @DeleteMapping("/{id}/api-keys/{keyId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revokeApiKey(@PathVariable String id, @PathVariable String keyId) {
    apiKeys.revoke(new MerchantId(id), keyId);
  }

  @PutMapping("/{id}/providers/{provider}/credentials")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void storeProviderCredential(
      @PathVariable String id,
      @PathVariable Provider provider,
      @RequestBody ProviderCredentialRequest request) {
    byte[] payload = objectMapper.writeValueAsBytes(request.payload());
    credentials.store(new MerchantId(id), provider, request.environment(), payload);
  }

  /**
   * The header value the merchant configures at the acquirer's portal. 1500 is the Cielo's own
   * limit for a header value (docs/webhook, "VALUE — limitado a 1500 caracteres").
   */
  @PutMapping("/{id}/providers/{provider}/notification-key")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void storeNotificationKey(
      @PathVariable String id,
      @PathVariable Provider provider,
      @RequestBody NotificationKeyRequest request) {
    if (request.key() == null || request.key().isBlank() || request.key().length() > 1500) {
      throw new IllegalArgumentException("key must be 1 to 1500 characters");
    }

    merchants.get(new MerchantId(id));
    notificationKeys.set(new MerchantId(id), provider.name(), Secret.of(request.key()));
  }
}
