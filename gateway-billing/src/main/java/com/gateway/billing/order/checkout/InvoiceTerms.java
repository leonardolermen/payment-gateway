package com.gateway.billing.order.checkout;

/**
 * What the subscription an invoice bills asks of its checkout.
 *
 * @param planName the plan the invoice is for, so the page can name what the card is saved for
 * @param savesCard true while the subscription waits for this invoice to start it (INCOMPLETE):
 *     only a new card is taken, and it is saved for the next cycles whatever {@code save_card} said
 */
public record InvoiceTerms(String planName, boolean savesCard) {}
