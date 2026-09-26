package com.gateway.providers.itau.pix.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * A refund as Itaú returns it. {@code horario.liquidacao} is absent while {@code EM_PROCESSAMENTO}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DevolucaoResponse(
    String id,
    String rtrId,
    String valor,
    String natureza,
    String descricao,
    Horario horario,
    String status,
    String motivo) {
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Horario(Instant solicitacao, Instant liquidacao) {}
}
