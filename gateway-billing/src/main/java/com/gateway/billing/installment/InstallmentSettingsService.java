package com.gateway.billing.installment;

import com.gateway.billing.BillingEvents;
import com.gateway.billing.installment.persistence.InstallmentSettingsRepository;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.UnitOfWork;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The merchant's installment settings and the prices they give (spec 2026-10-07). Always per
 * environment: the caller passes the one of the API key, or of the order, never one from a body.
 */
public class InstallmentSettingsService {
  public static final String UPDATED = "installment_settings.updated";

  private final InstallmentSettingsRepository settings;
  private final BillingEvents events;
  private final UnitOfWork unitOfWork;
  private final Clock clock;

  public InstallmentSettingsService(
      InstallmentSettingsRepository settings,
      BillingEvents events,
      UnitOfWork unitOfWork,
      Clock clock) {
    this.settings = settings;
    this.events = events;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  /** The saved settings, or the default (12, all interest-free) when there are none. */
  public InstallmentSettings get(MerchantId merchantId, ProviderEnvironment environment) {
    return settings
        .find(merchantId, environment)
        .orElseGet(() -> InstallmentSettings.defaults(merchantId, environment));
  }

  /**
   * Replaces the settings and records who did it in the outbox, in one transaction. A range error
   * is an IllegalArgumentException (400) before anything is written. {@code apiKeyId} is the key
   * that made the change, so the merchant can audit who changed the price.
   */
  public InstallmentSettings update(
      MerchantId merchantId,
      ProviderEnvironment environment,
      int maxInstallments,
      int interestFreeUpTo,
      int monthlyRateBps,
      String apiKeyId) {
    // Micros: Postgres keeps no more, and the response must equal what a GET reads back.
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    InstallmentSettings changed =
        new InstallmentSettings(
            merchantId, environment, maxInstallments, interestFreeUpTo, monthlyRateBps, now);

    unitOfWork.run(
        () -> {
          settings.save(changed);
          // aggregate_id is CHAR(26): the merchant's id; the partition keeps the changes in order.
          events.emit(
              merchantId,
              UPDATED,
              merchantId.value(),
              "installment_settings:" + merchantId.value(),
              json(changed, apiKeyId));
        });

    return changed;
  }

  /** The options an order of {@code amount} offers under the merchant's settings. */
  public List<InstallmentOption> options(
      MerchantId merchantId, ProviderEnvironment environment, long amount) {
    return InstallmentPricing.options(amount, get(merchantId, environment));
  }

  /** The {@code installment_settings.updated} payload; public for the README catalog test. */
  public static Map<String, Object> json(InstallmentSettings settings, String apiKeyId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("environment", settings.environment().name());
    body.put("max_installments", settings.maxInstallments());
    body.put("interest_free_up_to", settings.interestFreeUpTo());
    body.put("monthly_rate_bps", settings.monthlyRateBps());
    body.put("api_key_id", apiKeyId);
    body.put("updated_at", settings.updatedAt() == null ? null : settings.updatedAt().toString());

    return body;
  }
}
