package com.gateway.payments.jobs;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One handler per job type, indexed once at construction and complete or nothing: a type with no
 * handler, or with two, fails the startup instead of leaving a job row that nothing can run.
 */
public class JobHandlers {
  private final Map<JobType, JobHandler> byType;

  public JobHandlers(List<JobHandler> handlers) {
    Map<JobType, JobHandler> indexed = new EnumMap<>(JobType.class);

    for (JobHandler handler : handlers) {
      JobHandler previous = indexed.put(handler.type(), handler);
      if (previous != null) {
        throw new IllegalStateException("two job handlers for " + handler.type());
      }
    }

    for (JobType type : JobType.values()) {
      if (!indexed.containsKey(type)) {
        throw new IllegalStateException("no job handler for " + type);
      }
    }

    this.byType = indexed;
  }

  public JobHandler forType(JobType type) {
    return byType.get(type);
  }
}
