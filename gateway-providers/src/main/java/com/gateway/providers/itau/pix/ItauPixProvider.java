package com.gateway.providers.itau.pix;

import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.*;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.ProviderWebhookEvent;
import com.gateway.kernel.provider.pix.Charge;
import com.gateway.kernel.provider.pix.ChargeStatus;
import com.gateway.kernel.provider.pix.PixIssueRequest;
import com.gateway.kernel.provider.pix.PixMethodProvider;
import com.gateway.kernel.provider.pix.ReceivedPix;
import com.gateway.kernel.provider.pix.RefundRequest;
import com.gateway.kernel.provider.pix.RefundResult;
import com.gateway.kernel.provider.pix.RefundStatus;
import com.gateway.providers.itau.auth.ItauCredentials;
import com.gateway.providers.itau.auth.ItauEndpoints;
import com.gateway.providers.itau.auth.ItauTokenClient;
import com.gateway.providers.itau.auth.PemKeyStores;
import com.gateway.providers.itau.pix.dto.*;
import com.gateway.providers.itau.pix.dto.CobList;
import com.gateway.providers.itau.pix.dto.CobRequest;
import com.gateway.providers.itau.pix.dto.CobResponse;
import com.gateway.providers.itau.pix.dto.DevolucaoRequest;
import com.gateway.providers.itau.pix.dto.DevolucaoResponse;
import com.gateway.providers.itau.pix.dto.PixItem;
import com.gateway.providers.itau.pix.dto.WebhookPayload;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** The only class that knows Itaú's vocabulary and the gateway's at the same time. */
public class ItauPixProvider implements PixMethodProvider {
  private static final int PAGE_SIZE = 100;
  private final PixApiClient live, test;
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(ItauPixProvider.class);
  private final ObjectMapper mapper = new ObjectMapper();

  public ItauPixProvider(
      ItauTokenClient tokens,
      KeyStore trustStore,
      Duration readTimeout,
      Clock clock,
      ItauEndpoints liveEndpoints,
      ItauEndpoints testEndpoints) {
    // clock stays in the public signature (planned interface) but nothing here reads time yet;
    // token expiry is the token client's clock.
    this.live = new PixApiClient(tokens, liveEndpoints, trustStore, readTimeout);
    this.test = new PixApiClient(tokens, testEndpoints, trustStore, readTimeout);
  }

