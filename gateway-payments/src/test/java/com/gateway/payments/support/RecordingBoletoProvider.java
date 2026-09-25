package com.gateway.payments.support;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.boleto.BoletoIssueRequest;
import com.gateway.kernel.provider.boleto.BoletoProvider;
import com.gateway.kernel.provider.boleto.BoletoSituation;
import com.gateway.kernel.provider.boleto.BoletoStatus;
import com.gateway.kernel.provider.boleto.IssuedBoleto;
import com.gateway.kernel.provider.pix.Charge;
import com.gateway.kernel.provider.pix.ChargeStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.gateway.kernel.ids.MerchantId;
import java.nio.charset.StandardCharsets;

/**
 * In-memory implementation of the kernel's {@link BoletoProvider}, for the payments module's tests
 * only (never a product provider, never leaves src/test). Issuing also registers the Pix side of the
 * Bolecode in {@link RecordingPixProvider}, the way the bank creates both at once.
 */
public class RecordingBoletoProvider implements BoletoProvider {
  /** The account when no credential is given: agência 1500, conta 0000520, DAC 6 — the same shape ItauBoletoProvider derives from. */
  static final String BENEFICIARY = "150000052061";
  private static final Pattern BENEFICIARY_ID = Pattern.compile("\"beneficiary_id\":\"([0-9]{12})\"");
  static final String WALLET = "109";

  private final Clock clock;
  private final RecordingPixProvider pix;
  /** Keyed by {@code beneficiary:nossoNumero}: every test merchant's first boleto is 00000001 and the context is shared. */
  private final Map<String, BoletoStatus> boletos = new ConcurrentHashMap<>();
  /** The last key issued per number, so the number-only helpers act on the current test's boleto (tests run one at a time). */
  private final Map<String, String> latest = new ConcurrentHashMap<>();
  private final Map<String, ProviderException> failFind = new ConcurrentHashMap<>();
  private final List<String> calls = new CopyOnWriteArrayList<>();
  private volatile ProviderException failNextIssue;
  private volatile ProviderException landThenFail;
  private volatile boolean refuseCredentials;
  private volatile boolean skipPix;

  public RecordingBoletoProvider(Clock clock, RecordingPixProvider pix) {
    this.clock = clock;
    this.pix = pix;
  }

  @Override public String id() { return "ITAU"; }

  public void failNextIssueWith(ProviderException e) { this.failNextIssue = e; }

  /** The POST reached the bank and issued the boleto, but the caller sees {@code e} (a timeout, a 503, a 202). */
  public void landNextIssueThenFailWith(ProviderException e) { this.landThenFail = e; }

  /** The next issue creates no Pix side at the bank, so GET /cob on the derived txid finds nothing. */
  public void skipNextPixRegistration() { this.skipPix = true; }

  public void refuseNextIssueCredentials() { this.refuseCredentials = true; }

  public void failNextFindWith(String nossoNumero, ProviderException e) { failFind.put(nossoNumero, e); }

  /**
   * One merchant's boleto only: every test merchant's first boleto is 00000001 and POLL_BOLETO jobs
   * of earlier tests fall due at the same instant, so a bare number was consumed by another job.
   */
  public void failNextFindWith(MerchantId merchant, String nossoNumero, ProviderException e) {
    failFind.put(InMemoryCredentialLookup.beneficiaryOf(merchant) + ":" + nossoNumero, e);
  }

  public void markPaid(String nossoNumero, Money amount, Instant at) {
    String k = key(nossoNumero);
    BoletoStatus s = boletos.get(k);
    boletos.put(k, new BoletoStatus(BoletoSituation.PAID, amount, at, "01", s.idBoletoIndividual(), s.linhaDigitavel(), s.codigoBarras(), s.paymentLimitDate(), s.pixCopiaECola()));
  }

  public void setSituation(String nossoNumero, BoletoSituation situation) {
    String k = key(nossoNumero);
    BoletoStatus s = boletos.get(k);
    boletos.put(k, new BoletoStatus(situation, s.paidAmount(), s.paidAt(), s.paidChannel(), s.idBoletoIndividual(), s.linhaDigitavel(), s.codigoBarras(), s.paymentLimitDate(), s.pixCopiaECola()));
  }

  public void remove(String nossoNumero) { boletos.remove(key(nossoNumero)); }

  public BoletoStatus status(String nossoNumero) { return boletos.get(key(nossoNumero)); }

  private String key(String nossoNumero) { return latest.getOrDefault(nossoNumero, BENEFICIARY + ":" + nossoNumero); }

  public List<String> callsFor(String key) { return calls.stream().filter(c -> c.endsWith(":" + key)).toList(); }

