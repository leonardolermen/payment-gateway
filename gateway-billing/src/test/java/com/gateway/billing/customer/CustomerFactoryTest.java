package com.gateway.billing.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class CustomerFactoryTest {
  static final MerchantId MERCHANT = MerchantId.next();

  @Test
  void normalisesTheDocumentAndKeepsTheAddressOptional() {
    Customer customer =
        CustomerFactory.fromRequest(
            MERCHANT,
            ProviderEnvironment.TEST,
            "Ana Silva",
            "529.982.247-25",
            null,
            null,
            Clock.systemUTC());

    assertThat(customer.document().digits()).isEqualTo("52998224725");
    assertThat(customer.address()).isNull();
    assertThat(customer.version()).isEqualTo(1L);
  }

  @Test
  void namesTheFieldTheClientSent() {
    assertThatThrownBy(
            () ->
                CustomerFactory.fromRequest(
                    MERCHANT,
                    ProviderEnvironment.TEST,
                    "Ana",
                    "123",
                    null,
                    new CustomerAddress.Raw("Rua A", "Centro", "SP", "XX", "01310100"),
                    Clock.systemUTC()))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("document");
  }

  @Test
  void anAddressFieldErrorNamesItsPath() {
    assertThatThrownBy(
            () ->
                CustomerFactory.fromRequest(
                    MERCHANT,
                    ProviderEnvironment.TEST,
                    "Ana",
                    "52998224725",
                    null,
                    new CustomerAddress.Raw("Rua A", "Centro", "Sao Paulo", "S1", "01310100"),
                    Clock.systemUTC()))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("address.state");
  }
}
