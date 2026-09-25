package com.gateway.app.api.webhook;

import com.barrier.webhookdelivery.domain.WebhookEndpoint;
import com.barrier.webhookdelivery.service.WebhookEndpointService;
import com.gateway.app.api.webhook.dto.EndpointResponse;
import com.gateway.app.api.webhook.dto.EndpointWithSecretResponse;
import com.gateway.app.api.webhook.dto.RegisterEndpointRequest;
import com.gateway.app.security.MerchantContext;
import com.gateway.kernel.errors.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Merchant self-service over webhook-delivery's endpoints. Tenant is always the calling merchant
 * ({@code MerchantContext.current().merchantId()}) — never a path or body parameter — so one
 * merchant can neither register on another's behalf nor address another's endpoint by id.
 */
@RestController
@RequestMapping("/v1/webhooks/endpoints")
public class WebhookEndpointsController {
  private final WebhookEndpointService service;

  public WebhookEndpointsController(WebhookEndpointService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<EndpointWithSecretResponse> register(@RequestBody RegisterEndpointRequest req) {
    WebhookEndpoint e = service.register(tenant(), req.url(), req.events());
    return ResponseEntity.status(HttpStatus.CREATED).body(EndpointWithSecretResponse.from(e));
  }

  @GetMapping
  public List<EndpointResponse> list() {
    return service.listByTenant(tenant()).stream().map(EndpointResponse::from).toList();
  }

  @GetMapping("/{id}")
  public EndpointResponse get(@PathVariable UUID id) {
    return EndpointResponse.from(mine(id));
  }

  @PutMapping("/{id}")
  public EndpointResponse update(@PathVariable UUID id, @RequestBody RegisterEndpointRequest req) {
    mine(id);
    return EndpointResponse.from(service.update(id, req.url(), req.events()).orElseThrow(() -> new NotFoundException("endpoint", id.toString())));
  }

  @PostMapping("/{id}/rotate-secret")
  public EndpointWithSecretResponse rotateSecret(@PathVariable UUID id) {
    mine(id);
    return EndpointWithSecretResponse.from(service.rotateSecret(id).orElseThrow(() -> new NotFoundException("endpoint", id.toString())));
  }

  @DeleteMapping("/{id}")
  public EndpointResponse deactivate(@PathVariable UUID id) {
    mine(id);
    return EndpointResponse.from(service.deactivate(id).orElseThrow(() -> new NotFoundException("endpoint", id.toString())));
  }

  private String tenant() { return MerchantContext.current().merchantId().value(); }

  /** Id belonging to another merchant looks exactly like a missing one: 404, not 403 — the endpoint's existence is not this merchant's to know. */
  private WebhookEndpoint mine(UUID id) {
    return service.find(id).filter(e -> e.tenantId().equals(tenant())).orElseThrow(() -> new NotFoundException("endpoint", id.toString()));
  }
}
