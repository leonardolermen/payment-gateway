package com.gateway.payments.payment.boleto.persistence;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class BoletoNumberRepositoryIntegrationTest extends ServiceIntegrationTestBase {
  @Autowired BoletoNumberRepository numbers;
  @Autowired PlatformTransactionManager txManager;

  @Test
  void startsAtOnePerMerchantWithEightDigits() {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    MerchantId other = MerchantId.next();
    // Extracted to typed locals: javac's inference can't settle the assertThat overload (String vs.
    // IntPredicate/Predicate<T>) while T is still open on the nested tx.execute(...) call.
    String first = tx.execute(s -> numbers.next(merchant));
    String second = tx.execute(s -> numbers.next(merchant));
    String otherFirst = tx.execute(s -> numbers.next(other));
    assertThat(first).isEqualTo("00000001");
    assertThat(second).isEqualTo("00000002");
    assertThat(otherFirst).isEqualTo("00000001");
  }

  @Test
  void refusesToRunOutsideATransaction() {
    assertThatThrownBy(() -> numbers.next(merchant)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
  }

  /** Two threads, one row: the UPDATE ... RETURNING serializes on the row lock, so no number repeats and none is skipped. */
  @Test
  void concurrentCallersNeverShareANumber() throws Exception {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    CountDownLatch go = new CountDownLatch(1);
    Callable<String> take = () -> { go.await(); return tx.execute(s -> numbers.next(merchant)); };
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Future<String>> futures = IntStream.range(0, 20).mapToObj(i -> pool.submit(take)).toList();
      go.countDown();
      Set<String> got = new java.util.HashSet<>();
      for (Future<String> f : futures) got.add(f.get());
      assertThat(got).hasSize(20).contains("00000001", "00000020");
    }
  }

  @Test
  void theCounterIsCappedAtEightDigits() {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    jdbc.update("INSERT INTO payments.boleto_numbers (merchant_id, next_value) VALUES (?, 99999999)", merchant.value());
    // @Repository translates data-access exceptions, so the raw IllegalStateException surfaces as
    // its root cause, not directly — same pattern as JobsAndOutboxClaimIntegrationTest.
    assertThatThrownBy(() -> tx.execute(s -> numbers.next(merchant)))
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("nosso numero exhausted for merchant " + merchant.value());
  }
}
