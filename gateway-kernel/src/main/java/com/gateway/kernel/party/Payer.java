package com.gateway.kernel.party;

/**
 * Who pays. A registered boleto cannot be issued without one (issue OpenAPI: pessoa and endereco are
 * required, and every address line with them), and every component validates its own shape — so a
 * Payer that exists is a Payer the bank will accept.
 *
 * <p>In {@code kernel/party} rather than under {@code provider/boleto}: the payer is not boleto
 * vocabulary, it is the vocabulary of whoever pays.
 */
public record Payer(PersonName name, Document document, Address address) {}
