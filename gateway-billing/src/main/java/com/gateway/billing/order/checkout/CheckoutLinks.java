package com.gateway.billing.order.checkout;

/**
 * Port: the payer's url for a token. Where the payer-facing front serves its page is the app's
 * configuration; billing only needs the url to hand an invoice's link out once, in its event.
 */
public interface CheckoutLinks {
  String urlFor(CheckoutToken token);
}
