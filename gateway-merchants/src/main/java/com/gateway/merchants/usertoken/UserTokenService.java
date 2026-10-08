package com.gateway.merchants.usertoken;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.security.Secret;
import com.gateway.merchants.MerchantsProperties;
import com.gateway.merchants.apikey.ApiKey;
import com.gateway.merchants.usertoken.persistence.UserTokenRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** Single-use links (verify e-mail, reset password, invite), stored only as a peppered hash. */
public class UserTokenService {
  public record Issued(UserToken token, Secret plain) {}

  private static final SecureRandom RANDOM = new SecureRandom();

  private final UserTokenRepository tokens;
  private final MerchantsProperties properties;
  private final Clock clock;

  public UserTokenService(UserTokenRepository tokens, MerchantsProperties properties, Clock clock) {
    this.tokens = tokens;
    this.properties = properties;
    this.clock = clock;
  }

  @Transactional
  public Issued issue(
      UserToken.Kind kind,
      String userId,
      MerchantId merchantId,
      Map<String, String> payload,
      Duration ttl) {
    Instant now = clock.instant();

    String plain = token("gt_");
    UserToken token =
        new UserToken(
            Ulid.next(),
            userId,
            merchantId,
            kind,
            ApiKey.hashOf(plain, properties.apiKeyPepper()),
            Map.copyOf(payload),
            now.plus(ttl),
            null,
            now);
    tokens.insert(token);

    return new Issued(token, Secret.of(plain));
  }

  /** One UPDATE decides who gets the token: two concurrent consumers cannot both win. */
  @Transactional
  public Optional<UserToken> consume(UserToken.Kind kind, String plain) {
    return tokens.consume(ApiKey.hashOf(plain, properties.apiKeyPepper()), kind, clock.instant());
  }

  /** A resend replaces the previous link, so the old one must stop working. */
  @Transactional
  public void invalidateOpen(UserToken.Kind kind, String userId) {
    tokens.markUsedOpenOf(kind, userId, clock.instant());
  }

  private static String token(String prefix) {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
