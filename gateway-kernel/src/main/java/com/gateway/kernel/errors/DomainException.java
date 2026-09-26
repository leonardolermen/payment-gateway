package com.gateway.kernel.errors;

/**
 * A business rule violation: becomes a 4xx at the edge, with a stable {@code code} merchants can
 * handle.
 */
public class DomainException extends RuntimeException {
  private final String code;

  public DomainException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
