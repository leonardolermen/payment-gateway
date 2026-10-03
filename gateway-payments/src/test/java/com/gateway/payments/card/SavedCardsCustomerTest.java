package com.gateway.payments.card;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.card.CardOnFileUsage;
import com.gateway.kernel.provider.card.CardToken;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.payment.create.CustomerDocumentHash;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SavedCardsCustomerTest extends ServiceIntegrationTestBase {
  @Autowired SavedCards savedCards;
  @Autowired UnitOfWork unitOfWork;

  Payment savedCardPayment() {
    return paymentService.create(
        cardCommand(1000, new CardChoice.NewCard(card(APPROVES), true), null, null));
  }

  @Test
  void aCardSavedBeforeTheCustomerExistedIsAdoptedByDocumentHash() {
    Payment payment = savedCardPayment();
    String customerId = Ulid.next();

    // Adoption is MANDATORY-transactional: in production CustomerService's insert provides it.
    int adopted =
        unitOfWork.inTransaction(
            () ->
                savedCards.adoptByDocumentHash(
                    merchant,
                    ProviderEnvironment.TEST,
                    CustomerDocumentHash.of("12345678901"),
                    customerId));

    assertThat(adopted).isEqualTo(1);
    assertThat(savedCards.listByCustomer(merchant, customerId))
        .extracting(SavedCard::id)
        .containsExactly(payment.card().cardId());
  }

  @Test
  void aRecurringTokenCarriesNoSecurityCode() {
    Payment payment = savedCardPayment();

    CardToken token =
        savedCards.tokenForRecurring(merchant, ProviderEnvironment.TEST, payment.card().cardId());

    assertThat(token.securityCode()).isNull();
    assertThat(token.usage()).isEqualTo(CardOnFileUsage.USED);
  }
}
