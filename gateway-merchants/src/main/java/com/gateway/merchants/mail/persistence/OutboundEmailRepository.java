package com.gateway.merchants.mail.persistence;

import com.gateway.merchants.mail.OutboundEmail;
import java.util.Optional;

public interface OutboundEmailRepository {
  void insert(OutboundEmail email);

  Optional<OutboundEmail> findById(String id);

  void deleteById(String id);
}
