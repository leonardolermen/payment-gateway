package com.gateway.app.api.payment.dto;

/** The payer's address as it arrives on the wire: plain strings, checked in the domain. */
public record Address(String street, String district, String city, String state, String zip) {}
