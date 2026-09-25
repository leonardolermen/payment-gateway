package com.gateway.providers.itau.pix;

import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.ProviderWebhookEvent;

import com.gateway.kernel.provider.pix.Charge;
import com.gateway.kernel.provider.pix.ChargeStatus;
import com.gateway.kernel.provider.pix.PixProvider;
import com.gateway.kernel.provider.pix.ReceivedPix;
import com.gateway.kernel.provider.pix.RefundRequest;
import com.gateway.kernel.provider.pix.RefundResult;
import com.gateway.kernel.provider.pix.RefundStatus;
import com.gateway.providers.itau.auth.ItauCredentials;
import com.gateway.providers.itau.auth.ItauEndpoints;
import com.gateway.providers.itau.auth.ItauTokenClient;
import com.gateway.providers.itau.auth.PemKeyStores;
import com.gateway.providers.itau.pix.dto.CobList;
import com.gateway.providers.itau.pix.dto.CobRequest;
import com.gateway.providers.itau.pix.dto.CobResponse;
import com.gateway.providers.itau.pix.dto.DevolucaoRequest;
import com.gateway.providers.itau.pix.dto.DevolucaoResponse;
import com.gateway.providers.itau.pix.dto.PixItem;
import com.gateway.providers.itau.pix.dto.WebhookPayload;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.*;
import com.gateway.providers.itau.pix.dto.*;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** The only class that knows Itaú's vocabulary and the gateway's at the same time. */
public class ItauPixProvider implements PixProvider {
  private static final int PAGE_SIZE = 100;
  private final PixApiClient live, test;
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ItauPixProvider.class);
  private final ObjectMapper mapper = new ObjectMapper();

  public ItauPixProvider(ItauTokenClient tokens, KeyStore trustStore, Duration readTimeout, Clock clock, ItauEndpoints liveEndpoints, ItauEndpoints testEndpoints) {
    // clock stays in the public signature (planned interface) but nothing here reads time yet;
    // token expiry is the token client's clock.
    this.live = new PixApiClient(tokens, liveEndpoints, trustStore, readTimeout);
    this.test = new PixApiClient(tokens, testEndpoints, trustStore, readTimeout);
  }

  /** Itaú's CA(s) as PEM (one or more certificates) → a truststore for the per-credential SSLContext. */
  public static KeyStore trustStoreFromPem(String pem) {
    try {
      var certs = java.security.cert.CertificateFactory.getInstance("X.509")
          .generateCertificates(new java.io.ByteArrayInputStream(pem.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
      if (certs.isEmpty()) throw new IllegalArgumentException("no certificate in the Itaú trust store PEM");
      return PemKeyStores.trustStoreFrom(certs.stream().map(c -> (java.security.cert.X509Certificate) c).toList());
    } catch (java.security.cert.CertificateException e) {
      throw new IllegalArgumentException("invalid Itaú trust store PEM: " + e.getMessage(), e);
    }
  }

  @Override public String id() { return "ITAU"; }

  private PixApiClient client(ProviderCredentials c) { return c.environment() == ProviderEnvironment.LIVE ? live : test; }
  private static ItauCredentials creds(ProviderCredentials c) { return ItauCredentials.parse(c.payload()); }

  @Override
  public Charge createCharge(ProviderCredentials c, String txid, Money amount, int expiresInSeconds, String payerDocument, String payerName, String description) {
    ItauCredentials ic = creds(c);
    return toCharge(client(c).putCob(ic, txid, CobRequest.forCharge(amount, expiresInSeconds, ic.pixKey(), payerDocument, payerName, description)));
  }

  @Override public Optional<Charge> findCharge(ProviderCredentials c, String txid) { return client(c).getCob(creds(c), txid).map(ItauPixProvider::toCharge); }

  @Override public void cancelCharge(ProviderCredentials c, String txid) {
    client(c).patchCob(creds(c), txid, Map.of("status", "REMOVIDA_PELO_USUARIO_RECEBEDOR"));
  }

  @Override public RefundResult requestRefund(ProviderCredentials c, RefundRequest r) {
    return toRefund(client(c).putDevolucao(creds(c), r.endToEndId(), r.refundId(), new DevolucaoRequest(PixAmounts.toItau(r.amount()))));
  }

  @Override public Optional<RefundResult> findRefund(ProviderCredentials c, String e2eid, String refundId) {
    return client(c).getDevolucao(creds(c), e2eid, refundId).map(ItauPixProvider::toRefund);
  }

  /** Walks every page: reconciliation that silently stops at page 0 would call paid charges unpaid. */
  @Override public List<Charge> listCharges(ProviderCredentials c, Instant from, Instant to) {
    ItauCredentials ic = creds(c);
    List<Charge> out = new ArrayList<>();
    int page = 0;
    while (true) {
      CobList l = client(c).listCob(ic, from, to, page, PAGE_SIZE);
      if (l.cobs() != null) l.cobs().forEach(cob -> out.add(toCharge(cob)));
      int pages = l.parametros() == null || l.parametros().paginacao() == null ? 1 : l.parametros().paginacao().quantidadeDePaginas();
      if (++page >= pages) break;
    }
    return out;
  }

  @Override public ProviderWebhookEvent parseWebhook(byte[] body) {
    WebhookPayload p;
    try {
      p = mapper.readValue(body, WebhookPayload.class);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("unreadable Itaú webhook", e);
    }
    if (p == null || p.pix() == null) throw new IllegalArgumentException("Itaú webhook without pix[]");

    List<ReceivedPix> received = new ArrayList<>();
    List<RefundResult> refunds = new ArrayList<>();
    Map<String, String> txids = new HashMap<>();
    Map<String, String> refundE2e = new HashMap<>();

    for (PixItem it : p.pix()) {

      if (it == null || it.endToEndId() == null) {
        // endToEndId is the dedup key; an item without it cannot be recorded or matched.
        LOG.warn("Itaú webhook item without endToEndId skipped (txid={})", it == null ? null : it.txid());
        continue;
      }

      received.add(toReceived(it));

      if (it.txid() != null) txids.put(it.endToEndId(), it.txid());

      if (it.devolucoes() != null) {
        it.devolucoes().forEach(d -> {
          refunds.add(toRefund(d));
          if (d.id() != null) refundE2e.put(d.id(), it.endToEndId());
        });

      }
    }
    return new ProviderWebhookEvent(received, refunds, txids, refundE2e);
  }

  static Charge toCharge(CobResponse r) {
    if (r.valor() == null || r.valor().original() == null) {
      throw new ProviderException(ProviderException.Code.UNKNOWN, 200, null, "Itaú cob without valor.original: " + r.txid());
    }

    List<ReceivedPix> pix = r.pix() == null ? List.of() : r.pix().stream().map(ItauPixProvider::toReceived).toList();
    Instant created = r.calendario() == null ? null : r.calendario().criacao();
    int exp = r.calendario() == null || r.calendario().expiracao() == null ? 0 : r.calendario().expiracao();

    return new Charge(r.txid(), toStatus(r.status()), PixAmounts.fromItau(r.valor().original()), r.pixCopiaECola(), r.location(), created, exp, pix);
  }

  static ReceivedPix toReceived(PixItem i) { return new ReceivedPix(i.endToEndId(), PixAmounts.fromItau(i.valor()), i.horario(), i.infoPagador()); }

  static RefundResult toRefund(DevolucaoResponse d) {
    RefundStatus s = switch (d.status() == null ? "" : d.status()) {
      case "DEVOLVIDO" -> RefundStatus.COMPLETED;
      case "NAO_REALIZADO" -> RefundStatus.FAILED;
      default -> RefundStatus.PROCESSING;
    };

    Instant requested = d.horario() == null ? null : d.horario().solicitacao();
    Instant settled = d.horario() == null ? null : d.horario().liquidacao();
    // motivo is only a failure reason when the refund failed; on DEVOLVIDO the bank fills it with prose.
    return new RefundResult(d.id(), s, PixAmounts.fromItau(d.valor()), s == RefundStatus.FAILED ? d.motivo() : null, requested, settled);
  }

  /** Both spellings: the OpenAPI enum says REMOVIDA_…, the portal prose says REMOVIDO_… (NOTES.md). */
  static ChargeStatus toStatus(String s) {
    return switch (s == null ? "" : s) {
      case "ATIVA" -> ChargeStatus.ACTIVE;
      case "CONCLUIDA" -> ChargeStatus.COMPLETED;
      case "REMOVIDA_PELO_USUARIO_RECEBEDOR", "REMOVIDO_PELO_USUARIO_RECEBEDOR" -> ChargeStatus.REMOVED_BY_MERCHANT;
      case "REMOVIDA_PELO_PSP", "REMOVIDO_PELO_PSP" -> ChargeStatus.REMOVED_BY_PSP;
      default -> throw new ProviderException(ProviderException.Code.UNKNOWN, 200, null, "unknown charge status: " + s);
    };
  }
}
