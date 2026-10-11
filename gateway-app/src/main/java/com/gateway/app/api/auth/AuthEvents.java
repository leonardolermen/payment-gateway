package com.gateway.app.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The account audit trail (spec 2026-10-08-usuarios-e-sessao, section 4): one INFO line per event,
 * key=value, under its own logger so it can be routed or kept longer than the rest. Ids and the
 * client IP only: never a token, a cookie, a password or an e-mail address. A failed login logs
 * user=unknown because the answer must not say whether the e-mail exists, and neither may the log a
 * support person reads.
 */
public final class AuthEvents {
  private static final Logger log = LoggerFactory.getLogger("gateway.audit.account");

  private AuthEvents() {}

  public static void loginSucceeded(String userId, String ip) {
    log.info("account event=login_succeeded user={} ip={}", userId, ip);
  }

  public static void loginFailed(String ip) {
    log.info("account event=login_failed user=unknown ip={}", ip);
  }

  public static void loggedOut(String userId, String ip) {
    log.info("account event=logout user={} ip={}", userId, ip);
  }

  public static void passwordChanged(String userId) {
    log.info("account event=password_changed user={}", userId);
  }

  public static void passwordReset(String userId) {
    log.info("account event=password_reset user={}", userId);
  }

  public static void emailVerified(String userId) {
    log.info("account event=email_verified user={}", userId);
  }

  public static void inviteSent(String merchantId, String inviterId, String role) {
    log.info("account event=invite_sent merchant={} by={} role={}", merchantId, inviterId, role);
  }

  public static void inviteAccepted(String merchantId, String userId) {
    log.info("account event=invite_accepted merchant={} user={}", merchantId, userId);
  }

  public static void roleChanged(String merchantId, String byUserId, String userId, String role) {
    log.info(
        "account event=role_changed merchant={} by={} user={} role={}",
        merchantId,
        byUserId,
        userId,
        role);
  }

  public static void userRemoved(String merchantId, String byUserId, String userId) {
    log.info("account event=user_removed merchant={} by={} user={}", merchantId, byUserId, userId);
  }

  /** {@code scope}: "others" (the caller keeps this session) or "all" (removal, reset). */
  public static void sessionsRevoked(String userId, String scope) {
    log.info("account event=sessions_revoked user={} scope={}", userId, scope);
  }
}
