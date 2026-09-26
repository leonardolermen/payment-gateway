package com.gateway.providers.itau.boleto;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.providers.itau.auth.ItauCredentials;
import com.gateway.providers.itau.auth.ItauEndpoints;
import com.gateway.providers.itau.auth.ItauTokenClient;
import com.gateway.providers.itau.boleto.dto.BoletoPixRequest;
import com.gateway.providers.itau.boleto.dto.BoletoPixResponse;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import tools.jackson.databind.ObjectMapper;

/**
 * Itaú "Boleto com Pix" v1, one environment per instance. No retry: the nosso número is ours, so
 * the caller (payments) can ask the query API whether the boleto exists before deciding.
 */
class BoletoPixApiClient {
  private final BoletoHttp http;
  private final ObjectMapper mapper = new ObjectMapper();

  BoletoPixApiClient(
      ItauTokenClient tokens, ItauEndpoints endpoints, KeyStore trustStore, Duration readTimeout) {
    this.http = new BoletoHttp(tokens, endpoints, trustStore, readTimeout);
  }

  /**
   * 200 (and 201) is the issued boleto; 202 "operação em andamento" becomes a TIMEOUT in
   * BoletoErrors: the bank has not decided.
   */
  BoletoPixResponse post(ItauCredentials c, BoletoPixRequest body) {
    HttpRequest.Builder builder =
        http.request("/boletos-pix")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    mapper.writeValueAsString(body), StandardCharsets.UTF_8));
    HttpResponse<String> res = http.send(c, builder);
    int status = res.statusCode();
    if (status == 200 || status == 201) {
      try {
        return mapper.readValue(res.body(), BoletoPixResponse.class);
      } catch (RuntimeException e) {
        throw new ProviderException(
            ProviderException.Code.UNKNOWN, "unreadable provider response", e);
      }
    }
    throw BoletoErrors.from(status, res.body());
  }
}
