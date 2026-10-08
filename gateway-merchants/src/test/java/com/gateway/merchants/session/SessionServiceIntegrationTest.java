package com.gateway.merchants.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.merchants.TestApp;
import com.gateway.merchants.merchant.Merchant;
import com.gateway.merchants.merchant.MerchantService;
import com.gateway.merchants.user.EmailAddress;
import com.gateway.merchants.user.Role;
import com.gateway.merchants.user.User;
import com.gateway.merchants.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = TestApp.class)
@Testcontainers
class SessionServiceIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired MerchantService merchants;
  @Autowired UserService users;
  @Autowired SessionService sessions;
  @Autowired JdbcTemplate jdbc;

  private User ana(String email) {
    Merchant store = merchants.create("Loja");
    return users.register(store.id(), "Ana", new EmailAddress(email), Role.OWNER, "senha-forte-1");
  }

  @Test
  void opensAuthenticatesAndRotates() {
    User ana = ana("ana@rotates.com");
    SessionService.Issued first = sessions.open(ana.id(), "203.0.113.9", "Mozilla/5.0");

    assertThat(first.accessToken().reveal()).startsWith("gs_");
    assertThat(first.refreshToken().reveal()).startsWith("gr_");
    assertThat(sessions.authenticate(first.accessToken().reveal()))
        .map(Session::userId)
        .contains(ana.id());
    assertThat(sessions.authenticate("gs_nope")).isEmpty();

    SessionService.Issued second = sessions.refresh(first.refreshToken().reveal()).orElseThrow();
    assertThat(second.session().id()).isEqualTo(first.session().id());
    assertThat(sessions.authenticate(first.accessToken().reveal())).isEmpty();
    assertThat(sessions.authenticate(second.accessToken().reveal())).isPresent();
  }

  @Test
  void aReusedRefreshRevokesTheSession() {
    User ana = ana("ana@reuse.com");
    SessionService.Issued first = sessions.open(ana.id(), null, null);
    SessionService.Issued second = sessions.refresh(first.refreshToken().reveal()).orElseThrow();

    // The old refresh comes back: someone else holds the cookie. Everything on that session dies.
    assertThat(sessions.refresh(first.refreshToken().reveal())).isEmpty();
    assertThat(sessions.refresh(second.refreshToken().reveal())).isEmpty();
    assertThat(sessions.authenticate(second.accessToken().reveal())).isEmpty();
  }

  @Test
  void revokeOthersKeepsOnlyTheCurrentOne() {
    User ana = ana("ana@others.com");
    SessionService.Issued laptop = sessions.open(ana.id(), null, null);
    SessionService.Issued phone = sessions.open(ana.id(), null, null);

    sessions.revokeOthers(ana.id(), laptop.session().id());

    assertThat(sessions.authenticate(laptop.accessToken().reveal())).isPresent();
    assertThat(sessions.authenticate(phone.accessToken().reveal())).isEmpty();
    assertThat(sessions.listLive(ana.id()))
        .extracting(Session::id)
        .containsExactly(laptop.session().id());
  }

  @Test
  void nothingPlainIsStored() {
    User ana = ana("ana@plain.com");
    SessionService.Issued issued = sessions.open(ana.id(), null, null);

    String row =
        jdbc.queryForObject(
            "SELECT access_hash || refresh_hash FROM merchants.sessions WHERE id = ?",
            String.class,
            issued.session().id());
    assertThat(row)
        .doesNotContain(issued.accessToken().reveal())
        .doesNotContain(issued.refreshToken().reveal());
  }
}
