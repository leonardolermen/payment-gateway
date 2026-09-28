package com.gateway.providers.cielo.sale;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.providers.cielo.CieloErrors;
import com.gateway.providers.cielo.CieloHttp;
import com.gateway.providers.cielo.auth.CieloCredentials;
import com.gateway.providers.cielo.auth.CieloEndpoints;
import com.gateway.providers.cielo.sale.dto.SaleRequest;
import com.gateway.providers.cielo.sale.dto.SaleResponse;
import com.gateway.providers.cielo.sale.dto.SaleUpdateResponse;
import com.gateway.providers.cielo.sale.dto.SalesByOrderResponse;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Cielo's sales resource for one environment. Writes go to the transactional host, reads to the
 * query host.
 */
public class CieloSalesClient {
  private static final Logger LOG = LoggerFactory.getLogger(CieloSalesClient.class);

  private final CieloHttp http;
  private final CieloEndpoints endpoints;

  public CieloSalesClient(CieloHttp http, CieloEndpoints endpoints) {
    this.http = http;
    this.endpoints = endpoints;
  }

  /** 201 for every business answer, a decline included (reference/api-codes). */
  public SaleResponse authorize(CieloCredentials credentials, SaleRequest request) {
    HttpResponse<String> response =
        http.send(credentials, http.request(endpoints.api(), "/1/sales").POST(http.json(request)));

    if (response.statusCode() != 201 && response.statusCode() != 200) {
      throw CieloErrors.from(response.statusCode(), response.body());
    }

    return http.read(response, SaleResponse.class);
  }

  /**
   * Empty amount captures everything. "Esse modelo de captura pode ocorrer apenas uma vez por
   * transação" — the second call is the Cielo's to refuse (308), not ours to retry.
   */
  public SaleUpdateResponse capture(
      CieloCredentials credentials, String paymentId, Optional<Money> amount) {
    return update(credentials, "/1/sales/" + segment(paymentId) + "/capture" + amountQuery(amount));
  }

  /** Before capture only total; after capture a refund, partial allowed and repeatable. */
  public SaleUpdateResponse voidSale(
      CieloCredentials credentials, String paymentId, Optional<Money> amount) {
    return update(credentials, "/1/sales/" + segment(paymentId) + "/void" + amountQuery(amount));
  }

  public Optional<SaleResponse> findByPaymentId(CieloCredentials credentials, String paymentId) {
    HttpResponse<String> response =
        http.send(
            credentials,
            http.request(endpoints.apiQuery(), "/1/sales/" + segment(paymentId)).GET());

    if (response.statusCode() == 200) {
      return Optional.of(http.read(response, SaleResponse.class));
    }

    ProviderException failure = CieloErrors.from(response.statusCode(), response.body());
    if (CieloErrors.isTransactionNotFound(failure)) {
      return Optional.empty();
    }
    throw failure;
  }

  /**
   * Newest first: after a timeout the gateway sent one sale per MerchantOrderId, but a retry of the
   * same order by hand (the docs' 24 h reuse rule) must not make it adopt the older one.
   */
  public List<String> findPaymentIdsByOrder(CieloCredentials credentials, String merchantOrderId) {
    String query =
        "/1/sales?merchantOrderId=" + URLEncoder.encode(merchantOrderId, StandardCharsets.UTF_8);
    HttpResponse<String> response =
        http.send(credentials, http.request(endpoints.apiQuery(), query).GET());

    if (response.statusCode() != 200) {
      ProviderException failure = CieloErrors.from(response.statusCode(), response.body());
      if (CieloErrors.isTransactionNotFound(failure)) {
        return List.of();
      }
      throw failure;
    }

    SalesByOrderResponse sales = http.read(response, SalesByOrderResponse.class);
    if (sales.payments() == null) {
      return List.of();
    }

    return sales.payments().stream()
        .sorted(
            Comparator.comparing((SalesByOrderResponse.Item item) -> received(item.receivedDate()))
                .reversed())
        .map(SalesByOrderResponse.Item::paymentId)
        .toList();
  }

  private SaleUpdateResponse update(CieloCredentials credentials, String pathAndQuery) {
    HttpResponse<String> response =
        http.send(
            credentials,
            http.request(endpoints.api(), pathAndQuery).PUT(HttpRequest.BodyPublishers.noBody()));

    if (response.statusCode() != 200) {
      throw CieloErrors.from(response.statusCode(), response.body());
    }

    return http.read(response, SaleUpdateResponse.class);
  }

  /**
   * An unreadable ReceveidDate sorts last instead of throwing: this list feeds the in-doubt
   * recovery and the sweep, and one malformed date from the Cielo would otherwise fail every lookup
   * of that order — the sale stuck in doubt over a field used only for ordering.
   */
  private static Instant received(String date) {
    try {
      Instant parsed = CieloDates.parse(date);
      return parsed == null ? Instant.EPOCH : parsed;
    } catch (DateTimeParseException e) {
      LOG.warn("Cielo ReceveidDate {} is unreadable; sorting that sale last", date);
      return Instant.EPOCH;
    }
  }

  private static String amountQuery(Optional<Money> amount) {
    return amount.map(money -> "?amount=" + money.cents()).orElse("");
  }

  private static String segment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
