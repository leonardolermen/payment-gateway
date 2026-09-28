package com.gateway.kernel.provider.card;

import com.gateway.kernel.party.Document;
import com.gateway.kernel.party.PersonName;

/**
 * Who pays with the card, as the acquirer takes it: the name is required (the Cielo refuses a sale
 * without {@code Customer.Name}, code 105), document and e-mail are optional. Not {@code
 * kernel/party/Payer}: that one requires an address, which a card charge does not have (plan C4).
 */
public record CardCustomer(PersonName name, Document document, String email) {}
