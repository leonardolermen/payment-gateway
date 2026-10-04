package com.gateway.billing.subscription;

import java.time.LocalDate;

/** {@code end} is exclusive: it is the first day of the next period, and the day it is billed. */
public record BillingPeriod(LocalDate start, LocalDate end) {}