  /**
   * The calls for one merchant's boleto, as {@code op:nossoNumero}. Every test merchant's first
   * boleto is 00000001 and the context is shared, so a bare number would see other tests' calls.
   */
  public List<String> callsFor(MerchantId merchant, String nossoNumero) {
    String suffix = ":" + InMemoryCredentialLookup.beneficiaryOf(merchant) + ":" + nossoNumero;
    return calls.stream().filter(c -> c.endsWith(suffix)).map(c -> c.substring(0, c.indexOf(':')) + ":" + nossoNumero).toList();
  }

  /** The txid the bank derives for {@code merchant}'s boleto. */
  public String pixTxidFor(MerchantId merchant, String nossoNumero) {
    return txid(InMemoryCredentialLookup.beneficiaryOf(merchant), nossoNumero);
  }

  private static String beneficiary(ProviderCredentials c) {
    if (c == null) return BENEFICIARY;
    Matcher m = BENEFICIARY_ID.matcher(new String(c.payload(), StandardCharsets.UTF_8));
    return m.find() ? m.group(1) : BENEFICIARY;
  }

  @Override public void requireIssueCredentials(ProviderCredentials c) {
    if (refuseCredentials) {
      refuseCredentials = false;
      throw new ProviderException(ProviderException.Code.CREDENTIALS_INCOMPLETE, 0, "beneficiary_id", "ITAU credential is missing beneficiary_id");
    }
  }

  @Override public IssuedBoleto issue(ProviderCredentials c, BoletoIssueRequest r) {
    calls.add("issueBoleto:" + beneficiary(c) + ":" + r.nossoNumero());
    ProviderException fail = failNextIssue;
    if (fail != null) {
      failNextIssue = null;
      throw fail;
    }
    String txid = pixTxidFor(c, r.nossoNumero());
    String emv = "00020101021226" + txid;
    String linha = ("3419" + r.nossoNumero()).repeat(5).substring(0, 47);
    String barras = ("3419" + r.nossoNumero()).repeat(4).substring(0, 44);
    String k = beneficiary(c) + ":" + r.nossoNumero();
    latest.put(r.nossoNumero(), k);
    boletos.put(k, new BoletoStatus(BoletoSituation.OPEN, null, null, null, "uuid-" + r.nossoNumero(), linha, barras, r.paymentLimitDate(), emv));
    if (skipPix) skipPix = false;
    else pix.register(new Charge(txid, ChargeStatus.ACTIVE, r.amount(), emv, "pix.example/qr/" + txid, clock.instant(), 0, List.of()));
    ProviderException after = landThenFail;
    if (after != null) {
      landThenFail = null;
      throw after;
    }
    return new IssuedBoleto("uuid-" + r.nossoNumero(), linha, barras, r.paymentLimitDate(), txid, emv, "60701190000104");
  }

  @Override public Optional<BoletoStatus> find(ProviderCredentials c, String nossoNumero) {
    calls.add("findBoleto:" + beneficiary(c) + ":" + nossoNumero);
    ProviderException fail = failFind.remove(beneficiary(c) + ":" + nossoNumero);
    if (fail == null) fail = failFind.remove(nossoNumero);
    if (fail != null) throw fail;
    return Optional.ofNullable(boletos.get(beneficiary(c) + ":" + nossoNumero));
  }

  @Override public void cancel(ProviderCredentials c, String nossoNumero) {
    calls.add("cancelBoleto:" + beneficiary(c) + ":" + nossoNumero);
    String k = beneficiary(c) + ":" + nossoNumero;
    BoletoStatus s = boletos.get(k);
    if (s == null) throw new ProviderException(ProviderException.Code.NOT_FOUND, 404, "404", "not found");
    if (s.paid()) throw new ProviderException(ProviderException.Code.CONFLICT, 422, "422", "Boleto já liquidado");
    boletos.put(k, new BoletoStatus(BoletoSituation.CANCELED, s.paidAmount(), s.paidAt(), s.paidChannel(), s.idBoletoIndividual(), s.linhaDigitavel(), s.codigoBarras(), s.paymentLimitDate(), s.pixCopiaECola()));
  }

  /** Same formula as the Itaú provider: BL + beneficiary without DAC + wallet + number padded to 15. */
  @Override public String pixTxidFor(ProviderCredentials c, String nossoNumero) {
    return txid(beneficiary(c), nossoNumero);
  }

  private static String txid(String beneficiary, String nossoNumero) {
    return "BL" + beneficiary.substring(0, 11) + WALLET + "0".repeat(15 - nossoNumero.length()) + nossoNumero;
  }
}
