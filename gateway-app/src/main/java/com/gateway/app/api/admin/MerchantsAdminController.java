package com.gateway.app.api.admin;

import com.gateway.app.api.admin.dto.ApiKeyEnvironmentRequest;
import com.gateway.app.api.admin.dto.ApiKeyIssuedResponse;
import com.gateway.app.api.admin.dto.MerchantRequest;
import com.gateway.app.api.admin.dto.MerchantResponse;
import com.gateway.app.api.admin.dto.ProviderCredentialRequest;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.domain.Provider;
import com.gateway.merchants.service.ApiKeyService;
import com.gateway.merchants.service.MerchantService;
import com.gateway.merchants.service.ProviderCredentialService;
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

  public MerchantsAdminController(MerchantService merchants, ApiKeyService apiKeys, ProviderCredentialService credentials, ObjectMapper objectMapper) {
    this.merchants = merchants;
    this.apiKeys = apiKeys;
    this.credentials = credentials;
    this.objectMapper = objectMapper;
  }

  @PostMapping
  public ResponseEntity<MerchantResponse> create(@RequestBody MerchantRequest req) {
    return ResponseEntity.status(HttpStatus.CREATED).body(MerchantResponse.from(merchants.create(req.name())));
  }

  @GetMapping
  public List<MerchantResponse> list() {
    return merchants.list().stream().map(MerchantResponse::from).toList();
  }

  @GetMapping("/{id}")
  public MerchantResponse get(@PathVariable String id) {
    return MerchantResponse.from(merchants.get(new MerchantId(id)));
  }

  @PostMapping("/{id}/suspend")
  public MerchantResponse suspend(@PathVariable String id) {
    return MerchantResponse.from(merchants.suspend(new MerchantId(id)));
  }

  @PostMapping("/{id}/activate")
  public MerchantResponse activate(@PathVariable String id) {
    return MerchantResponse.from(merchants.activate(new MerchantId(id)));
  }

  @PostMapping("/{id}/api-keys")
  public ResponseEntity<ApiKeyIssuedResponse> issueApiKey(@PathVariable String id, @RequestBody ApiKeyEnvironmentRequest req) {
    var issued = apiKeys.issue(new MerchantId(id), req.environment());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiKeyIssuedResponse.from(issued));
  }

  @PostMapping("/{id}/api-keys/rotate")
  public ResponseEntity<ApiKeyIssuedResponse> rotateApiKey(@PathVariable String id, @RequestBody ApiKeyEnvironmentRequest req) {
    var issued = apiKeys.rotate(new MerchantId(id), req.environment());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiKeyIssuedResponse.from(issued));
  }

  @DeleteMapping("/{id}/api-keys/{keyId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revokeApiKey(@PathVariable String id, @PathVariable String keyId) {
    apiKeys.revoke(new MerchantId(id), keyId);
  }

  @PutMapping("/{id}/providers/{provider}/credentials")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void storeProviderCredential(@PathVariable String id, @PathVariable Provider provider, @RequestBody ProviderCredentialRequest req) {
    byte[] payload = objectMapper.writeValueAsBytes(req.payload());
    credentials.store(new MerchantId(id), provider, req.environment(), payload);
  }
}
