package io.ratesalgo.backtest.experiment;

import io.ratesalgo.backtest.Strategy;
import io.ratesalgo.backtest.sim.SimDay;

/**
 * Builds the strategy of a named arm for one session. The runner loads an implementation by class
 * name in every worker process, so it needs a public no-argument constructor and no state shared
 * between sessions: a session's result must depend on its seed and its arm only.
 *
 * <p>rates-backtest ships {@link ToyArms} for its own tests; the desk's arms are in rates-app
 * ({@code io.ratesalgo.app.backtest.DeskArms}).
 */
public interface ArmFactory {

  /**
   * Why an arm cannot be built, or null when it can. The runner asks for every arm of a design
   * before it starts, so an arm that is not wired refuses the run instead of failing in a worker.
   */
  String refusal(String arm);

  /** A fresh strategy for {@code arm} on {@code day}, the session's own model. */
  Strategy create(String arm, SimDay day);

  /** Loads a factory by class name. */
  static ArmFactory load(final String className) {
    try {
      return Class.forName(className).asSubclass(ArmFactory.class).getDeclaredConstructor().newInstance();
    } catch (final ReflectiveOperationException | ClassCastException e) {
      throw new IllegalArgumentException("cannot load arm factory " + className + ": " + e, e);
    }
  }
}
