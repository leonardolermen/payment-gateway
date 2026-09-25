package com.gateway.payments.payment.create;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.party.Payer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every message here is the one {@code PaymentService.validatePayer} answered before the fourteen ifs
 * became typed constructions. They are contract: a merchant handles the code and reads the field name,
 * so this test exists to make a change to either of them deliberate.
 */
class PayerFactoryTest {

  private static PayerData complete() {
    return new PayerData("Ana Silva", "529.982.247-25",
        new PayerData.AddressData("Av. Paulista 1000", "Bela Vista", "Sao Paulo", "sp", "01310-100"));
  }

  private static PayerData with(String name, String document, PayerData.AddressData address) {
    return new PayerData(name, document, address);
  }

  private static PayerData.AddressData address(String street, String district, String city, String state, String zip) {
    return new PayerData.AddressData(street, district, city, state, zip);
  }

  @Test
  void buildsANormalisedPayer() {
    Payer payer = PayerFactory.from(complete());

    assertThat(payer.name().value()).isEqualTo("Ana Silva");
    assertThat(payer.document().digits()).isEqualTo("52998224725");
    assertThat(payer.address().street()).isEqualTo("Av. Paulista 1000");
    assertThat(payer.address().state().value()).isEqualTo("SP");
    assertThat(payer.address().zip().digits()).isEqualTo("01310100");
  }

  @Test
  void aCnpjIsAcceptedAsWell() {
    PayerData company = with("Acme LTDA", "11.222.333/0001-81", complete().address());

    assertThat(PayerFactory.from(company).document().digits()).isEqualTo("11222333000181");
  }

  @Test
  void aMissingPayerNamesTheMethodItIsRequiredFor() {
    assertThatThrownBy(() -> PayerFactory.from(null))
        .isInstanceOf(DomainException.class)
        .hasMessage("customer is required for a BOLECODE payment")
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("CUSTOMER_REQUIRED");
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("invalidFields")
  void eachInvalidFieldIsNamedInTheMessage(PayerData data, String expectedMessage) {
    assertThatThrownBy(() -> PayerFactory.from(data))
        .isInstanceOf(DomainException.class)
        .hasMessage(expectedMessage)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("CUSTOMER_REQUIRED");
  }

  static Stream<Arguments> invalidFields() {
    PayerData.AddressData good = complete().address();

    return Stream.of(
        arguments(with(null, "52998224725", good), "customer.name is required"),
        arguments(with("123", "52998224725", good), "customer.name is required"),
        arguments(with("Ana", "123", good), "customer.document must be a CPF (11 digits) or CNPJ (14 digits)"),
        arguments(with("Ana", null, good), "customer.document must be a CPF (11 digits) or CNPJ (14 digits)"),
        arguments(with("Ana", "52998224725", null), "customer.address is required"),
        arguments(with("Ana", "52998224725", address(" ", "Bela Vista", "Sao Paulo", "SP", "01310100")),
            "customer.address.street is required"),
        arguments(with("Ana", "52998224725", address("Av. Paulista", null, "Sao Paulo", "SP", "01310100")),
            "customer.address.district is required"),
        arguments(with("Ana", "52998224725", address("Av. Paulista", "Bela Vista", "", "SP", "01310100")),
            "customer.address.city is required"),
        arguments(with("Ana", "52998224725", address("Av. Paulista", "Bela Vista", "Sao Paulo", "XYZ", "01310100")),
            "customer.address.state must be a two-letter UF"),
        arguments(with("Ana", "52998224725", address("Av. Paulista", "Bela Vista", "Sao Paulo", "SP", "1310100")),
            "customer.address.zip must be 8 digits"));
  }
}
