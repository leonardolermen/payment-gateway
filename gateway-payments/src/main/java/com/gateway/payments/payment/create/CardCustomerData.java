package com.gateway.payments.payment.create;

/** The customer exactly as the merchant sent it; {@link CardCustomerFactory} is the door. */
public record CardCustomerData(String name, String document, String email) {}
