package io.ratesalgo.ops.config;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Validates configuration files against the schema; run in CI (ops.yml) and available in every
 * image. {@code ConfigValidatorMain SCHEMA CONFIG...}; exit 0 if all are valid, 1 otherwise, 2 on
 * bad arguments or an unreadable schema.
 */
public final class ConfigValidatorMain {

  private ConfigValidatorMain() {}

  public static void main(final String[] args) {
    System.exit(run(args, System.out));
  }

  /** Runs the validator; returns the exit status. */
  public static int run(final String[] args, final PrintStream out) {
    if (args.length < 2) {
      out.println("usage: ConfigValidatorMain SCHEMA CONFIG...");
      return 2;
    }
    final JsonSchema schema;
    try {
      schema = ProcessConfig.schema(Path.of(args[0]));
    } catch (final IOException | RuntimeException e) {
      out.println("schema " + args[0] + ": " + e.getMessage());
      return 2;
    }
    int failures = 0;
    for (int i = 1; i < args.length; i++) {
      try {
        final List<String> errors = ProcessConfig.check(Path.of(args[i]), schema);
        if (errors.isEmpty()) {
          out.println("valid   " + args[i]);
        } else {
          failures++;
          out.println("INVALID " + args[i]);
          errors.forEach(e -> out.println("        " + e));
        }
      } catch (final IOException | RuntimeException e) {
        failures++;
        out.println("INVALID " + args[i] + ": " + e.getMessage());
      }
    }
    return failures == 0 ? 0 : 1;
  }
}
