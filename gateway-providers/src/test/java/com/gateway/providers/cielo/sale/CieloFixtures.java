package com.gateway.providers.cielo.sale;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads a file of src/test/resources/cielo/fixtures (see its README for each file's origin). */
public final class CieloFixtures {
  private CieloFixtures() {}

  public static String read(String name) {
    try {
      return Files.readString(
          Path.of("src/test/resources/cielo/fixtures/" + name), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
