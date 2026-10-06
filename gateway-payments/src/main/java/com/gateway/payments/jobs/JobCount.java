package com.gateway.payments.jobs;

/** How many jobs share one (status, type): one metric series each. */
public record JobCount(String status, JobType type, long count) {}
