package com.gateway.providers.cielo.sale;

import com.gateway.providers.cielo.CieloErrors;
import com.gateway.providers.cielo.CieloHttp;
import com.gateway.providers.cielo.auth.CieloCredentials;
import com.gateway.providers.cielo.auth.CieloEndpoints;
import com.gateway.providers.cielo.sale.dto.SaleRequest;
import com.gateway.providers.cielo.sale.dto.SaleResponse;
import java.net.http.HttpResponse;

/**
 * The Cielo's sales resource for one environment. Writes go to the transactional host, reads to the
 * query host. Capture, void and the two queries join in Task 5.
 */
public class CieloSalesClient {
  private final CieloHttp http;
  private final CieloEndpoints endpoints;

  public CieloSalesClient(CieloHttp http, CieloEndpoints endpoints) {
    this.http = http;
    this.endpoints = endpoints;
  }

  /** 201 for every business answer, a decline included (reference/api-codes). */
  public SaleResponse authorize(CieloCredentials credentials, SaleRequest request) {
    HttpResponse<String> response =
        http.send(credentials, http.request(endpoints.api(), "/1/sales").POST(http.json(request)));

    if (response.statusCode() != 201 && response.statusCode() != 200) {
      throw CieloErrors.from(response.statusCode(), response.body());
    }

    return http.read(response, SaleResponse.class);
  }
}
