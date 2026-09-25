package com.gateway.providers.itau.boleto.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** POST /boletos-pix 200 (schema {@code boletoPixResponse}); only the fields the gateway keeps. ignoreUnknown: the bank echoes the whole request plus juros/multa/etc. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BoletoPixResponse(@JsonProperty("dado_boleto") DadoBoleto dadoBoleto, @JsonProperty("dados_qrcode") DadosQrcode dadosQrcode) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DadoBoleto(@JsonProperty("dados_individuais_boleto") List<Individual> dadosIndividuaisBoleto) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Individual(
      @JsonProperty("id_boleto_individual") String idBoletoIndividual,
      @JsonProperty("numero_nosso_numero") String numeroNossoNumero,
      @JsonProperty("dac_titulo") String dacTitulo,
      @JsonProperty("codigo_barras") String codigoBarras,
      @JsonProperty("numero_linha_digitavel") String numeroLinhaDigitavel,
      @JsonProperty("data_limite_pagamento") String dataLimitePagamento) {}

  /** {@code base64} (the QR image) is not modelled: the merchant renders the EMV. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DadosQrcode(String chave, String txid, String emv, String location) {}

  public Individual first() {
    if (dadoBoleto == null || dadoBoleto.dadosIndividuaisBoleto() == null || dadoBoleto.dadosIndividuaisBoleto().isEmpty()) {
      throw new IllegalStateException("boletos-pix response without dados_individuais_boleto");
    }
    return dadoBoleto.dadosIndividuaisBoleto().getFirst();
  }
}
