package com.gateway.payments.dispute;

import java.util.List;

/**
 * {@code nextCursor} is set when the page came back full: there MAY be more, and the next call can
 * come back empty (same contract as the operator's queue and the deliveries listing).
 */
public record DisputePage(List<Dispute> disputes, String nextCursor) {}
