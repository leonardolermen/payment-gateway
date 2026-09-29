package com.gateway.kernel.provider.card;

/**
 * What pays: the card itself, or the acquirer's token for one stored earlier. Sealed so the request
 * factory at the provider handles both and nothing else.
 */
public sealed interface CardSource permits CardData, CardToken {
  CardBrand brand();
}
