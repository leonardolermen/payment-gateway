package com.gateway.merchants.session;

import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.security.Secret;
import com.gateway.merchants.MerchantsProperties;
import com.gateway.merchants.apikey.ApiKey;
import com.gateway.merchants.session.persistence.SessionRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opaque tokens, hashed with the API-key pepper. Access lives 15 minutes in the browser memory;
 * refresh lives 30 days in an HttpOnly cookie and is rotated on every use. A refresh that comes
 * back after it was rotated is the one sign of a stolen cookie we can see, and it ends the session.
 */
public class SessionService {
  public record Issued(
      Session session, Secret accessToken, Secret refreshToken, Duration accessTtl) {}

  private static final Duration TOUCH_EVERY = Duration.ofMinutes(1);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SessionRepository sessions;
  private final MerchantsProperties properties;
  private final Clock clock;

  public SessionService(SessionRepository sessions, MerchantsProperties properties, Clock clock) {
    this.sessions = sessions;
    this.properties = properties;
    this.clock = clock;
  }

  @Transactional
  public Issued open(String userId, String ip, String userAgent) {
    Instant now = clock.instant();
    Duration accessTtl = properties.auth().accessTtl();

    String access = token("gs_");
    String refresh = token("gr_");
    Session session =
        new Session(
            Ulid.next(),
            userId,
            hash(access),
            hash(refresh),
            null,
            now.plus(accessTtl),
            now.plus(properties.auth().refreshTtl()),
            ip == null ? null : ip.substring(0, Math.min(45, ip.length())),
            userAgent == null ? null : userAgent.substring(0, Math.min(200, userAgent.length())),
            now,
            now,
            null);
    sessions.insert(session);

    return new Issued(session, Secret.of(access), Secret.of(refresh), accessTtl);
  }

  @Transactional
  public Optional<Session> authenticate(String accessToken) {
    if (accessToken == null) {
      return Optional.empty();
    }

    Instant now = clock.instant();

    return sessions
        .findByAccessHash(hash(accessToken))
        .filter(session -> session.accessValid(now))
        .flatMap(session -> touchIfStale(session, now));
  }

  /** Targeted update: a concurrent revoke must not be overwritten by this touch. */
  private Optional<Session> touchIfStale(Session session, Instant now) {
    if (!session.lastUsedAt().plus(TOUCH_EVERY).isBefore(now)) {
      return Optional.of(session);
    }

    sessions.touch(session.id(), now);

    return sessions.findById(session.id()).filter(fresh -> fresh.accessValid(now));
  }

  @Transactional
  public Optional<Issued> refresh(String refreshToken) {
    if (refreshToken == null) {
      return Optional.empty();
    }

    Instant now = clock.instant();
    String presented = hash(refreshToken);

    Optional<Session> replayed = sessions.findByPreviousRefreshHash(presented);
    if (replayed.isPresent()) {
      sessions.revoke(replayed.get().id(), now);
      return Optional.empty();
    }

    return sessions
        .findByRefreshHash(presented)
        .filter(session -> session.isLive(now))
        .flatMap(session -> rotate(session, presented, now));
  }

  /** Empty when another caller rotated first: it won, and this one is not a theft signal. */
  private Optional<Issued> rotate(Session session, String presentedHash, Instant now) {
    Duration accessTtl = properties.auth().accessTtl();
    String access = token("gs_");
    String refresh = token("gr_");

    boolean rotated =
        sessions.rotate(
            session.id(),
            presentedHash,
            hash(access),
            hash(refresh),
            now.plus(accessTtl),
            now.plus(properties.auth().refreshTtl()),
            now);
    if (!rotated) {
      return Optional.empty();
    }

    return sessions
        .findById(session.id())
        .map(fresh -> new Issued(fresh, Secret.of(access), Secret.of(refresh), accessTtl));
  }

  @Transactional
  public void revoke(String sessionId) {
    sessions.revoke(sessionId, clock.instant());
  }

  @Transactional
  public void revokeOthers(String userId, String keepSessionId) {
    Instant now = clock.instant();

    sessions.findLiveByUser(userId, now).stream()
        .filter(session -> !session.id().equals(keepSessionId))
        .forEach(session -> sessions.revoke(session.id(), now));
  }

  @Transactional
  public void revokeAll(String userId) {
    revokeOthers(userId, null);
  }

  @Transactional(readOnly = true)
  public List<Session> listLive(String userId) {
    return sessions.findLiveByUser(userId, clock.instant());
  }

  private String hash(String plain) {
    return ApiKey.hashOf(plain, properties.apiKeyPepper());
  }

  private static String token(String prefix) {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
