package com.gateway.app.api.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.FieldDomainException;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.merchants.credential.Provider;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CredentialShapeTest {
  private static final String ITAU_SANDBOX =
      "{\"client_id\":\"sbx-id\",\"client_secret\":\"sbx-secret\",\"pix_key\":\"60701190000104\"}";

  @Test
  void aMinimalItauSandboxCredentialPassesInTest() {
    assertThatCode(() -> validate(Provider.ITAU, ProviderEnvironment.TEST, ITAU_SANDBOX))
        .doesNotThrowAnyException();
  }

  @Test
  void anItauLiveCredentialWithoutThePrivateKeyNamesIt() {
    String withoutKey =
        "{\"client_id\":\"id\",\"client_secret\":\"s\",\"pix_key\":\"k\","
            + "\"x_itau_apikey\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\",\"certificate_pem\":\"c\"}";

    assertThatThrownBy(() -> validate(Provider.ITAU, ProviderEnvironment.LIVE, withoutKey))
        .isInstanceOfSatisfying(
            FieldDomainException.class,
            failure -> {
              assertThat(failure.code()).isEqualTo("PROVIDER_CREDENTIALS_INVALID");
              assertThat(failure.field()).isEqualTo("private_key_pem");
            });
  }

  @Test
  void theSandboxShapeIsNotEnoughInLive() {
    assertThatThrownBy(() -> validate(Provider.ITAU, ProviderEnvironment.LIVE, ITAU_SANDBOX))
        .isInstanceOfSatisfying(
            FieldDomainException.class,
            failure -> assertThat(failure.field()).isEqualTo("certificate_pem"));
  }

  @Test
  void aCieloKeyOfThirtyNineCharactersNamesMerchantKey() {
    String shortKey =
        "{\"merchant_id\":\"11111111-2222-3333-4444-555555555555\",\"merchant_key\":\""
            + "A".repeat(39)
            + "\"}";

    assertThatThrownBy(() -> validate(Provider.CIELO, ProviderEnvironment.TEST, shortKey))
        .isInstanceOfSatisfying(
            FieldDomainException.class,
            failure -> {
              assertThat(failure.field()).isEqualTo("merchant_key");
              assertThat(failure.getMessage()).doesNotContain("AAAA");
            });
  }

  @Test
  void aSecretOfTheWrongTypeIsAFieldErrorNotAFiveHundred() {
    String objectSecret =
        "{\"client_id\":\"id\",\"client_secret\":{\"nested\":true},\"pix_key\":\"k\"}";

    assertThatThrownBy(() -> validate(Provider.ITAU, ProviderEnvironment.TEST, objectSecret))
        .isInstanceOfSatisfying(
            FieldDomainException.class,
            failure -> {
              assertThat(failure.code()).isEqualTo("PROVIDER_CREDENTIALS_INVALID");
              assertThat(failure.getMessage()).isEqualTo("payload has a field of the wrong type");
              assertThat(failure.getMessage()).doesNotContain("nested");
            });
  }

  @Test
  void aKeyOutsideTheProvidersFieldsIsRefusedBeforeTheParserRuns() {
    String misspelled =
        "{\"client_id\":\"id\",\"clientSecret\":\"s\",\"client_secret\":\"s\",\"pix_key\":\"k\"}";
    String trailingSpace =
        "{\"merchant_id\":\"11111111-2222-3333-4444-555555555555\",\"merchant_key \":\"x\"}";

    assertThatThrownBy(() -> validate(Provider.ITAU, ProviderEnvironment.TEST, misspelled))
        .isInstanceOfSatisfying(
            FieldDomainException.class,
            failure -> {
              assertThat(failure.code()).isEqualTo("PROVIDER_CREDENTIALS_INVALID");
              assertThat(failure.field()).isEqualTo("clientSecret");
              assertThat(failure.getMessage()).isEqualTo("clientSecret is not a field of ITAU");
            });
    assertThatThrownBy(() -> validate(Provider.CIELO, ProviderEnvironment.TEST, trailingSpace))
        .isInstanceOfSatisfying(
            FieldDomainException.class,
            failure -> assertThat(failure.field()).isEqualTo("merchant_key "));
  }

  @Test
  void anUnknownProviderIsABadRequest() {
    assertThatThrownBy(() -> validate(Provider.FAKE, ProviderEnvironment.TEST, "{}"))
        .isExactlyInstanceOf(IllegalArgumentException.class)
        .hasMessage("unknown provider");
  }

  private static void validate(Provider provider, ProviderEnvironment environment, String json) {
    CredentialShape.validate(provider, environment, json.getBytes(StandardCharsets.UTF_8));
  }
}
