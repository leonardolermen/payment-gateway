package com.gateway.kernel.provider.boleto;

/** {@code document} is CPF (11 digits) or CNPJ (14 digits); a registered boleto cannot be issued without a payer. */
public record Payer(String name, String document, Address address) {}
