package com.gateway.billing.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.billing.BillingEvents;
import com.gateway.billing.customer.persistence.CustomerRepository;
import com.gateway.billing.support.BillingIntegrationTestBase;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.security.Sealer;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.card.SavedCards;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.payment.create.CardCustomerData;
import com.gateway.payments.payment.create.CardDataFactory;
import com.gateway.payments.payment.create.CreateCardPayment;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CustomerServiceIntegrationTest extends BillingIntegrationTestBase {
  @Autowired CustomerService customers;
  @Autowired SavedCards savedCards;
  @Autowired CustomerRepository repository;
  @Autowired ActiveSubscriptionsCheck activeSubscriptions;
  @Autowired BillingEvents events;
  @Autowired Sealer sealer;
  @Autowired UnitOfWork unitOfWork;

  Customer ana() {
    return customers.create(
        CustomerFactory.fromRequest(
            merchant,
            ProviderEnvironment.TEST,
            "Ana Silva",
            "529.982.247-25",
            "ana@example.com",
            null,
            clock));
  }

  @Test
  void createsReadsBackAndEmits() {
    Customer created = ana();

    Customer found = customers.get(merchant, created.id());

    assertThat(found.name().value()).isEqualTo("Ana Silva");
    assertThat(found.document().digits()).isEqualTo("52998224725");
    assertThat(
            jdbc.queryForList(
                "SELECT event_type FROM payments.outbox WHERE aggregate_id = ?",
                String.class,
                created.id()))
        .containsExactly("customer.created");
  }

  @Test
  void theSameDocumentTwiceIsAConflictNamingTheExistingId() {
    Customer first = ana();

    assertThatThrownBy(this::ana)
        .isInstanceOf(DomainException.class)
        .extracting(exception -> ((DomainException) exception).code())
        .isEqualTo("CUSTOMER_EXISTS");
    assertThat(customers.findByDocument(merchant, ProviderEnvironment.TEST, "52998224725"))
        .map(Customer::id)
        .contains(first.id());
  }

  @Test
  void aCardSavedEarlierWithTheSameDocumentIsAdopted() {
    Payment payment =
        paymentService.create(
            new CreateCardPayment(
                merchant,
                ProviderEnvironment.TEST,
                Money.brl(1000),
                "o-1",
                null,
                new CardChoice.NewCard(
                    CardDataFactory.from(
                        "4024007153763171",
                        "ANA SILVA",
                        "12/2030",
                        "123",
                        null,
                        YearMonth.of(2026, 9)),
                    true),
                null,
                null,
                null,
                "LOJA",
                new CardCustomerData("Ana Silva", "52998224725", null),
                null));

    Customer created = ana();

    assertThat(customers.cardsOf(merchant, created.id()))
        .extracting(card -> card.id())
        .containsExactly(payment.card().cardId());
  }

  @Test
  void updateKeepsTheDocumentAndBumpsTheVersion() {
    Customer created = ana();

    Customer updated =
        customers.update(
            merchant,
            created.id(),
            "Ana S.",
            null,
            new CustomerAddress.Raw("Rua A 1", "Centro", "Sao Paulo", "sp", "01310-100"));

    assertThat(updated.version()).isEqualTo(2L);
    assertThat(updated.address().state().value()).isEqualTo("SP");
    assertThat(updated.document()).isEqualTo(created.document());
  }

  @Test
  void deleteReportsAConcurrentChangeInsteadOfDoingNothing() {
    Customer created = ana();
    // The service reads a snapshot, then the row moves on: the window between get and update.
    jdbc.update("UPDATE billing.customers SET version = version + 1 WHERE id = ?", created.id());
    CustomerService racing =
        new CustomerService(
            new StaleReads(repository, created),
            savedCards,
            activeSubscriptions,
            events,
            sealer,
            unitOfWork,
            clock);

    assertThatThrownBy(() -> racing.delete(merchant, created.id()))
        .isInstanceOf(DomainException.class)
        .extracting(exception -> ((DomainException) exception).code())
        .isEqualTo("CONFLICT");
    assertThat(
            jdbc.queryForObject(
                "SELECT deleted_at IS NULL FROM billing.customers WHERE id = ?",
                Boolean.class,
                created.id()))
        .isTrue();
  }

  /** Reads return the snapshot taken before the concurrent write; writes go to the real table. */
  private record StaleReads(CustomerRepository real, Customer snapshot)
      implements CustomerRepository {
    @Override
    public void insert(Customer customer, byte[] documentCiphertext, String documentHash) {
      real.insert(customer, documentCiphertext, documentHash);
    }

    @Override
    public boolean update(Customer customer) {
      return real.update(customer);
    }

    @Override
    public Optional<Customer> findActive(MerchantId merchantId, String id) {
      return Optional.of(snapshot);
    }

    @Override
    public Optional<Customer> findActiveByDocumentHash(
        MerchantId merchantId, ProviderEnvironment environment, String documentHash) {
      return real.findActiveByDocumentHash(merchantId, environment, documentHash);
    }

    @Override
    public List<Customer> listActive(
        MerchantId merchantId, ProviderEnvironment environment, String cursorId, int limit) {
      return real.listActive(merchantId, environment, cursorId, limit);
    }

    @Override
    public Map<String, String> activeNames(MerchantId merchantId, Collection<String> ids) {
      return real.activeNames(merchantId, ids);
    }
  }

  @Test
  void deleteIsLogicalAndHidesTheCustomer() {
    Customer created = ana();

    customers.delete(merchant, created.id());

    assertThatThrownBy(() -> customers.get(merchant, created.id()))
        .isInstanceOf(DomainException.class);
  }
}
