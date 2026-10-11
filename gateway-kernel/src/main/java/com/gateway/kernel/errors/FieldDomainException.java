package com.gateway.kernel.errors;

/**
 * A domain error about one field of the request: the edge adds {@code field} to the problem so a
 * form can mark the input, in the spelling the client sent (e.g. {@code merchant_key}).
 */
public class FieldDomainException extends DomainException {
  private final String field;

  public FieldDomainException(String code, String message, String field) {
    super(code, message);
    this.field = field;
  }

  public String field() {
    return field;
  }
}
