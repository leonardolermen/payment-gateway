package com.gateway.kernel.provider;

/**
 * A provider call failed. {@code code} is the gateway's normalized vocabulary; the raw provider
 * error (RFC 7807 {@code type}) travels in {@code providerType} so support can read what the bank
 * said without the gateway having to model every bank error.
 */
public class ProviderException extends RuntimeException {
  /**
   * CONFLICT: the bank refuses because the resource is already in the state the caller wants to
   * leave (a baixa on a boleto it shows paid). CREDENTIALS_INCOMPLETE: the credential parses but
   * lacks a field this operation needs; {@code providerType} names the field so the merchant's
   * 422 can say which one.
   */
  public enum Code { DECLINED, UNAVAILABLE, INVALID, TIMEOUT, NOT_FOUND, UNAUTHENTICATED, CONFLICT, CREDENTIALS_INCOMPLETE, UNKNOWN }
  private final Code code;
  private final int httpStatus;
  private final String providerType;

  public ProviderException(Code code, int httpStatus, String providerType, String message) {
    super(message);
    this.code = code; this.httpStatus = httpStatus; this.providerType = providerType;
  }
  public ProviderException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = code; this.httpStatus = 0; this.providerType = null;
  }
  public Code code() { return code; }
  public int httpStatus() { return httpStatus; }
  public String providerType() { return providerType; }
}
