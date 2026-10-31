package io.ratesalgo.cookbook;

import java.nio.file.Files;
import java.nio.file.Path;

/** Finds the repository root (the directory holding data/presets.yaml) from the working directory. */
final class RepoRoot {

  private RepoRoot() {}

  static Path find() {
    Path p = Path.of("").toAbsolutePath();
    while (p != null && !Files.isRegularFile(p.resolve("data/presets.yaml"))) {
      p = p.getParent();
    }
    if (p == null) {
      throw new IllegalStateException("run from inside the repository: data/presets.yaml not found");
    }
    return p;
  }
}