  /**
   * Itaú's CA(s) as PEM (one or more certificates) → a truststore for the per-credential
   * SSLContext.
   */
  public static KeyStore trustStoreFromPem(String pem) {
    try {
      var certs =
          java.security.cert.CertificateFactory.getInstance("X.509")
              .generateCertificates(
                  new java.io.ByteArrayInputStream(
                      pem.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
      if (certs.isEmpty()) {
        throw new IllegalArgumentException("no certificate in the Itaú trust store PEM");
      }
      return PemKeyStores.trustStoreFrom(
          certs.stream()
              .map(certificate -> (java.security.cert.X509Certificate) certificate)
              .toList());
    } catch (java.security.cert.CertificateException e) {
      throw new IllegalArgumentException("invalid Itaú trust store PEM: " + e.getMessage(), e);
    }
  }

  @Override
  public String id() {
    return "ITAU";
  }

  @Override
  public PaymentMethod method() {
    return PaymentMethod.PIX;
  }

  /**
   * Pix needs the key the charge is collected into. Without this check a credential missing it only
   * failed at the first HTTP call, surfacing as PROVIDER_DECLINED; here it is
   * PROVIDER_CREDENTIALS_MISSING before any row exists, which is what a merchant can act on.
   */
  @Override
  public void requireIssueCredentials(ProviderCredentials providerCredentials) {
    requirePixCredentials(providerCredentials);
  }

  /**
   * ItauCredentials' canonical constructor already names the missing field, but as an
   * IllegalArgumentException, which would surface as a 500. Only this entry point translates it:
   * the other operations keep failing the way they already did.
   */
  private static ItauCredentials requirePixCredentials(ProviderCredentials providerCredentials) {
    try {
      return creds(providerCredentials);
    } catch (IllegalArgumentException e) {
      String field =
          e.getMessage() == null ? null : e.getMessage().replace("missing required field: ", "");
      throw new ProviderException(
          ProviderException.Code.CREDENTIALS_INCOMPLETE, 0, field, e.getMessage());
    }
  }

  private PixApiClient client(ProviderCredentials providerCredentials) {
    return providerCredentials.environment() == ProviderEnvironment.LIVE ? live : test;
  }

  private static ItauCredentials creds(ProviderCredentials providerCredentials) {
    return ItauCredentials.parse(providerCredentials.payload());
  }

  @Override
  public Charge issue(ProviderCredentials providerCredentials, PixIssueRequest request) {
    ItauCredentials credentials = creds(providerCredentials);
    CobRequest cob =
        CobRequest.forCharge(
            request.amount(),
            request.expiresInSeconds(),
            credentials.pixKey(),
            request.payerDocument(),
            request.payerName(),
            request.description());

    return toCharge(client(providerCredentials).putCob(credentials, request.txid(), cob));
  }

  @Override
  public Optional<Charge> find(ProviderCredentials providerCredentials, String txid) {
    return client(providerCredentials)
        .getCob(creds(providerCredentials), txid)
        .map(ItauPixProvider::toCharge);
  }

  @Override
  public void cancel(ProviderCredentials providerCredentials, String txid) {
    client(providerCredentials)
        .patchCob(
            creds(providerCredentials), txid, Map.of("status", "REMOVIDA_PELO_USUARIO_RECEBEDOR"));
  }

  @Override
  public RefundResult requestRefund(
      ProviderCredentials providerCredentials, RefundRequest request) {
    return toRefund(
        client(providerCredentials)
            .putDevolucao(
                creds(providerCredentials),
                request.endToEndId(),
                request.refundId(),
                new DevolucaoRequest(PixAmounts.toItau(request.amount()))));
  }

  @Override
  public Optional<RefundResult> findRefund(
      ProviderCredentials providerCredentials, String e2eid, String refundId) {
    return client(providerCredentials)
        .getDevolucao(creds(providerCredentials), e2eid, refundId)
        .map(ItauPixProvider::toRefund);
  }

  /**
   * Walks every page: reconciliation that silently stops at page 0 would call paid charges unpaid.
   */
  @Override
  public List<Charge> listCharges(
      ProviderCredentials providerCredentials, Instant from, Instant to) {
    ItauCredentials credentials = creds(providerCredentials);
    List<Charge> out = new ArrayList<>();
    int page = 0;
    while (true) {
      CobList cobList = client(providerCredentials).listCob(credentials, from, to, page, PAGE_SIZE);
      if (cobList.cobs() != null) {
        cobList.cobs().forEach(cob -> out.add(toCharge(cob)));
      }
      int pages =
          cobList.parametros() == null || cobList.parametros().paginacao() == null
              ? 1
              : cobList.parametros().paginacao().quantidadeDePaginas();
      if (++page >= pages) {
        break;
      }
    }
    return out;
  }

  @Override
  public ProviderWebhookEvent parseWebhook(byte[] body) {
    WebhookPayload webhookPayload;
    try {
      webhookPayload = mapper.readValue(body, WebhookPayload.class);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("unreadable Itaú webhook", e);
    }
    if (webhookPayload == null || webhookPayload.pix() == null) {
      throw new IllegalArgumentException("Itaú webhook without pix[]");
    }

    List<ReceivedPix> received = new ArrayList<>();
    List<RefundResult> refunds = new ArrayList<>();
    Map<String, String> txids = new HashMap<>();
    Map<String, String> refundE2e = new HashMap<>();

    for (PixItem item : webhookPayload.pix()) {

      if (item == null || item.endToEndId() == null) {
        // endToEndId is the dedup key; an item without it cannot be recorded or matched.
        LOG.warn(
            "Itaú webhook item without endToEndId skipped (txid={})",
            item == null ? null : item.txid());
        continue;
      }

      received.add(toReceived(item));

      if (item.txid() != null) {
        txids.put(item.endToEndId(), item.txid());
      }

      if (item.devolucoes() != null) {
        item.devolucoes()
            .forEach(
                devolucao -> {
                  refunds.add(toRefund(devolucao));
                  if (devolucao.id() != null) {
                    refundE2e.put(devolucao.id(), item.endToEndId());
                  }
                });
      }
    }
    return new ProviderWebhookEvent(received, refunds, txids, refundE2e);
  }

  static Charge toCharge(CobResponse response) {
    if (response.valor() == null || response.valor().original() == null) {
      throw new ProviderException(
          ProviderException.Code.UNKNOWN,
          200,
          null,
          "Itaú cob without valor.original: " + response.txid());
    }

    List<ReceivedPix> pix =
        response.pix() == null
            ? List.of()
            : response.pix().stream().map(ItauPixProvider::toReceived).toList();
    Instant created = response.calendario() == null ? null : response.calendario().criacao();
    int exp =
        response.calendario() == null || response.calendario().expiracao() == null
            ? 0
            : response.calendario().expiracao();

    return new Charge(
        response.txid(),
        toStatus(response.status()),
        PixAmounts.fromItau(response.valor().original()),
        response.pixCopiaECola(),
        response.location(),
        created,
        exp,
        pix);
  }

  static ReceivedPix toReceived(PixItem item) {
    return new ReceivedPix(
        item.endToEndId(), PixAmounts.fromItau(item.valor()), item.horario(), item.infoPagador());
  }

  static RefundResult toRefund(DevolucaoResponse devolucaoResponse) {
    RefundStatus refundStatus =
        switch (devolucaoResponse.status() == null ? "" : devolucaoResponse.status()) {
          case "DEVOLVIDO" -> RefundStatus.COMPLETED;
          case "NAO_REALIZADO" -> RefundStatus.FAILED;
          default -> RefundStatus.PROCESSING;
        };

    Instant requested =
        devolucaoResponse.horario() == null ? null : devolucaoResponse.horario().solicitacao();
    Instant settled =
        devolucaoResponse.horario() == null ? null : devolucaoResponse.horario().liquidacao();
    // motivo is only a failure reason when the refund failed; on DEVOLVIDO the bank fills it with
    // prose.
    return new RefundResult(
        devolucaoResponse.id(),
        refundStatus,
        PixAmounts.fromItau(devolucaoResponse.valor()),
        refundStatus == RefundStatus.FAILED ? devolucaoResponse.motivo() : null,
        requested,
        settled);
  }

  /**
   * Both spellings: the OpenAPI enum says REMOVIDA_…, the portal prose says REMOVIDO_… (NOTES.md).
   */
  static ChargeStatus toStatus(String refundStatus) {
    return switch (refundStatus == null ? "" : refundStatus) {
      case "ATIVA" -> ChargeStatus.ACTIVE;
      case "CONCLUIDA" -> ChargeStatus.COMPLETED;
      case "REMOVIDA_PELO_USUARIO_RECEBEDOR", "REMOVIDO_PELO_USUARIO_RECEBEDOR" ->
          ChargeStatus.REMOVED_BY_MERCHANT;
      case "REMOVIDA_PELO_PSP", "REMOVIDO_PELO_PSP" -> ChargeStatus.REMOVED_BY_PSP;
      default ->
          throw new ProviderException(
              ProviderException.Code.UNKNOWN, 200, null, "unknown charge status: " + refundStatus);
    };
  }
}
