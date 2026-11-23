package io.ratesalgo.app.pricing;

import io.ratesalgo.api.FairValueSource;
import io.ratesalgo.common.time.Clock;

/**
 * Selects the pricing binding. Version 1 is the default and is constructed exactly as the desk
 * loop constructed it before the selection existed, so every recorded figure of the simulated
 * desk is unchanged while {@link Version#V1} is selected. Version 2 becomes the default together
 * with simulator v2, in a pre-registered step (docs/research/PRICING_V2.md Section 10).
 */
public final class PricingBindings {

  /** The versions. */
  public enum Version {
    /** {@link AnalyticsPricing}: five swap pillars, bond fair value from the book. */
    V1,
    /** {@link PricingV2}: the full SOFR curve, fitted Treasury spreads, two-factor risk. */
    V2
  }

  private PricingBindings() {}

  /** The default version. */
  public static Version defaultVersion() {
    return Version.V1;
  }

  /**
   * Creates a binding.
   *
   * <p>Version 2 is built as of the start of the clock's business day, the last New York cut,
   * and not as of the instant of the call. The binding stamps its start-of-day marks with the
   * time it is built at, and the residuals it carries afterwards depend on that stamp. A live
   * loop is built before its first input and its replay on the first journaled frame, so the
   * instant differs between the two; the start of the business day does not, and two desks built
   * at any two instants of one business day hold the same pricing state after the same inputs.
   *
   * @param version which binding
   * @param universe the instruments
   * @param clock the loop's clock; version 2 takes its business date from it, version 1 ignores it
   */
  public static PricingBinding create(
      final Version version, final UsdUniverse universe, final Clock clock) {
    return switch (version) {
      case V1 -> new AnalyticsPricing(universe, FairValueSource.NO_VALUE);
      case V2 -> {
        final PricingV2.Options options = PricingV2.Options.defaults();
        yield new PricingV2(universe, businessDayStart(clock, options.cutHour()), options);
      }
    };
  }

  /** A clock standing at the last cut at or before {@code clock}'s time. */
  public static Clock businessDayStart(final Clock clock, final int cutHour) {
    final long start = BusinessDate.lastCut(clock.epochNanos(), cutHour);
    return new Clock() {
      @Override
      public long nanoTime() {
        return start;
      }

      @Override
      public long epochNanos() {
        return start;
      }
    };
  }
}
