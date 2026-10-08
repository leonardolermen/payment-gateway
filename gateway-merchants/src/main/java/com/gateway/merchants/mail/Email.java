package com.gateway.merchants.mail;

/** One message, already rendered: plain text for every client, HTML for the ones that show it. */
public record Email(String to, String subject, String text, String html) {}
