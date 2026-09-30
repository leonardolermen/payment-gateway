package com.gateway.payments.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.card.CardBrand;
import com.gateway.kernel.provider.card.CardOnFileUsage;
import com.gateway.kernel.provider.card.CardToken;
import com.gateway.kernel.security.Secret;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class SavedCardsIntegrationTest extends ServiceIntegrationTestBase {
  static final String TOKEN = "db62dc71-d07b-4745-9969-42697b988ccb";

  @Autowired SavedCards savedCards;
  @Autowired TransactionTemplate paymentsTransactionTemplate;

  SavedCard saveOne(MerchantId owner) {
    return paymentsTransactionTemplate.execute(
        status ->
            savedCards.save(
                owner,
                "CIELO",
                ProviderEnvironment.TEST,
                TOKEN,
                CardBrand.VISA,
                "3171",
                YearMonth.of(2030, 12),
                "JOAO DA SILVA",
                null));
  }

  @Test
  void theTokenIsStoredEncryptedAndComesBackAsAUsedToken() {
    SavedCard saved = saveOne(merchant);

    byte[] stored =
        jdbc.queryForObject(
            "SELECT token_ciphertext FROM payments.cards WHERE id = ?", byte[].class, saved.id());
    assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1))
        .doesNotContain("db62dc71");

    CardToken token =
        savedCards.tokenFor(merchant, ProviderEnvironment.TEST, saved.id(), Secret.of("123"));
    assertThat(token.value()).isEqualTo(TOKEN);
    assertThat(token.brand()).isEqualTo(CardBrand.VISA);
    assertThat(token.usage()).isEqualTo(CardOnFileUsage.USED);
  }

  @Test
  void getShowsTheFaceOfTheCard() {
    SavedCard saved = saveOne(merchant);

    SavedCard read = savedCards.get(merchant, saved.id());

    assertThat(read.brand()).isEqualTo(CardBrand.VISA);
    assertThat(read.last4()).isEqualTo("3171");
    assertThat(read.expiry()).isEqualTo(YearMonth.of(2030, 12));
    assertThat(read.holder()).isEqualTo("JOAO DA SILVA");
    assertThat(read.toString()).doesNotContain(TOKEN);
  }

  /** Spec §4: another merchant's card_id is NOT_FOUND, never 403 — existence does not leak. */
  @Test
  void anotherMerchantsCardDoesNotExist() {
    SavedCard saved = saveOne(MerchantId.next());

    assertThatThrownBy(() -> savedCards.get(merchant, saved.id()))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () ->
                savedCards.tokenFor(
                    merchant, ProviderEnvironment.TEST, saved.id(), Secret.of("123")))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("CARD_NOT_FOUND");
  }

  /**
   * A TEST key must not reach a card stored under LIVE: the token belongs to the other MerchantId.
   */
  @Test
  void anotherEnvironmentsCardIsNotFoundForCharging() {
    SavedCard saved = saveOne(merchant);

    assertThatThrownBy(
            () ->
                savedCards.tokenFor(
                    merchant, ProviderEnvironment.LIVE, saved.id(), Secret.of("123")))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("CARD_NOT_FOUND");
  }

  /** The Cielo has no token deletion (spec §4): deleting is ours, and final. */
  @Test
  void aDeletedCardIsGoneForEveryone() {
    SavedCard saved = saveOne(merchant);

    savedCards.delete(merchant, saved.id());

    assertThatThrownBy(() -> savedCards.get(merchant, saved.id()))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () ->
                savedCards.tokenFor(
                    merchant, ProviderEnvironment.TEST, saved.id(), Secret.of("123")))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> savedCards.delete(merchant, saved.id()))
        .isInstanceOf(NotFoundException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM payments.cards WHERE id = ?",
                Boolean.class,
                saved.id()))
        .isTrue();
  }
}
