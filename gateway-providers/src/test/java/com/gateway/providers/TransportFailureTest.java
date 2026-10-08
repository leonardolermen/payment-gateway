package com.gateway.providers;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import org.junit.jupiter.api.Test;

class TransportFailureTest {

  @Test
  void namesTheExceptionClassWhenItHasNoMessage() {
    String description =
        TransportFailure.describe(
            "token request",
            URI.create("http://localhost:8099/itau/oauth"),
            new ConnectException());

    assertThat(description).isEqualTo("token request to localhost:8099 failed: ConnectException");
  }

  @Test
  void keepsTheMessageWhenThereIsOne() {
    String description =
        TransportFailure.describe(
            "Itaú GET",
            URI.create("https://secure.api.itau/pix/cob"),
            new IOException("Connection reset"));

    assertThat(description)
        .isEqualTo("Itaú GET to secure.api.itau failed: IOException: Connection reset");
  }

  @Test
  void neverIncludesPathOrQuery() {
    String description =
        TransportFailure.describe(
            "Itaú GET",
            URI.create("https://secure.api.itau/pix/cob?inicio=2026-10-06T00:00:00Z&token=abc"),
            new ConnectException());

    assertThat(description)
        .doesNotContain("/pix/cob")
        .doesNotContain("inicio")
        .doesNotContain("abc");
  }
}
