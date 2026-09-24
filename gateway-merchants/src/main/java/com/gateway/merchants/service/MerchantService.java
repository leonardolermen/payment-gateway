package com.gateway.merchants.service;

import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.domain.Merchant;
import com.gateway.merchants.repository.MerchantRepository;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

public class MerchantService {
  private final MerchantRepository repo;
  public MerchantService(MerchantRepository repo) { this.repo = repo; }

  @Transactional public Merchant create(String name) { return repo.save(Merchant.create(name)); }
  @Transactional(readOnly = true) public Merchant get(MerchantId id) { return repo.findById(id).orElseThrow(() -> new NotFoundException("merchant", id.value())); }
  @Transactional public Merchant suspend(MerchantId id) { return repo.save(get(id).suspend()); }
  @Transactional public Merchant activate(MerchantId id) { return repo.save(get(id).activate()); }
  @Transactional(readOnly = true) public List<Merchant> list() { return repo.findAll(); }
}
