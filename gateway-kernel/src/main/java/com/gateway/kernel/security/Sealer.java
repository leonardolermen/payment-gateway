package com.gateway.kernel.security;

/**
 * Authenticated encryption of a small secret bound to a context: what one module seals, only the
 * same context opens. In the kernel because payments must store an acquirer's card token encrypted
 * and may not import merchants, where the envelope cipher and its master key live (plan C8).
 *
 * <p>{@link #open} throws {@link SecurityException} for a tampered blob or another context, never a
 * detail of which one.
 */
public interface Sealer {
  byte[] seal(byte[] plaintext, String context);

  byte[] open(byte[] sealed, String context);
}
