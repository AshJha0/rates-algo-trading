package io.ratesalgo.router;

/**
 * Told once when a parent order reaches a terminal state. Called on the router's thread, possibly
 * from inside {@link SmartOrderRouter#poll}, {@link SmartOrderRouter#cancel} or a report; it must
 * not call back into the router and must not allocate.
 */
@FunctionalInterface
public interface ParentListener {

  /** Ignores every parent. */
  ParentListener NONE = (parentId, outcome, quantity, filled, unresolved) -> {};

  /**
   * A parent ended.
   *
   * @param parentId the parent
   * @param outcome how it ended
   * @param quantity the quantity submitted
   * @param filled the quantity filled
   * @param unresolved quantity on children the venue never answered for ({@link
   *     ParentOutcome#Failed} only): it may or may not have traded
   */
  void onParentDone(long parentId, ParentOutcome outcome, long quantity, long filled, long unresolved);
}
