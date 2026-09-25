package com.gateway.providers.itau.boleto;

import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.providers.itau.auth.ItauEndpoints;

/** The three Itaú APIs a Bolecode touches, each with its own base and token URL (ItauEndpoints has the values and their sources). */
public record ItauBoletoEndpoints(ItauEndpoints issue, ItauEndpoints query, ItauEndpoints instruction) {
  public static ItauBoletoEndpoints forEnvironment(ProviderEnvironment env) {
    return new ItauBoletoEndpoints(ItauEndpoints.boletoIssue(env), ItauEndpoints.boletoQuery(env), ItauEndpoints.boletoInstruction(env));
  }
}
