package com.gateway.providers.itau.boleto;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.providers.itau.auth.ItauCredentials;
import com.gateway.providers.itau.auth.ItauEndpoints;
import com.gateway.providers.itau.auth.ItauTokenClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.time.Duration;

/**
 * Itaú "Emissão e Instrução" (cash_management v2), only the baixa. It authenticates at its own STS
 * URL ({@code ItauEndpoints.CASH_MANAGEMENT_TOKEN_URL}), which is why it gets its own endpoints.
 */
class BoletoInstructionClient {
  private final BoletoHttp http;

  BoletoInstructionClient(ItauTokenClient tokens, ItauEndpoints endpoints, KeyStore trustStore, Duration readTimeout) {
    this.http = new BoletoHttp(tokens, endpoints, trustStore, readTimeout);
  }

  /**
   * 200 (the mainframe's {@code Aguardando aprovação} answer), 202 and 204 are all "done"; the
   * boleto is invalid from the bank's side either way. A 422 that talks about pago/liquidado is a
   * CONFLICT the caller turns into "already paid" after asking the query API; the 422 has no schema
   * and no code, so the text is all there is.
   */
  void baixa(ItauCredentials c, String idBoleto) {
    HttpRequest.Builder b = http.request("/boletos/" + BoletoHttp.seg(idBoleto) + "/baixa").method("PATCH", HttpRequest.BodyPublishers.noBody());
    HttpResponse<String> res = http.send(c, b);
    int status = res.statusCode();
    if (status == 200 || status == 202 || status == 204) {
      return;
    }
    if (status == 422 && BoletoErrors.mentionsAlreadyPaid(res.body())) {
      ProviderException declined = BoletoErrors.from(status, res.body());
      throw new ProviderException(ProviderException.Code.CONFLICT, status, declined.providerType(), declined.getMessage());
    }
    throw BoletoErrors.from(status, res.body());
  }
}
