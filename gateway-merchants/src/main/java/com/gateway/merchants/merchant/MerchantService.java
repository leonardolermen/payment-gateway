package com.gateway.merchants.merchant;

import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.merchant.Merchant;
import com.gateway.merchants.merchant.persistence.MerchantRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

public class MerchantService {
  private final MerchantRepository repo;
  public MerchantService(MerchantRepository repo) {
    this.repo = repo;
  }

  @Transactional public Merchant create(String name) { return repo.save(Merchant.create(name)); }
  @Transactional(readOnly = true) public Merchant get(MerchantId id) { return repo.findById(id).orElseThrow(() -> new NotFoundException("merchant", id.value())); }
  @Transactional public Merchant suspend(MerchantId id) { return repo.save(get(id).suspend()); }
  @Transactional public Merchant activate(MerchantId id) { return repo.save(get(id).activate()); }
  /** Empty for an unknown token: the webhook answers 404 either way, so existence does not leak. */
  @Transactional(readOnly = true) public Optional<Merchant> findByInboundWebhookToken(String token) {
    if (token == null || token.length() != 26) {
      return Optional.empty();
    }
    return repo.findByInboundWebhookToken(token);
  }
  @Transactional(readOnly = true) public List<Merchant> list() { return repo.findAll(); }
}
