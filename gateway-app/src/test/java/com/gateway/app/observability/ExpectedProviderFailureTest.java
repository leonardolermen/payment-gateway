package com.gateway.app.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ThrowableProxy;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.ProviderException.Code;
import java.net.ConnectException;
import org.junit.jupiter.api.Test;

class ExpectedProviderFailureTest {

  private static ProviderException unavailable() {
    return new ProviderException(
        Code.UNAVAILABLE,
        "token request to localhost:8099 failed: ConnectException",
        new ConnectException());
  }

  @Test
  void collapsesUnavailableToOneLineWithTheCauseChain() {
    String line = ExpectedProviderFailure.oneLine(new ThrowableProxy(unavailable())).orElseThrow();

    assertThat(line)
        .isEqualTo(
            "ProviderException UNAVAILABLE: token request to localhost:8099 failed:"
                + " ConnectException ← ConnectException");
  }

  @Test
  void collapsesTimeout() {
    ProviderException timeout = new ProviderException(Code.TIMEOUT, "Itaú GET timed out", null);

    assertThat(ExpectedProviderFailure.oneLine(new ThrowableProxy(timeout)))
        .hasValueSatisfying(
            line -> assertThat(line).contains("ProviderException TIMEOUT: Itaú GET timed out"));
  }

  @Test
  void flattensLineBreaksInTheMessageSoTheLineStaysOneLine() {
    ProviderException unavailable =
        new ProviderException(
            Code.UNAVAILABLE, "Itaú GET failed: 503\n<html>\r\n<body>down</body>", null);

    String line = ExpectedProviderFailure.oneLine(new ThrowableProxy(unavailable)).orElseThrow();

    assertThat(line).doesNotContain("\n").doesNotContain("\r");
    assertThat(line).contains("503 <html> <body>down</body>");
  }

  @Test
  void collapsesWhenTheProviderFailureIsACause() {
    DomainException wrapper = new DomainException("PROVIDER_UNAVAILABLE", "The bank could not ...");
    wrapper.initCause(unavailable());

    assertThat(ExpectedProviderFailure.oneLine(new ThrowableProxy(wrapper)))
        .hasValueSatisfying(line -> assertThat(line).startsWith("DomainException: The bank"))
        .hasValueSatisfying(line -> assertThat(line).contains("ProviderException UNAVAILABLE"));
  }

  @Test
  void keepsTheStackForEveryOtherCode() {
    ProviderException rejected =
        new ProviderException(Code.UNAUTHENTICATED, 401, null, "STS rejected the credentials");

    assertThat(ExpectedProviderFailure.oneLine(new ThrowableProxy(rejected))).isEmpty();
  }

  @Test
  void keepsTheStackForABug() {
    assertThat(ExpectedProviderFailure.oneLine(new ThrowableProxy(new IllegalStateException("x"))))
        .isEmpty();
  }

  @Test
  void survivesACauseCycle() {
    RuntimeException first = new RuntimeException("first");
    RuntimeException second = new RuntimeException("second", first);
    first.initCause(second);

    assertThat(ExpectedProviderFailure.oneLine(new ThrowableProxy(first))).isEmpty();
  }
}
