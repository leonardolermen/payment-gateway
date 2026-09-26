package com.gateway.kernel.errors;

/**
 * A value that does not have the shape its type requires. Carries only the value-level reason
 * ("must be 8 digits"): the type does not know it is called {@code customer.address.zip} in the
 * API, so whoever builds the aggregate composes the field path and the error code from it.
 *
 * <p>Not a {@link DomainException}: that one carries a merchant-facing code and reaches the edge as
 * a 4xx. This one is caught and translated before it ever gets there.
 */
public class InvalidValue extends RuntimeException {
  private final String reason;

  public InvalidValue(String reason) {
    super(reason);
    this.reason = reason;
  }

  public String reason() {
    return reason;
  }
}
