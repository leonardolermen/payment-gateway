package com.gateway.kernel.provider.card;

/**
 * The Card On File marker (docs/card-on-file): FIRST on the charge that stores the card, USED on
 * every charge with the stored credential after it.
 */
public enum CardOnFileUsage {
  FIRST,
  USED
}
