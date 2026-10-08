package com.gateway.merchants.usertoken.persistence;

import com.gateway.merchants.usertoken.UserToken;
import java.time.Instant;
import java.util.Optional;

public interface UserTokenRepository {
  void insert(UserToken token);

  Optional<UserToken> findById(String id);

  Optional<UserToken> findByHash(String tokenHash);

  /** Empty unless this call is the one that marked the token used. */
  Optional<UserToken> consume(String tokenHash, UserToken.Kind kind, Instant now);

  void markUsedOpenOf(UserToken.Kind kind, String userId, Instant now);
}
