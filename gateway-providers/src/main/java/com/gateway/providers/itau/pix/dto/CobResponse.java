package com.gateway.providers.itau.pix.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;

/**
 * A cob as Itaú returns it. ignoreUnknown: the bank sends fields we do not model ({@code loc},
 * {@code devedor}, {@code infoAdicionais}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CobResponse(
    Calendario calendario,
    String txid,
    Integer revisao,
    String location,
    String status,
    Valor valor,
    String chave,
    String pixCopiaECola,
    List<PixItem> pix) {
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Calendario(Instant criacao, Integer expiracao) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Valor(String original) {}
}
