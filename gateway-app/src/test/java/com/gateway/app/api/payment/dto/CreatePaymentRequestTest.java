package com.gateway.app.api.payment.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.create.CreateBolecodePayment;
import com.gateway.payments.payment.create.CreatePixPayment;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * The request body is polymorphic on {@code method}, so the deserialiser is what refuses a field of
 * the other method — there is no longer a validate() comparing method strings. These tests pin
 * that, and pin the messages each shape still answers for its own fields.
 */
class CreatePaymentRequestTest {
  /** Same two settings as application.yml: snake_case, and an unknown property is an error. */
  private final JsonMapper mapper =
      JsonMapper.builder()
          .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  private CreatePaymentRequest read(String json) {
    return mapper.readValue(json, CreatePaymentRequest.class);
  }

  @Test
  void aPixBodyDeserialisesToThePixShape() {
    CreatePaymentRequest request =
        read(
            "{\"method\":\"PIX\",\"amount\":1000,\"currency\":\"BRL\",\"reference\":\"order-1\",\"expires_in\":3600}");

    assertThat(request).isInstanceOf(PixPaymentRequest.class);
    assertThat(request.method()).isEqualTo(PaymentMethod.PIX);
    assertThat(((PixPaymentRequest) request).expiresIn()).isEqualTo(3600);
  }

  @Test
  void aBolecodeBodyDeserialisesToTheBolecodeShape() {
    CreatePaymentRequest request =
        read(
            "{\"method\":\"BOLECODE\",\"amount\":1000,\"currency\":\"BRL\",\"due_date\":\"2026-10-01\","
                + "\"payment_limit_days\":30,\"customer\":{\"name\":\"Ana\",\"document\":\"52998224725\"}}");

    assertThat(request).isInstanceOf(BolecodePaymentRequest.class);
    assertThat(request.method()).isEqualTo(PaymentMethod.BOLECODE);
    assertThat(((BolecodePaymentRequest) request).dueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
  }

  @Test
  void aBolecodeFieldInAPixBodyIsRefused() {
    assertThatThrownBy(
            () ->
                read(
                    "{\"method\":\"PIX\",\"amount\":1000,\"currency\":\"BRL\",\"due_date\":\"2026-10-01\"}"))
        .isInstanceOf(JacksonException.class);
    assertThatThrownBy(
            () ->
                read(
                    "{\"method\":\"PIX\",\"amount\":1000,\"currency\":\"BRL\",\"payment_limit_days\":30}"))
        .isInstanceOf(JacksonException.class);
  }

  @Test
  void aPixFieldInABolecodeBodyIsRefused() {
    assertThatThrownBy(
            () ->
                read(
                    "{\"method\":\"BOLECODE\",\"amount\":1000,\"currency\":\"BRL\",\"expires_in\":3600}"))
        .isInstanceOf(JacksonException.class);
  }

  @Test
  void anUnknownMethodIsRefused() {
    assertThatThrownBy(() -> read("{\"method\":\"CARTAO\",\"amount\":1000,\"currency\":\"BRL\"}"))
        .isInstanceOf(JacksonException.class);
  }

  @Test
  void aMissingMethodIsRefused() {
    assertThatThrownBy(() -> read("{\"amount\":1000,\"currency\":\"BRL\"}"))
        .isInstanceOf(JacksonException.class);
  }

  @Test
  void amountAndCurrencyKeepTheMessagesTheyHaveAlwaysAnswered() {
    assertThatThrownBy(() -> new PixPaymentRequest(0L, "BRL", null, null, null, null).validate())
        .hasMessage("amount must be a positive number of cents");
    assertThatThrownBy(() -> new PixPaymentRequest(null, "BRL", null, null, null, null).validate())
        .hasMessage("amount must be a positive number of cents");
    assertThatThrownBy(() -> new PixPaymentRequest(1000L, "USD", null, null, null, null).validate())
        .hasMessage("currency must be BRL");
    assertThatThrownBy(
            () -> new BolecodePaymentRequest(1000L, null, null, null, null, null, null).validate())
        .hasMessage("currency must be BRL");
  }

  @Test
  void eachShapeValidatesOnlyItsOwnField() {
    assertThatThrownBy(() -> new PixPaymentRequest(1000L, "BRL", null, null, null, -1).validate())
        .hasMessage("expires_in must be positive seconds");
    assertThatThrownBy(
            () -> new BolecodePaymentRequest(1000L, "BRL", null, null, null, null, -1).validate())
        .hasMessage("payment_limit_days must not be negative");
  }

  @Test
  void aPixBodyBecomesAPixCommandAndCarriesOnlyTheCustomerDocument() {
    MerchantId merchant = MerchantId.next();
    Customer customer = new Customer("Ana", "529.982.247-25", null);

    CreatePixPayment command =
        (CreatePixPayment)
            new PixPaymentRequest(1000L, "BRL", "order-1", "Pedido", customer, 3600)
                .toCommand(merchant, ProviderEnvironment.TEST);

    assertThat(command.merchantId()).isEqualTo(merchant);
    assertThat(command.environment()).isEqualTo(ProviderEnvironment.TEST);
    assertThat(command.amount().cents()).isEqualTo(1000);
    assertThat(command.customerDocument()).isEqualTo("529.982.247-25");
    assertThat(command.expiresInSeconds()).isEqualTo(3600);
  }

  @Test
  void aBolecodeBodyBecomesABolecodeCommandWithTheRawPayer() {
    Customer customer =
        new Customer(
            "Ana",
            "52998224725",
            new Address("Av. Paulista", "Bela Vista", "Sao Paulo", "sp", "01310-100"));

    CreateBolecodePayment command =
        (CreateBolecodePayment)
            new BolecodePaymentRequest(
                    1000L, "BRL", "order-1", "Pedido", customer, LocalDate.of(2026, 10, 1), 30)
                .toCommand(MerchantId.next(), ProviderEnvironment.LIVE);

    // Copied, not normalised: the domain validates it and names the field if it is wrong.
    assertThat(command.payer().address().state()).isEqualTo("sp");
    assertThat(command.payer().address().zip()).isEqualTo("01310-100");
    assertThat(command.paymentLimitDays()).isEqualTo(30);
  }

  @Test
  void aBodyWithNoCustomerBecomesACommandWithNoPayer() {
    CreateBolecodePayment command =
        (CreateBolecodePayment)
            new BolecodePaymentRequest(1000L, "BRL", null, null, null, null, null)
                .toCommand(MerchantId.next(), ProviderEnvironment.TEST);

    assertThat(command.payer()).isNull();
  }
}
