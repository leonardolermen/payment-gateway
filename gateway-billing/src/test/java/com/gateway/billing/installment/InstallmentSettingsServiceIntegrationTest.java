package com.gateway.billing.installment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.billing.support.BillingIntegrationTestBase;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class InstallmentSettingsServiceIntegrationTest extends BillingIntegrationTestBase {
  @Autowired InstallmentSettingsService installments;

  @Test
  void noRowIsTheDefault() {
    InstallmentSettings settings = installments.get(merchant, ProviderEnvironment.TEST);

    assertThat(settings)
        .isEqualTo(InstallmentSettings.defaults(merchant, ProviderEnvironment.TEST));
    assertThat(settings.updatedAt()).isNull();
  }

  @Test
  void anUpdateIsReadBackAndASecondOneReplacesIt() {
    installments.update(merchant, ProviderEnvironment.TEST, 10, 3, 299, "key-1");
    InstallmentSettings second =
        installments.update(merchant, ProviderEnvironment.TEST, 6, 6, 0, "key-1");

    assertThat(installments.get(merchant, ProviderEnvironment.TEST)).isEqualTo(second);
  }

  @Test
  void environmentsAreApart() {
    installments.update(merchant, ProviderEnvironment.TEST, 10, 3, 299, "key-1");

    assertThat(installments.get(merchant, ProviderEnvironment.LIVE))
        .isEqualTo(InstallmentSettings.defaults(merchant, ProviderEnvironment.LIVE));
  }

  @Test
  void anInvalidUpdateWritesNothing() {
    assertThatThrownBy(
            () -> installments.update(merchant, ProviderEnvironment.TEST, 6, 7, 0, "key-1"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(installments.get(merchant, ProviderEnvironment.TEST).updatedAt()).isNull();
    assertThat(outbox()).isEmpty();
  }

  @Test
  void anUpdateEmitsTheEventWithTheKeyThatMadeIt() {
    InstallmentSettings saved =
        installments.update(merchant, ProviderEnvironment.TEST, 10, 3, 299, "key-1");

    List<Map<String, Object>> rows = outbox();

    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst())
        .containsEntry("event_type", "installment_settings.updated")
        .containsEntry("aggregate_id", merchant.value());
    assertThat((String) rows.getFirst().get("payload"))
        .contains("\"environment\":\"TEST\"")
        .contains("\"max_installments\":10")
        .contains("\"interest_free_up_to\":3")
        .contains("\"monthly_rate_bps\":299")
        .contains("\"api_key_id\":\"key-1\"")
        .contains("\"updated_at\":\"" + saved.updatedAt() + "\"");
  }

  List<Map<String, Object>> outbox() {
    return jdbc.queryForList(
        "SELECT event_type, aggregate_id, payload FROM payments.outbox WHERE merchant_id = ?",
        merchant.value());
  }
}
