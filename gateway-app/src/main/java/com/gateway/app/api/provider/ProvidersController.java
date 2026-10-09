package com.gateway.app.api.provider;

import com.gateway.app.api.provider.dto.CredentialsRequest;
import com.gateway.app.api.provider.dto.NotificationKeyRequest;
import com.gateway.app.api.provider.dto.ProviderStatusResponse;
import com.gateway.app.api.provider.dto.ProvidersResponse;
import com.gateway.app.inbound.mtls.WebhookMtlsProperties;
import com.gateway.app.security.MerchantContext;
import com.gateway.merchants.credential.Provider;
import com.gateway.merchants.merchant.Merchant;
import com.gateway.merchants.merchant.MerchantService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * The owner's own provider configuration (spec 2026-10-09-provedores-self-service, section 2).
 * Session and OWNER only, enforced by {@code RoleRoutes}; the environment is the session's {@code
 * X-Environment}, never the body. {@code {provider}} is the enum name, so an unknown one is a 400
 * from the path conversion before any handler runs.
 */
@RestController
@RequestMapping("/v1/merchant/providers")
public class ProvidersController {
  private final MerchantProviderService providers;
  private final MerchantService merchants;
  private final WebhookMtlsProperties mtls;
  private final ObjectMapper objectMapper;

  public ProvidersController(
      MerchantProviderService providers,
      MerchantService merchants,
      WebhookMtlsProperties mtls,
      ObjectMapper objectMapper) {
    this.providers = providers;
    this.merchants = merchants;
    this.mtls = mtls;
    this.objectMapper = objectMapper;
  }

  @GetMapping
  public ProvidersResponse status() {
    MerchantContext.Current caller = MerchantContext.current();
    Merchant merchant = merchants.get(caller.merchantId());

    List<ProviderStatusResponse> statuses =
        providers.status(caller.merchantId(), caller.environment()).stream()
            .map(ProviderStatusResponse::from)
            .toList();

    return new ProvidersResponse(
        caller.environment(), mtls.inboundWebhookUrl(merchant.inboundWebhookToken()), statuses);
  }

  @PutMapping("/{provider}/credentials")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void storeCredentials(
      @PathVariable Provider provider, @RequestBody CredentialsRequest request) {
    if (request.payload() == null) {
      throw new IllegalArgumentException("payload is required");
    }

    MerchantContext.Current caller = MerchantContext.current();
    byte[] submitted = objectMapper.writeValueAsBytes(request.payload());

    providers.store(caller.merchantId(), provider, caller.environment(), submitted);
  }

  @PutMapping("/{provider}/notification-key")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void storeNotificationKey(
      @PathVariable Provider provider, @RequestBody NotificationKeyRequest request) {
    MerchantContext.Current caller = MerchantContext.current();

    providers.setNotificationKey(caller.merchantId(), provider, request.key());
  }
}
