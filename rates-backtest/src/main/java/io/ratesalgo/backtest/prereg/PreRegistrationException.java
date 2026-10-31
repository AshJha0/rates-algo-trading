package io.ratesalgo.backtest.prereg;

import java.util.List;

/** A pre-registration is missing or invalid; carries every problem found. */
public final class PreRegistrationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final java.util.ArrayList<String> problems;

  public PreRegistrationException(final List<String> problems) {
    super("pre-registration refused: " + String.join("; ", problems));
    this.problems = new java.util.ArrayList<>(problems);
  }

  public List<String> problems() {
    return List.copyOf(problems);
  }
}
