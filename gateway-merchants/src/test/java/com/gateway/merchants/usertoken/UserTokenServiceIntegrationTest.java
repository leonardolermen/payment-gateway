package com.gateway.merchants.usertoken;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.merchants.TestApp;
import com.gateway.merchants.merchant.Merchant;
import com.gateway.merchants.merchant.MerchantService;
import com.gateway.merchants.user.EmailAddress;
import com.gateway.merchants.user.Role;
import com.gateway.merchants.user.User;
import com.gateway.merchants.user.UserService;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = TestApp.class)
@Testcontainers
class UserTokenServiceIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired MerchantService merchants;
  @Autowired UserService users;
  @Autowired UserTokenService tokens;

  private User ana(Merchant store, String email) {
    return users.register(store.id(), "Ana", new EmailAddress(email), Role.OWNER, "senha-forte-1");
  }

  @Test
  void aTokenIsConsumedOnceAndNeverAfterExpiry() {
    Merchant store = merchants.create("Loja");
    User ana = ana(store, "ana@consumed.com");
    UserTokenService.Issued issued =
        tokens.issue(
            UserToken.Kind.VERIFY_EMAIL, ana.id(), store.id(), Map.of(), Duration.ofHours(24));

    assertThat(tokens.consume(UserToken.Kind.RESET_PASSWORD, issued.plain().reveal())).isEmpty();
    assertThat(tokens.consume(UserToken.Kind.VERIFY_EMAIL, issued.plain().reveal()))
        .map(UserToken::userId)
        .contains(ana.id());
    assertThat(tokens.consume(UserToken.Kind.VERIFY_EMAIL, issued.plain().reveal())).isEmpty();

    UserTokenService.Issued expired =
        tokens.issue(
            UserToken.Kind.VERIFY_EMAIL, ana.id(), store.id(), Map.of(), Duration.ofSeconds(-1));
    assertThat(tokens.consume(UserToken.Kind.VERIFY_EMAIL, expired.plain().reveal())).isEmpty();
  }

  @Test
  void aResendInvalidatesTheOpenOnes() {
    Merchant store = merchants.create("Loja");
    User ana = ana(store, "ana@resend.com");
    UserTokenService.Issued first =
        tokens.issue(
            UserToken.Kind.VERIFY_EMAIL, ana.id(), store.id(), Map.of(), Duration.ofHours(24));

    tokens.invalidateOpen(UserToken.Kind.VERIFY_EMAIL, ana.id());
    UserTokenService.Issued second =
        tokens.issue(
            UserToken.Kind.VERIFY_EMAIL, ana.id(), store.id(), Map.of(), Duration.ofHours(24));

    assertThat(tokens.consume(UserToken.Kind.VERIFY_EMAIL, first.plain().reveal())).isEmpty();
    assertThat(tokens.consume(UserToken.Kind.VERIFY_EMAIL, second.plain().reveal())).isPresent();
  }

  @Test
  void anInviteCarriesItsRoleAndEmailAndNoUser() {
    Merchant store = merchants.create("Loja");
    UserTokenService.Issued invite =
        tokens.issue(
            UserToken.Kind.INVITE,
            null,
            store.id(),
            Map.of("email", "bia@loja.com", "role", "FINANCE"),
            Duration.ofDays(7));

    UserToken consumed =
        tokens.consume(UserToken.Kind.INVITE, invite.plain().reveal()).orElseThrow();

    assertThat(consumed.userId()).isNull();
    assertThat(consumed.payload())
        .containsEntry("email", "bia@loja.com")
        .containsEntry("role", "FINANCE");
  }
}
