package com.gateway.kernel.provider.card;

/**
 * The gateway's decline vocabulary (spec §5). What a merchant's checkout can act on: ask for
 * another card (EXPIRED, BLOCKED, CANCELED, DO_NOT_HONOR), wait (INSUFFICIENT_FUNDS), retry later
 * (TIMEOUT), or nothing specific (GENERIC). The issuer's own text never reaches the merchant.
 */
public enum CardDeclineCode {
  INSUFFICIENT_FUNDS,
  EXPIRED_CARD,
  BLOCKED_CARD,
  CANCELED_CARD,
  TIMEOUT,
  DO_NOT_HONOR,
  GENERIC
}
