package com.gateway.merchants.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.merchants.TestApp;
import com.gateway.merchants.TestClock;
import com.gateway.merchants.merchant.Merchant;
import com.gateway.merchants.merchant.MerchantService;
import com.gateway.merchants.user.EmailAddress;
import com.gateway.merchants.user.Role;
import com.gateway.merchants.user.User;
import com.gateway.merchants.user.UserService;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
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
  @Autowired TestClock clock;

  @AfterEach
  void resetClock() {
    clock.reset();
  }

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

  @Test
  void refreshOfARevokedSessionIsEmpty() {
    SessionService.Issued issued = sessions.open(ana("ana@revokedrefresh.com").id(), null, null);
    sessions.revoke(issued.session().id());

    assertThat(sessions.refresh(issued.refreshToken().reveal())).isEmpty();
    assertThat(sessions.authenticate(issued.accessToken().reveal())).isEmpty();
  }

  @Test
  void refreshOfAnExpiredSessionIsEmpty() {
    SessionService.Issued issued = sessions.open(ana("ana@expiredrefresh.com").id(), null, null);
    clock.advance(Duration.ofDays(31));

    assertThat(sessions.refresh(issued.refreshToken().reveal())).isEmpty();
  }

  @Test
  void anExpiredAccessTokenDoesNotAuthenticateButRefreshStillWorks() {
    SessionService.Issued issued = sessions.open(ana("ana@expiredaccess.com").id(), null, null);
    clock.advance(Duration.ofMinutes(16));

    assertThat(sessions.authenticate(issued.accessToken().reveal())).isEmpty();
    assertThat(sessions.refresh(issued.refreshToken().reveal())).isPresent();
  }

  @Test
  void anExpiredRefreshKillsAnAccessTokenThatHasNotExpired() {
    SessionService.Issued issued = sessions.open(ana("ana@expiredboth.com").id(), null, null);
    jdbc.update(
        "UPDATE merchants.sessions SET access_expires_at = now() + interval '60 days',"
            + " refresh_expires_at = now() + interval '1 hour' WHERE id = ?",
        issued.session().id());
    clock.advance(Duration.ofHours(2));

    assertThat(sessions.authenticate(issued.accessToken().reveal())).isEmpty();
  }

  @Test
  void anUnknownRefreshTokenIsEmptyAndRevokesNothing() {
    SessionService.Issued issued = sessions.open(ana("ana@unknownrefresh.com").id(), null, null);

    assertThat(sessions.refresh("gr_unknown")).isEmpty();
    assertThat(sessions.refresh(null)).isEmpty();
    assertThat(sessions.authenticate(null)).isEmpty();
    assertThat(sessions.authenticate(issued.accessToken().reveal())).isPresent();
  }

  @Test
  void revokeEndsOneSessionAndRevokeAllEndsEverySession() {
    User ana = ana("ana@revokeall.com");
    SessionService.Issued laptop = sessions.open(ana.id(), null, null);
    SessionService.Issued phone = sessions.open(ana.id(), null, null);

    sessions.revoke(laptop.session().id());
    assertThat(sessions.authenticate(laptop.accessToken().reveal())).isEmpty();
    assertThat(sessions.authenticate(phone.accessToken().reveal())).isPresent();

    sessions.revokeAll(ana.id());
    assertThat(sessions.authenticate(phone.accessToken().reveal())).isEmpty();
    assertThat(sessions.listLive(ana.id())).isEmpty();
  }

  @Test
  void revokedAtIsFinalAndATouchNeverResurrectsASession() {
    SessionService.Issued issued = sessions.open(ana("ana@final.com").id(), null, null);
    String id = issued.session().id();

    sessions.revoke(id);
    Object first =
        jdbc.queryForObject(
            "SELECT revoked_at FROM merchants.sessions WHERE id = ?", Object.class, id);
    clock.advance(Duration.ofMinutes(5));
    sessions.revoke(id);

    Object second =
        jdbc.queryForObject(
            "SELECT revoked_at FROM merchants.sessions WHERE id = ?", Object.class, id);
    assertThat(second).isEqualTo(first);
  }

  @Test
  void lastUsedAtMovesAtMostOncePerMinute() {
    SessionService.Issued issued = sessions.open(ana("ana@touch.com").id(), null, null);
    String id = issued.session().id();
    String sql = "SELECT last_used_at FROM merchants.sessions WHERE id = ?";

    clock.advance(Duration.ofMinutes(2));
    sessions.authenticate(issued.accessToken().reveal());
    Object touched = jdbc.queryForObject(sql, Object.class, id);
    sessions.authenticate(issued.accessToken().reveal());

    assertThat(jdbc.queryForObject(sql, Object.class, id)).isEqualTo(touched);
  }
}
