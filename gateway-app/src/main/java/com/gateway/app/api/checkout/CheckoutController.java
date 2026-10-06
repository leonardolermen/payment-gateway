package com.gateway.app.api.checkout;

import com.gateway.app.api.checkout.dto.CheckoutPaymentResponse;
import com.gateway.app.api.checkout.dto.CheckoutResponse;
import com.gateway.app.api.order.dto.OrderAttemptRequest;
import com.gateway.billing.order.checkout.CheckoutService;
import com.gateway.billing.order.checkout.CheckoutView;
import com.gateway.merchants.credential.ProviderCredentialService;
import com.gateway.merchants.merchant.MerchantService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The payer's API: no key, the token in the path is the whole authorization. Responses are a strict
 * subset of the merchant's — never the payer's own data back to the browser, never provider ids.
 * Not under IdempotencyFilter: there is no merchant scope for a key, and the one-active-attempt
 * rule already stops a double charge.
 */
@RestController
@RequestMapping("/v1/checkout/{token}")
public class CheckoutController {
  private final CheckoutService checkout;
  private final MerchantService merchants;
  private final ProviderCredentialService credentials;

  public CheckoutController(
      CheckoutService checkout, MerchantService merchants, ProviderCredentialService credentials) {
    this.checkout = checkout;
    this.merchants = merchants;
    this.credentials = credentials;
  }

  @GetMapping
  public CheckoutResponse get(@PathVariable String token) {
    CheckoutView view = checkout.get(token);
    String merchantName = merchants.get(view.order().merchantId()).name();

    return CheckoutResponse.from(view, merchantName, Methods.available(credentials, view.order()));
  }

  @PostMapping("/payments")
  public ResponseEntity<CheckoutPaymentResponse> attempt(
      @PathVariable String token, @RequestBody OrderAttemptRequest request) {
    CheckoutPaymentResponse created =
        CheckoutPaymentResponse.from(checkout.attempt(token, request.toAttempt()));

    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @GetMapping("/payments/{id}")
  public CheckoutPaymentResponse payment(@PathVariable String token, @PathVariable String id) {
    return CheckoutPaymentResponse.from(checkout.payment(token, id));
  }

  @PostMapping("/payments/{id}/cancel")
  public CheckoutPaymentResponse cancel(@PathVariable String token, @PathVariable String id) {
    return CheckoutPaymentResponse.from(checkout.cancelAttempt(token, id));
  }
}
