package com.gateway.payments;

import java.util.function.Supplier;

/**
 * One transaction around one piece of work.
 *
 * <p>A port rather than Spring's {@code TransactionTemplate} used directly: the classes that write a row
 * during a create are orchestration, but they are not wiring, and {@code ArchitectureTest}'s
 * {@code modelsHaveNoSpring} is the boundary that says so. The adapter is one anonymous class in
 * {@code PaymentsConfiguration}, which is wiring by its nature and may see Spring.
 *
 * <p>The services that predate this port still take the template directly; their names end in
 * {@code Service}, which is how this repository has always spelled "this class is wiring".
 */
public interface UnitOfWork {

  <T> T inTransaction(Supplier<T> work);

  default void run(Runnable work) {
    inTransaction(() -> {
      work.run();
      return null;
    });
  }
}
