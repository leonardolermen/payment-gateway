package com.gateway.merchants.usertoken.persistence;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.usertoken.UserToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserTokenRepository {
  void insert(UserToken token);

  Optional<UserToken> findById(String id);

  Optional<UserToken> findByHash(String tokenHash);

  /** Empty unless this call is the one that marked the token used. */
  Optional<UserToken> consume(String tokenHash, UserToken.Kind kind, Instant now);

  void markUsedOpenOf(UserToken.Kind kind, String userId, Instant now);

  Optional<UserToken> findNewestOpen(UserToken.Kind kind, String userId);

  /** Unused and unexpired INVITE tokens of the merchant, oldest first. */
  List<UserToken> findOpenInvites(MerchantId merchantId, Instant now);

  void markUsed(String id, Instant now);
}
