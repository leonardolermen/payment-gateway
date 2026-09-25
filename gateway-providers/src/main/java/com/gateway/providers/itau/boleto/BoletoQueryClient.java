package com.gateway.providers.itau.boleto;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.providers.itau.auth.ItauCredentials;
import com.gateway.providers.itau.auth.ItauEndpoints;
import com.gateway.providers.itau.auth.ItauTokenClient;
import com.gateway.providers.itau.boleto.dto.BoletoQueryItem;
import com.gateway.providers.itau.boleto.dto.BoletoQueryResponse;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/** Itaú "Consulta de detalhe" (boletoscash v2): the only way to learn that a Bolecode was paid by barcode (no boleto webhook in scope, spec §8). */
class BoletoQueryClient {
  private final BoletoHttp http;
  private final ObjectMapper mapper = new ObjectMapper();

  BoletoQueryClient(ItauTokenClient tokens, ItauEndpoints endpoints, KeyStore trustStore, Duration readTimeout) {
    this.http = new BoletoHttp(tokens, endpoints, trustStore, readTimeout);
  }

  /**
   * Empty for a 404 and for a list that does not carry the number — the bank filters by
   * nosso_numero, but a static mock (the sandbox) answers its example whatever we ask, and
   * treating that as "our boleto" would complete the wrong payment.
   */
  Optional<BoletoQueryItem> find(ItauCredentials c, String nossoNumero) {
    String q = "?id_beneficiario=" + BoletoHttp.enc(c.beneficiaryId()) + "&codigo_carteira=" + BoletoHttp.enc(c.walletCode()) + "&nosso_numero=" + BoletoHttp.enc(nossoNumero);
    HttpResponse<String> res = http.send(c, http.request("/boletos" + q).GET());
    int status = res.statusCode();
    if (status == 404) return Optional.empty();
    if (status != 200) throw BoletoErrors.from(status, res.body());
    BoletoQueryResponse body;
    try {
      body = mapper.readValue(res.body(), BoletoQueryResponse.class);
    } catch (RuntimeException e) {
      throw new ProviderException(ProviderException.Code.UNKNOWN, "unreadable provider response", e);
    }
    if (body == null || body.data() == null) return Optional.empty();
    return body.data().stream().filter(item -> item.individual(nossoNumero).isPresent()).findFirst();
  }
}
