package com.gateway.providers.cielo.card;

import com.gateway.providers.cielo.CieloErrors;
import com.gateway.providers.cielo.CieloHttp;
import com.gateway.providers.cielo.auth.CieloCredentials;
import com.gateway.providers.cielo.auth.CieloEndpoints;
import com.gateway.providers.cielo.card.dto.CardTokenRequest;
import com.gateway.providers.cielo.card.dto.CardTokenResponse;
import java.net.http.HttpResponse;

/**
 * POST /1/card/ — with the trailing slash, as both endpoint rows of reference/criar-cardtoken write
 * it. The token is bound to the MerchantId that created it (spec §1).
 */
public class CieloCardClient {
  private final CieloHttp http;
  private final CieloEndpoints endpoints;

  public CieloCardClient(CieloHttp http, CieloEndpoints endpoints) {
    this.http = http;
    this.endpoints = endpoints;
  }

  public CardTokenResponse create(CieloCredentials credentials, CardTokenRequest request) {
    HttpResponse<String> response =
        http.send(credentials, http.request(endpoints.api(), "/1/card/").POST(http.json(request)));

    if (response.statusCode() != 201 && response.statusCode() != 200) {
      throw CieloErrors.from(response.statusCode(), response.body());
    }

    return http.read(response, CardTokenResponse.class);
  }
}
