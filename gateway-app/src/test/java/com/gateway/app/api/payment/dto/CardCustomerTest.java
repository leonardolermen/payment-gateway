package com.gateway.app.api.payment.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CardCustomerTest {

  /** A log line or exception that prints the request must not carry CPF or e-mail. */
  @Test
  void toStringMasksTheDocumentAndTheEmail() {
    CardCustomer customer = new CardCustomer("Maria Silva", "12345678909", "maria@example.com");

    assertThat(customer.toString())
        .contains("Maria Silva")
        .doesNotContain("12345678909")
        .doesNotContain("maria@example.com");
  }
}
