package io.ratesalgo.venues.cert;

import java.util.List;

/**
 * Result of one certification script.
 *
 * @param name script name
 * @param venue venue kind
 * @param flow flow under test
 * @param steps steps executed
 * @param expectations messages checked
 * @param failures descriptions of failed steps; empty when the script passed
 * @param sequenceErrors engine-side session sequence errors during the run
 */
public record CertResult(
    String name,
    String venue,
    String flow,
    int steps,
    int expectations,
    List<String> failures,
    long sequenceErrors) {

  /** Copies the list. */
  public CertResult {
    failures = List.copyOf(failures);
  }

  /** True if every step passed and the session saw no sequence errors. */
  public boolean passed() {
    return failures.isEmpty() && sequenceErrors == 0;
  }
}
