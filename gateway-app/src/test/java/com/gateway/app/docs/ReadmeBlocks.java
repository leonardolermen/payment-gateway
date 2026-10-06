package com.gateway.app.docs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Reads the repository's README.md and the JSON examples it shows under its headings. */
final class ReadmeBlocks {
  private static final String PARENT_ARTIFACT = "<artifactId>payment-gateway-parent</artifactId>";
  private static final String JSON_FENCE = "```json";
  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private ReadmeBlocks() {}

  /** The dotted key paths of the first ```json block after {@code heading}. */
  static SortedSet<String> keysOf(String heading) {
    return keysOf(JSON.readValue(jsonAfter(heading), Map.class));
  }

  /**
   * Every key path, nested ones dotted ({@code pix}, {@code pix.copia_e_cola}). A null value is
   * still a key: a consumer reading the field must find it documented.
   */
  static SortedSet<String> keysOf(Map<?, ?> body) {
    SortedSet<String> keys = new TreeSet<>();
    collect("", body, keys);

    return keys;
  }

  static String jsonAfter(String heading) {
    String readme = read();

    int headingAt = readme.indexOf("\n" + heading + "\n");
    if (headingAt < 0) {
      throw new AssertionError("README.md has no heading " + heading);
    }

    int fenceAt = readme.indexOf(JSON_FENCE, headingAt);
    int nextHeadingAt = readme.indexOf("\n#", headingAt + heading.length() + 1);
    boolean fenceBelongsToHeading = fenceAt >= 0 && (nextHeadingAt < 0 || fenceAt < nextHeadingAt);
    if (!fenceBelongsToHeading) {
      throw new AssertionError("README.md has no json block under " + heading);
    }

    int bodyStart = fenceAt + JSON_FENCE.length();
    int bodyEnd = readme.indexOf("```", bodyStart);

    return readme.substring(bodyStart, bodyEnd);
  }

  private static void collect(String prefix, Map<?, ?> body, SortedSet<String> keys) {
    for (Map.Entry<?, ?> entry : body.entrySet()) {
      String path = prefix + entry.getKey();
      keys.add(path);

      if (entry.getValue() instanceof Map<?, ?> nested) {
        collect(path + ".", nested, keys);
      }
    }
  }

  private static String read() {
    try {
      return Files.readString(repositoryRoot().resolve("README.md")).replace("\r\n", "\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // Surefire runs each module from its own directory, an IDE often from the root: walk up to the
  // parent pom instead of trusting either. Every module pom names the parent too (in <parent>), so
  // only the one that also lists <modules> is the root.
  private static boolean isRoot(String pom) {
    return pom.contains(PARENT_ARTIFACT) && pom.contains("<modules>");
  }

  private static Path repositoryRoot() throws IOException {
    for (Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        directory != null;
        directory = directory.getParent()) {
      Path pom = directory.resolve("pom.xml");
      if (Files.exists(pom) && isRoot(Files.readString(pom))) {
        return directory;
      }
    }

    throw new IllegalStateException(
        "no payment-gateway-parent pom above " + System.getProperty("user.dir"));
  }
}
