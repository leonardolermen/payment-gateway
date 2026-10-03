package com.gateway.app.api.customer.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.billing.customer.Customer;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** The DTO only copies; the 422 and its field name come from the factory and the value objects. */
class CustomerRequestTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC);
  private static final MerchantId MERCHANT = new MerchantId("01HZZZZZZZZZZZZZZZZZZZZZZZ");

  @Test
  void aCompleteRequestBecomesACustomer() {
    CustomerRequest request =
        new CustomerRequest(
            "Ana Souza",
            "529.982.247-25",
            "ana@example.com",
            new AddressFields("Rua A, 1", "Centro", "Sao Paulo", "sp", "01001-000"));

    Customer customer = request.toCustomer(MERCHANT, ProviderEnvironment.TEST, CLOCK);

    assertThat(customer.document().digits()).isEqualTo("52998224725");
    assertThat(customer.address().state().value()).isEqualTo("SP");
  }

  @Test
  void aPartialAddressNamesTheMissingField() {
    CustomerRequest request =
        new CustomerRequest(
            "Ana Souza",
            "52998224725",
            null,
            new AddressFields("Rua A, 1", "Centro", "Sao Paulo", "SP", null));

    assertThatThrownBy(() -> request.toCustomer(MERCHANT, ProviderEnvironment.TEST, CLOCK))
        .isInstanceOf(DomainException.class)
        .hasMessageStartingWith("customer.address.zip");
  }

  @Test
  void aMissingStreetNamesTheField() {
    CustomerRequest request =
        new CustomerRequest(
            "Ana Souza",
            "52998224725",
            null,
            new AddressFields(null, "Centro", "Sao Paulo", "SP", "01001000"));

    assertThatThrownBy(() -> request.toCustomer(MERCHANT, ProviderEnvironment.TEST, CLOCK))
        .isInstanceOf(DomainException.class)
        .hasMessage("customer.address.street is required");
  }

  @Test
  void noAddressIsAllowed() {
    CustomerRequest request = new CustomerRequest("Ana Souza", "52998224725", null, null);

    assertThat(request.toCustomer(MERCHANT, ProviderEnvironment.TEST, CLOCK).address()).isNull();
  }
}
