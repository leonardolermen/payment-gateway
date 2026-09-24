package com.gateway.kernel.ids;

import com.github.f4b6a3.ulid.UlidCreator;
import java.util.regex.Pattern;

/**
 * ULID as the id of everything: time-ordered (B-tree friendly), 26 Crockford base32 chars — which
 * fits the Pix {@code txid} ({@code [a-zA-Z0-9]{26,35}}) with no transformation. A UUID does not
 * (36 chars with hyphens).
 */
public final class Ulid {
  private static final Pattern FORMAT = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");

  private Ulid() {}

  /** Monotonic within a millisecond: two ids generated back to back sort in generation order. */
  public static String next() { return UlidCreator.getMonotonicUlid().toString(); }

  public static boolean isValid(String s) { return s != null && FORMAT.matcher(s).matches(); }
}
