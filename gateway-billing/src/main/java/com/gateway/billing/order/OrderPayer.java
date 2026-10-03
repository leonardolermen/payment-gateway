package com.gateway.billing.order;

import com.gateway.billing.customer.CustomerAddress;
import com.gateway.kernel.party.Document;
import com.gateway.kernel.party.PersonName;

/**
 * The inline payer of an order without a registered customer. The row keeps the document sealed,
 * not hashed: a stored order re-attempting a Bolecode needs the digits back, and a hash cannot give
 * them (spec §4.2). {@code address} is nullable; only a Bolecode requires it.
 */
public record OrderPayer(
    PersonName name, Document document, String email, CustomerAddress address) {}
