package com.gateway.providers.itau.boleto;

import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.boleto.BoletoIssueRequest;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.boleto.BoletoMethodProvider;
import com.gateway.kernel.provider.boleto.BoletoStatus;
import com.gateway.kernel.provider.boleto.IssuedBoleto;
import com.gateway.providers.itau.auth.ItauCredentials;
import com.gateway.providers.itau.auth.ItauTokenClient;
import com.gateway.providers.itau.boleto.dto.BoletoPixRequest;
import com.gateway.providers.itau.boleto.dto.BoletoPixResponse;
import com.gateway.providers.itau.boleto.dto.BoletoQueryItem;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

/** The only class that knows the three boleto APIs and the gateway's vocabulary at the same time. */
public class ItauBoletoProvider implements BoletoMethodProvider {
  private record Clients(BoletoPixApiClient issue, BoletoQueryClient query, BoletoInstructionClient instruction) {}

  private final Clients live, test;

  public ItauBoletoProvider(ItauTokenClient tokens, KeyStore trustStore, Duration readTimeout, ItauBoletoEndpoints liveEndpoints, ItauBoletoEndpoints testEndpoints) {
    this.live = clients(tokens, trustStore, readTimeout, liveEndpoints);
    this.test = clients(tokens, trustStore, readTimeout, testEndpoints);
  }

  private static Clients clients(ItauTokenClient tokens, KeyStore trustStore, Duration readTimeout, ItauBoletoEndpoints e) {
    return new Clients(
        new BoletoPixApiClient(tokens, e.issue(), trustStore, readTimeout),
        new BoletoQueryClient(tokens, e.query(), trustStore, readTimeout),
        new BoletoInstructionClient(tokens, e.instruction(), trustStore, readTimeout));
  }

  @Override public String id() {
    return "ITAU";
  }

  @Override public PaymentMethod method() {
    return PaymentMethod.BOLECODE;
  }

  private Clients clients(ProviderCredentials c) {
    return c.environment() == ProviderEnvironment.LIVE ? live : test;
  }

  /** A credential without the beneficiary is the merchant's configuration problem, not the bank's: CREDENTIALS_INCOMPLETE names the field. */
  private static ItauCredentials boletoCreds(ProviderCredentials c) {
    ItauCredentials ic = ItauCredentials.parse(c.payload());
    try {
      ic.requireBoletoShape();
    } catch (IllegalArgumentException e) {
      throw new ProviderException(ProviderException.Code.CREDENTIALS_INCOMPLETE, 0, e.getMessage(), "ITAU credential is missing " + e.getMessage());
    }
    return ic;
  }

  @Override public void requireIssueCredentials(ProviderCredentials c) {
    boletoCreds(c);
  }

  @Override public IssuedBoleto issue(ProviderCredentials c, BoletoIssueRequest r) {
    ItauCredentials ic = boletoCreds(c);
    BoletoPixResponse res = clients(c).issue().post(ic, BoletoPixRequest.forIssue(r, ic));
    BoletoPixResponse.Individual i = res.first();
    BoletoPixResponse.DadosQrcode qr = res.dadosQrcode();
    return new IssuedBoleto(i.idBoletoIndividual(), i.numeroLinhaDigitavel(), i.codigoBarras(), ItauDates.date(i.dataLimitePagamento()),
        qr == null ? null : qr.txid(), qr == null ? null : qr.emv(), qr == null ? null : qr.chave());
  }

  @Override public Optional<BoletoStatus> find(ProviderCredentials c, String nossoNumero) {
    ItauCredentials ic = boletoCreds(c);
    return clients(c).query().find(ic, nossoNumero).map(item -> toStatus(item, nossoNumero));
  }

  @Override public void cancel(ProviderCredentials c, String nossoNumero) {
    ItauCredentials ic = boletoCreds(c);
    clients(c).instruction().baixa(ic, baixaId(ic, nossoNumero));
  }

  @Override public String pixTxidFor(ProviderCredentials c, String nossoNumero) {
    return pixTxid(boletoCreds(c), nossoNumero);
  }

  private static final Pattern DIGITS = Pattern.compile("\\d+");

  /**
   * Both derivations concatenate the account data with the caller's nosso número; an unvalidated
   * value either throws an unclassified IllegalArgumentException ({@code "0".repeat(negative)} for
   * a txid over 15 digits) or silently builds a wrong id/txid the bank then 404s or misroutes on.
   * {@code Code.INVALID} names the field so the merchant's response says which value was bad.
   */
  private static String validateNossoNumero(String nossoNumero, int min, int max) {
    if (nossoNumero == null || !DIGITS.matcher(nossoNumero).matches() || nossoNumero.length() < min || nossoNumero.length() > max) {
      throw new ProviderException(ProviderException.Code.INVALID, 0, "nosso_numero",
          "nosso_numero must be " + min + "-" + max + " digits: " + nossoNumero);
    }
    return nossoNumero;
  }

  /** cash_management OpenAPI, path {id_boleto}: agência (4) + conta (7) + DAC (1) + carteira (3) + nosso número (8-16). */
  static String baixaId(ItauCredentials c, String nossoNumero) {
    return c.beneficiaryId() + c.walletCode() + validateNossoNumero(nossoNumero, 8, 16);
  }

  /** Issue OpenAPI, dados_qrcode.txid: "BL" + agência (4) + conta (7) + carteira (3) + nosso número (15) — beneficiary id without its DAC. */
  static String pixTxid(ItauCredentials c, String nossoNumero) {
    validateNossoNumero(nossoNumero, 1, 15);
    return "BL" + c.beneficiaryId().substring(0, 11) + c.walletCode() + "0".repeat(15 - nossoNumero.length()) + nossoNumero;
  }

  static BoletoStatus toStatus(BoletoQueryItem item, String nossoNumero) {
    BoletoQueryItem.Individual i = item.individual(nossoNumero).orElseThrow();
    Optional<BoletoQueryItem.Pagamento> last = item.lastPayment();
    return new BoletoStatus(
        BoletoSituations.parse(i.situacaoGeralBoleto()),
        last.map(p -> p.valorPagoTotalCobranca() == null ? null : BoletoAmounts.fromItau(p.valorPagoTotalCobranca())).orElse(null),
        last.map(p -> ItauDates.paidAt(p.dataHoraInclusaoPagamento(), p.dataInclusaoPagamento())).orElse(null),
        last.map(p -> p.codigoMeioPagamento() != null ? p.codigoMeioPagamento() : p.descricaoMeioPagamento()).orElse(null),
        i.idBoletoIndividual(),
        i.numeroLinhaDigitavel(),
        i.codigoBarras(),
        ItauDates.date(i.dataLimitePagamento()),
        item.dadoBoleto().qrcodePix() == null ? null : item.dadoBoleto().qrcodePix().emv());
  }
}
