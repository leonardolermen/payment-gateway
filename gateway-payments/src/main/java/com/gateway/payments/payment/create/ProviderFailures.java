package com.gateway.payments.payment.create;

import com.gateway.kernel.provider.ProviderException;
import java.util.Set;

/**
 * What a failed bank call means for a create.
 *
 * <p>Which codes may mean "it landed" is the flow's call and arrives as a set, not as a flag on
 * this class: for Pix it is TIMEOUT and UNAVAILABLE, because neither says whether the PUT reached
 * the bank; for a boleto CONFLICT joins them, because the nosso número is ours and was reserved
 * before the call, so "this number already exists" can only be our own earlier attempt that arrived
 * (ruling R4).
 */
public final class ProviderFailures {

  public enum Outcome {
    /** The bank may hold the charge: ask before deciding, never fail on the first exception. */
    MAY_HAVE_LANDED,
    /** The bank refused it outright, so there is nothing to ask about. */
    DECLINED,
    /** The bank could not answer, and the code does not suggest the call landed. */
    UNAVAILABLE
  }

  private ProviderFailures() {}

  public static Outcome classify(
      ProviderException failure, Set<ProviderException.Code> mayHaveLanded) {
    if (mayHaveLanded.contains(failure.code())) {
      return Outcome.MAY_HAVE_LANDED;
    }

    boolean declined =
        failure.code() == ProviderException.Code.INVALID
            || failure.code() == ProviderException.Code.DECLINED;

    return declined ? Outcome.DECLINED : Outcome.UNAVAILABLE;
  }

  /**
   * The merchant-facing code for a call that may have landed but could not be confirmed. A timeout
   * is told apart from the rest because it is the one a client is expected to retry with the same
   * Idempotency-Key.
   */
  public static String timeoutCodeOf(ProviderException failure) {
    return failure.code() == ProviderException.Code.TIMEOUT
        ? "PROVIDER_TIMEOUT"
        : "PROVIDER_UNAVAILABLE";
  }
}
