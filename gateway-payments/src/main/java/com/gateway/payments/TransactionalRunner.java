package com.gateway.payments;

import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one adapter from Spring's {@link TransactionTemplate} to the {@link UnitOfWork} port, so the
 * classes that write a row during a create never import Spring.
 *
 * <p>Named {@code Runner} because that is what it is and because it is how this repository spells
 * "this class is wiring, not model" — the distinction {@code ArchitectureTest}'s {@code
 * modelsHaveNoSpring} enforces.
 */
public class TransactionalRunner implements UnitOfWork {
  private final TransactionTemplate template;

  public TransactionalRunner(TransactionTemplate template) {
    this.template = template;
  }

  @Override
  public <T> T inTransaction(Supplier<T> work) {
    return template.execute(status -> work.get());
  }
}
