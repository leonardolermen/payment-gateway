package com.gateway.merchants.user;

/** OWNER > FINANCE > READONLY; the route table in the app asks "at least". */
public enum Role {
  READONLY,
  FINANCE,
  OWNER;

  public boolean atLeast(Role minimum) {
    return ordinal() >= minimum.ordinal();
  }
}
