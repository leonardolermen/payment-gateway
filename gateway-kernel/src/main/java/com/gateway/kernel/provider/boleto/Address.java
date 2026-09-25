package com.gateway.kernel.provider.boleto;

/** {@code state} is the two-letter UF, {@code zip} the 8-digit CEP: the bank's boleto layout has no room for anything else. */
public record Address(String street, String district, String city, String state, String zip) {}
