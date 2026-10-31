package io.ratesalgo.posttrade.recon;

/** Looks a break up in the journal-derived facts. {@link KeeperFacts} is the implementation. */
@FunctionalInterface
public interface BreakExplainer {

  /** Explainer that never explains anything. */
  BreakExplainer NONE = (key, cls) -> Explanation.NONE;

  /** The explanation for a break of class {@code breakClass} on {@code matchKey}. */
  Explanation explain(String matchKey, BreakClass breakClass);
}
