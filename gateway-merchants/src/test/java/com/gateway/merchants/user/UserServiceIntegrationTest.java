package com.gateway.merchants.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import com.gateway.merchants.TestApp;
import com.gateway.merchants.merchant.Merchant;
import com.gateway.merchants.merchant.MerchantService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
  void twoOwnersRemovingEachOtherAtOnceLeaveOneOwner() throws Exception {
    // Several rounds: one round can serialise by luck, and the bug only shows when both count
    // first.
    for (int round = 0; round < 5; round++) {
      Merchant store = merchants.create("Loja");
      User ana =
          users.register(
              store.id(),
              "Ana",
              new EmailAddress("ana" + round + "@race.com"),
              Role.OWNER,
              "senha-forte-1");
      User bia =
          users.register(
              store.id(),
              "Bia",
              new EmailAddress("bia" + round + "@race.com"),
              Role.OWNER,
              "senha-forte-2");

      CountDownLatch start = new CountDownLatch(1);
      List<Future<String>> outcomes = new ArrayList<>();
      try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
        for (User target : List.of(ana, bia)) {
          outcomes.add(
              executor.submit(
                  () -> {
                    start.await();
                    try {
                      users.remove(store.id(), target.id());
                      return "removed";
                    } catch (DomainException e) {
                      return e.code();
                    }
                  }));
        }
        start.countDown();
      }

      List<String> results = new ArrayList<>();
      for (Future<String> outcome : outcomes) {
        results.add(outcome.get());
      }

      assertThat(results).containsExactlyInAnyOrder("removed", "LAST_OWNER");
      assertThat(users.listByMerchant(store.id()))
          .filteredOn(user -> user.role() == Role.OWNER)
          .hasSize(1);
    }
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
