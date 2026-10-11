package com.gateway.merchants.mail.persistence;

import com.gateway.merchants.mail.OutboundEmail;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OutboundEmailRepositoryImpl implements OutboundEmailRepository {
  private final OutboundEmailJpaRepository jpa;

  @PersistenceContext private EntityManager entityManager;

  public OutboundEmailRepositoryImpl(OutboundEmailJpaRepository jpa) {
    this.jpa = jpa;
  }

  /** persist + flush: a constraint violation must surface inside the caller's transaction. */
  @Override
  @Transactional
  public void insert(OutboundEmail email) {
    entityManager.persist(toEntity(email));
    entityManager.flush();
  }

  @Override
  public Optional<OutboundEmail> findById(String id) {
    return jpa.findById(id).map(OutboundEmailRepositoryImpl::toDomain);
  }

  @Override
  @Transactional
  public void deleteById(String id) {
    jpa.deleteById(id);
  }

  private static OutboundEmailEntity toEntity(OutboundEmail email) {
    OutboundEmailEntity entity = new OutboundEmailEntity();
    entity.id = email.id();
    entity.recipient = email.recipient();
    entity.subject = email.subject();
    entity.textBody = email.textBody();
    entity.htmlBody = email.htmlBody();
    entity.createdAt = email.createdAt();
    return entity;
  }

  private static OutboundEmail toDomain(OutboundEmailEntity entity) {
    return new OutboundEmail(
        entity.id,
        entity.recipient,
        entity.subject,
        entity.textBody,
        entity.htmlBody,
        entity.createdAt);
  }
}
