package com.gateway.billing.customer;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.party.Document;
import org.junit.jupiter.api.Test;

class DocumentMaskTest {
  @Test
  void aCpfShowsOnlyItsMiddleBlockAndCheckDigits() {
    assertThat(DocumentMask.mask(Document.of("529.982.247-25"))).isEqualTo("***.982.***-25");
  }

  @Test
  void aCnpjShowsOnlyItsBranchAndCheckDigits() {
    assertThat(DocumentMask.mask(Document.of("08.867.659/0001-51")))
        .isEqualTo("**.***.***/0001-51");
  }
}
