package com.gateway.app.api.payment.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.payment.create.CreateBolecodePayment;
import com.gateway.payments.payment.create.CreateCardPayment;
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

  static final String CARD_BODY =
      "{\"method\":\"CARD\",\"amount\":12990,\"currency\":\"BRL\",\"reference\":\"order-42\","
          + "\"description\":\"Pedido 42\",\"soft_descriptor\":\"LOJA42\","
          + "\"card\":{\"number\":\"4024007153763171\",\"holder\":\"JOAO DA SILVA\",\"expiry\":\"12/2030\","
          + "\"cvv\":\"123\",\"brand\":\"VISA\"},"
          + "\"installments\":3,\"capture\":true,\"save_card\":true,"
          + "\"customer\":{\"name\":\"Joao da Silva\",\"document\":\"12345678901\",\"email\":\"joao@example.com\"}}";

  /** Spec §9's own example body. */
  @Test
  void aCardBodyDeserialisesToTheCardShapeAndBuildsTheCommand() {
    CreatePaymentRequest request = read(CARD_BODY);

    assertThat(request).isInstanceOf(CardPaymentRequest.class);
    request.validate();
    CreateCardPayment command =
        (CreateCardPayment) request.toCommand(MerchantId.next(), ProviderEnvironment.TEST);
    assertThat(command.card()).isInstanceOf(CardChoice.NewCard.class);
    assertThat(((CardChoice.NewCard) command.card()).save()).isTrue();
    assertThat(((CardChoice.NewCard) command.card()).card().last4()).isEqualTo("3171");
    assertThat(command.installments()).isEqualTo(3);
    assertThat(command.softDescriptor()).isEqualTo("LOJA42");
    assertThat(command.customer().email()).isEqualTo("joao@example.com");
  }

  @Test
  void aCardRequestNeverPrintsTheCard() {
    assertThat(read(CARD_BODY).toString())
        .doesNotContain("4024007153763171")
        .doesNotContain("\"123\"")
        .doesNotContain("cvv=123");
  }

  @Test
  void cardAndCardIdAreExclusive() {
    CreatePaymentRequest both =
        read(
            CARD_BODY.replace("\"installments\":3", "\"card_id\":\"01K0CARD\",\"installments\":3"));
    CreatePaymentRequest neither =
        read(
            "{\"method\":\"CARD\",\"amount\":100,\"currency\":\"BRL\",\"customer\":{\"name\":\"Ana\"}}");

    assertThatThrownBy(both::validate)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("exactly one of card and card_id is required");
    assertThatThrownBy(neither::validate)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("exactly one of card and card_id is required");
  }

  /** Plan D3: with card_id the cvv is required. */
  @Test
  void aCardIdWithoutCvvIsCardInvalid() {
    CreatePaymentRequest request =
        read(
            "{\"method\":\"CARD\",\"amount\":100,\"currency\":\"BRL\",\"card_id\":\"01K0CARD\","
                + "\"customer\":{\"name\":\"Ana\"}}");
    request.validate();

    assertThatThrownBy(() -> request.toCommand(MerchantId.next(), ProviderEnvironment.TEST))
        .isInstanceOf(DomainException.class)
        .hasMessage("cvv is required with card_id");
  }

  @Test
  void aBadCardNumberIsCardInvalidNamingTheField() {
    CreatePaymentRequest request = read(CARD_BODY.replace("4024007153763171", "4024007153763172"));

    assertThatThrownBy(() -> request.toCommand(MerchantId.next(), ProviderEnvironment.TEST))
        .isInstanceOf(DomainException.class)
        .satisfies(
            thrown -> {
              assertThat(((DomainException) thrown).code()).isEqualTo("CARD_INVALID");
              assertThat(thrown.getMessage()).isEqualTo("card.number must pass the Luhn check");
            });
  }

  @Test
  void aPixFieldInACardBodyIsRefused() {
    assertThatThrownBy(() -> read(CARD_BODY.replace("\"installments\":3", "\"expires_in\":60")))
        .isInstanceOf(JacksonException.class);
  }
}
