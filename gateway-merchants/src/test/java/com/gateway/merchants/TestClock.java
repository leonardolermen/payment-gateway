package com.gateway.merchants;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** System time plus an offset tests can move forward; reset after each test that advances it. */
public class TestClock extends Clock {
  private volatile Duration offset = Duration.ZERO;

  public void advance(Duration duration) {
    offset = offset.plus(duration);
  }

  public void reset() {
    offset = Duration.ZERO;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @Override
  public Instant instant() {
    return Instant.now().plus(offset);
  }
}
