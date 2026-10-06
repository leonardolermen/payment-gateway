package com.gateway.billing.customer.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.billing.customer.Customer;
import com.gateway.billing.customer.CustomerFactory;
import com.gateway.billing.customer.CustomerService;
import com.gateway.billing.support.BillingIntegrationTestBase;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CustomerRepositoryListIntegrationTest extends BillingIntegrationTestBase {
  @Autowired CustomerService customers;
  @Autowired CustomerRepository repository;

  Customer created(MerchantId owner, ProviderEnvironment environment, String name, String cpf) {
    return customers.create(
        CustomerFactory.fromRequest(owner, environment, name, cpf, null, null, clock));
  }

  @Test
  void namesLeaveOutDeletedCustomersAndOtherMerchants() {
    Customer ana = created(merchant, ProviderEnvironment.TEST, "Ana Silva", "529.982.247-25");
    Customer joao = created(merchant, ProviderEnvironment.TEST, "Joao Souza", "111.444.777-35");
    Customer foreign =
        created(MerchantId.next(), ProviderEnvironment.TEST, "Outra Loja", "529.982.247-25");
    customers.delete(merchant, joao.id());

    assertThat(repository.activeNames(merchant, List.of(ana.id(), joao.id(), foreign.id())))
        .containsExactlyEntriesOf(java.util.Map.of(ana.id(), "Ana Silva"));
    assertThat(repository.activeNames(merchant, List.of())).isEmpty();
  }

  @Test
  void listsActiveCustomersOfOneEnvironmentNewestFirstByCursor() {
    Customer ana = created(merchant, ProviderEnvironment.TEST, "Ana Silva", "529.982.247-25");
    Customer joao = created(merchant, ProviderEnvironment.TEST, "Joao Souza", "111.444.777-35");
    Customer maria = created(merchant, ProviderEnvironment.TEST, "Maria Lima", "390.533.447-05");
    Customer deleted = created(merchant, ProviderEnvironment.TEST, "Ex Cliente", "153.509.460-56");
    created(merchant, ProviderEnvironment.LIVE, "Ana Live", "529.982.247-25");
    customers.delete(merchant, deleted.id());

    List<Customer> first = repository.listActive(merchant, ProviderEnvironment.TEST, null, 2);
    List<Customer> second =
        repository.listActive(merchant, ProviderEnvironment.TEST, first.getLast().id(), 2);

    assertThat(first).map(Customer::id).containsExactly(maria.id(), joao.id());
    assertThat(second).map(Customer::id).containsExactly(ana.id());
    assertThat(first.getFirst().document().digits()).isEqualTo("39053344705");
  }
}
