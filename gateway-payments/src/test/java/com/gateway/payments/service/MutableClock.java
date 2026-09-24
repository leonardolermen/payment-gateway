package com.gateway.payments.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** A clock tests can move forward (the 90-day refund window, expiration). */
public class MutableClock extends Clock {
  // Truncated to micros: Postgres stores microseconds, so an equality check after a round trip
  // would fail on the sub-microsecond digits Windows clocks produce.
  private volatile Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

  public void reset() {
    now = Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  public void advance(Duration d) {
    now = now.plus(d);
  }

  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }
}
