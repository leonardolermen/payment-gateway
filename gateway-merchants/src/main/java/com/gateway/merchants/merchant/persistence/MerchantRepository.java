package com.gateway.merchants.merchant.persistence;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.merchant.Merchant;
import java.util.List;
import java.util.Optional;

public interface MerchantRepository {
  Merchant save(Merchant merchant);
  Optional<Merchant> findById(MerchantId id);
  List<Merchant> findAll();
  Optional<Merchant> findByInboundWebhookToken(String token);
}
