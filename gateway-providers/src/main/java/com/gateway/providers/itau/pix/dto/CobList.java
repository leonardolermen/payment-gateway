package com.gateway.providers.itau.pix.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** GET /cob response: the query window echoed back plus one page of cobs. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CobList(Parametros parametros, List<CobResponse> cobs) {
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Parametros(Paginacao paginacao) {}
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Paginacao(int paginaAtual, int itensPorPagina, int quantidadeDePaginas, int quantidadeTotalDeItens) {}
}
