package com.gateway.merchants.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import com.gateway.merchants.TestApp;
import com.gateway.merchants.merchant.Merchant;
import com.gateway.merchants.merchant.MerchantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = TestApp.class)
@Testcontainers
class UserServiceIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired MerchantService merchants;
  @Autowired UserService users;

  @Test
  void registersAndAuthenticates() {
    Merchant store = merchants.create("Loja");
    User ana =
        users.register(
            store.id(), "Ana", new EmailAddress("ana@registers.com"), Role.OWNER, "senha-forte-1");

    assertThat(ana.passwordHash()).doesNotContain("senha-forte-1");
    assertThat(users.authenticate(new EmailAddress("ANA@registers.com"), "senha-forte-1"))
        .map(User::id)
        .contains(ana.id());
    assertThat(users.get(ana.id()).lastLoginAt()).isNotNull();
    assertThatThrownBy(
            () ->
                users.register(
                    store.id(),
                    "Ana",
                    new EmailAddress("ana@registers.com"),
                    Role.FINANCE,
                    "outra-senha-1"))
        .isInstanceOf(DomainException.class)
        .hasFieldOrPropertyWithValue("code", "EMAIL_TAKEN");
  }

  @Test
  void unknownEmailAndWrongPasswordAreTheSameEmptyAnswer() {
    Merchant store = merchants.create("Loja");
    users.register(
        store.id(), "Ana", new EmailAddress("ana@empty.com"), Role.OWNER, "senha-forte-1");

    assertThat(users.authenticate(new EmailAddress("ana@empty.com"), "errada-errada")).isEmpty();
    assertThat(users.authenticate(new EmailAddress("ninguem@empty.com"), "senha-forte-1"))
        .isEmpty();
  }

  @Test
  void theLastOwnerCannotBeDemotedOrRemoved() {
    Merchant store = merchants.create("Loja");
    User ana =
        users.register(
            store.id(), "Ana", new EmailAddress("ana@owner.com"), Role.OWNER, "senha-forte-1");
    User bia =
        users.register(
            store.id(), "Bia", new EmailAddress("bia@owner.com"), Role.FINANCE, "senha-forte-2");

    assertThatThrownBy(() -> users.changeRole(store.id(), ana.id(), Role.FINANCE))
        .hasFieldOrPropertyWithValue("code", "LAST_OWNER");
    assertThatThrownBy(() -> users.remove(store.id(), ana.id()))
        .hasFieldOrPropertyWithValue("code", "LAST_OWNER");

    users.changeRole(store.id(), bia.id(), Role.OWNER);
    users.remove(store.id(), ana.id());
    assertThat(users.listByMerchant(store.id())).extracting(User::id).containsExactly(bia.id());
  }

  @Test
  void changePasswordNeedsTheCurrentOne() {
    Merchant store = merchants.create("Loja");
    User ana =
        users.register(
            store.id(), "Ana", new EmailAddress("ana@change.com"), Role.OWNER, "senha-forte-1");

    assertThatThrownBy(() -> users.changePassword(ana.id(), "errada", "nova-senha-11"))
        .hasFieldOrPropertyWithValue("code", "INVALID_CREDENTIALS");

    users.changePassword(ana.id(), "senha-forte-1", "nova-senha-11");
    assertThat(users.authenticate(new EmailAddress("ana@change.com"), "nova-senha-11")).isPresent();
  }
}
