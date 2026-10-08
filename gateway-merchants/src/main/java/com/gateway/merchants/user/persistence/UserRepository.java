package com.gateway.merchants.user.persistence;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.user.Role;
import com.gateway.merchants.user.User;
import java.util.List;
import java.util.Optional;

public interface UserRepository {
  /** Throws DataIntegrityViolationException when the e-mail already belongs to an active user. */
  void insert(User user);

  User save(User user);

  Optional<User> findById(String id);

  Optional<User> findActiveByEmail(String normalized);

  List<User> findActiveByMerchant(MerchantId merchantId);

  long countActiveByMerchantAndRole(MerchantId merchantId, Role role);
}
