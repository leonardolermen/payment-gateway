package com.gateway.kernel.errors;

public class NotFoundException extends DomainException {
  public NotFoundException(String what, String id) {
    super("NOT_FOUND", what + " not found: " + id);
  }
}
