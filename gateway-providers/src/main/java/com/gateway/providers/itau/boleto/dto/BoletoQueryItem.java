package com.gateway.providers.itau.boleto.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;

/**
 * One boleto of GET /boletos (query OpenAPI, schema {@code boleto}). The payment block is the list
 * {@code pagamentos_cobranca} (the spec called it {@code pagamento}); the last entry is the one that
 * settled. {@code qrcode_pix.emv} is the Bolecode's EMV, present when the bank keeps it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BoletoQueryItem(@JsonProperty("id_boleto") String idBoleto, @JsonProperty("dado_boleto") DadoBoleto dadoBoleto) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DadoBoleto(
      @JsonProperty("dados_individuais_boleto") List<Individual> dadosIndividuaisBoleto,
      @JsonProperty("pagamentos_cobranca") List<Pagamento> pagamentosCobranca,
      Baixa baixa,
      @JsonProperty("qrcode_pix") QrcodePix qrcodePix) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Individual(
      @JsonProperty("situacao_geral_boleto") String situacaoGeralBoleto,
      @JsonProperty("numero_nosso_numero") String numeroNossoNumero,
      @JsonProperty("id_boleto_individual") String idBoletoIndividual,
      @JsonProperty("codigo_barras") String codigoBarras,
      @JsonProperty("numero_linha_digitavel") String numeroLinhaDigitavel,
      @JsonProperty("data_vencimento") String dataVencimento,
      @JsonProperty("data_limite_pagamento") String dataLimitePagamento,
      @JsonProperty("valor_titulo") String valorTitulo) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Pagamento(
      @JsonProperty("valor_pago_total_cobranca") String valorPagoTotalCobranca,
      @JsonProperty("data_inclusao_pagamento") String dataInclusaoPagamento,
      @JsonProperty("data_hora_inclusao_pagamento") String dataHoraInclusaoPagamento,
      @JsonProperty("codigo_meio_pagamento_boleto_cobranca") String codigoMeioPagamento,
      @JsonProperty("descricao_meio_pagamento") String descricaoMeioPagamento) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Baixa(@JsonProperty("data_inclusao_alteracao_baixa") String data, @JsonProperty("motivo_baixa") String motivo) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record QrcodePix(String emv) {}

  public Optional<Individual> individual(String nossoNumero) {
    if (dadoBoleto == null || dadoBoleto.dadosIndividuaisBoleto() == null) return Optional.empty();
    return dadoBoleto.dadosIndividuaisBoleto().stream().filter(i -> nossoNumero.equals(i.numeroNossoNumero())).findFirst();
  }

  public Optional<Pagamento> lastPayment() {
    if (dadoBoleto == null || dadoBoleto.pagamentosCobranca() == null || dadoBoleto.pagamentosCobranca().isEmpty()) return Optional.empty();
    return Optional.of(dadoBoleto.pagamentosCobranca().getLast());
  }
}
