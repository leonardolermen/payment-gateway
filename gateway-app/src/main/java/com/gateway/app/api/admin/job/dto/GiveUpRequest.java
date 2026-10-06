package com.gateway.app.api.admin.job.dto;

/**
 * The note becomes the job's {@code last_error}, the one thing left to explain a DEAD row nobody
 * ran to the end: required, and capped at the column's 500.
 */
public record GiveUpRequest(String note) {
  private static final int MAX_NOTE = 500;

  public GiveUpRequest {
    if (note == null || note.isBlank()) {
      throw new IllegalArgumentException("note is required");
    }
    note = note.strip();
    if (note.length() > MAX_NOTE) {
      throw new IllegalArgumentException("note must be at most " + MAX_NOTE + " characters");
    }
  }
}
