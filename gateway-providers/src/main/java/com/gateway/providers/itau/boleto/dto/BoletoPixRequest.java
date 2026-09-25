package com.gateway.providers.itau.boleto.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gateway.kernel.provider.boleto.BoletoIssueRequest;
import com.gateway.providers.itau.auth.ItauCredentials;
import com.gateway.providers.itau.boleto.BoletoAmounts;
import com.gateway.providers.itau.boleto.BoletoText;
import java.util.List;

/**
 * POST /boletos-pix body (issue OpenAPI, schema {@code boletoPix}). Field names are the bank's; the
 * gateway never sees them. NON_NULL: the payer's document goes in exactly one of two properties and
 * the optional limit date is left out rather than sent as null. Always {@code efetivacao}: the
 * {@code simulacao} step is a smoke-test tool (spec §1), never part of the product flow.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BoletoPixRequest(
    @JsonProperty("etapa_processo_boleto") String etapaProcessoBoleto,
    Beneficiario beneficiario,
    @JsonProperty("dado_boleto") DadoBoleto dadoBoleto) {

  public record Beneficiario(@JsonProperty("id_beneficiario") String idBeneficiario) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record DadoBoleto(
      @JsonProperty("descricao_instrumento_cobranca") String descricaoInstrumentoCobranca,
      @JsonProperty("tipo_boleto") String tipoBoleto,
      @JsonProperty("codigo_carteira") String codigoCarteira,
      @JsonProperty("codigo_especie") String codigoEspecie,
      @JsonProperty("valor_total_titulo") String valorTotalTitulo,
      Pagador pagador,
      @JsonProperty("dados_individuais_boleto") List<DadoIndividual> dadosIndividuaisBoleto) {}

  public record Pagador(Pessoa pessoa, Endereco endereco) {}

  public record Pessoa(@JsonProperty("nome_pessoa") String nomePessoa, @JsonProperty("tipo_pessoa") TipoPessoa tipoPessoa) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TipoPessoa(
      @JsonProperty("codigo_tipo_pessoa") String codigoTipoPessoa,
      @JsonProperty("numero_cadastro_pessoa_fisica") String cpf,
      @JsonProperty("numero_cadastro_nacional_pessoa_juridica") String cnpj) {}

  public record Endereco(
      @JsonProperty("nome_logradouro") String nomeLogradouro,
      @JsonProperty("nome_bairro") String nomeBairro,
      @JsonProperty("nome_cidade") String nomeCidade,
      @JsonProperty("sigla_UF") String siglaUf,
      @JsonProperty("numero_CEP") String numeroCep) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record DadoIndividual(
      @JsonProperty("numero_nosso_numero") String numeroNossoNumero,
      @JsonProperty("data_vencimento") String dataVencimento,
      @JsonProperty("valor_titulo") String valorTitulo,
      @JsonProperty("data_limite_pagamento") String dataLimitePagamento,
      @JsonProperty("texto_uso_beneficiario") String textoUsoBeneficiario) {}

  public static BoletoPixRequest forIssue(BoletoIssueRequest r, ItauCredentials c) {
    String digits = r.payer().document().digits();
    TipoPessoa tipo = digits.length() == 14 ? new TipoPessoa("J", null, digits) : new TipoPessoa("F", digits, null);
    var address = r.payer().address();
    String amount = BoletoAmounts.toItau(r.amount());
    return new BoletoPixRequest(
        "efetivacao",
        new Beneficiario(c.beneficiaryId()),
        new DadoBoleto(
            "boleto_pix",
            "a vista",
            c.walletCode(),
            c.speciesCode(),
            amount,
            new Pagador(
                new Pessoa(BoletoText.name(r.payer().name().value(), 50), tipo),
                new Endereco(
                    BoletoText.text(address.street(), 45), BoletoText.text(address.district(), 15), BoletoText.text(address.city(), 20),
                    address.state().value(), address.zip().digits())),
            List.of(new DadoIndividual(
                r.nossoNumero(), r.dueDate().toString(), amount,
                r.paymentLimitDate() == null ? null : r.paymentLimitDate().toString(),
                r.description() == null ? null : BoletoText.text(r.description(), 25)))));
  }
}
