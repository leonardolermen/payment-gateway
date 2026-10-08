package com.gateway.merchants.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.merchants.TestApp;
import com.gateway.merchants.merchant.Merchant;
import com.gateway.merchants.merchant.MerchantService;
import com.gateway.merchants.user.persistence.UserRepository;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = TestApp.class)
@Testcontainers
class UserRepositoryIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired MerchantService merchants;
  @Autowired UserRepository users;

  @Test
  void insertsFindsByNormalizedEmailAndRefusesADuplicate() {
    Merchant store = merchants.create("Loja");
    User ana =
        User.create(
            store.id(),
            "Ana",
            new EmailAddress("Ana@Loja.com"),
            Role.OWNER,
            "h",
            Clock.systemUTC());

    users.insert(ana);

    assertThat(users.findActiveByEmail("ana@loja.com")).map(User::id).contains(ana.id());
    assertThat(users.findActiveByMerchant(store.id()))
        .extracting(User::id)
        .containsExactly(ana.id());
    assertThat(users.countActiveByMerchantAndRole(store.id(), Role.OWNER)).isEqualTo(1);

    User again =
        User.create(
            store.id(),
            "Ana 2",
            new EmailAddress("ANA@loja.com"),
            Role.FINANCE,
            "h",
            Clock.systemUTC());
    assertThatThrownBy(() -> users.insert(again))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void aDeletedUserIsNotFoundAndFreesTheEmail() {
    Merchant store = merchants.create("Loja");
    User ana =
        User.create(
            store.id(),
            "Ana",
            new EmailAddress("bia@loja.com"),
            Role.OWNER,
            "h",
            Clock.systemUTC());
    users.insert(ana);

    users.save(ana.deleted(Clock.systemUTC().instant()));

    assertThat(users.findActiveByEmail("bia@loja.com")).isEmpty();
    assertThat(users.findById(ana.id())).map(User::isActive).contains(false);
    users.insert(
        User.create(
            store.id(),
            "Ana",
            new EmailAddress("bia@loja.com"),
            Role.OWNER,
            "h",
            Clock.systemUTC()));
  }
}
