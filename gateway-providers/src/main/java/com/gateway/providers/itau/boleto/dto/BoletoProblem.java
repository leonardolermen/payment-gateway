package com.gateway.providers.itau.boleto.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The three boleto APIs' error body ({@code codigo, mensagem, campos[]}) — not RFC 7807 like the
 * Pix API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BoletoProblem(String codigo, String mensagem, List<Campo> campos) {
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Campo(String campo, String mensagem, String valor) {}
}
