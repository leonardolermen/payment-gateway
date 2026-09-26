package com.gateway.kernel.party;

import com.gateway.kernel.address.Uf;
import com.gateway.kernel.address.ZipCode;

/**
 * Where the payer lives, as the bank's boleto layout needs it: every line present, the state a UF
 * and the zip eight digits. {@link Uf} and {@link ZipCode} carry those two rules, so an Address
 * that exists is one the bank will accept — nothing downstream checks them again.
 */
public record Address(String street, String district, String city, Uf state, ZipCode zip) {}
