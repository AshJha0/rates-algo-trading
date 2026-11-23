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
   * <p>The clock is not read for state. Version 2 is a function of the timed calls it is given
   * (the inputs' timestamps and {@link PricingV2#advanceTo}), so a live loop and the replay of its
   * journals, built at different instants, hold the same pricing state after the same calls; it
   * takes its business date from the first of them. Version 1 ignores the clock as it always has.
   * The parameter stays so that the loop's construction is the same for both versions.
   *
   * @param version which binding
   * @param universe the instruments
   * @param clock the loop's clock, not read
   */
  public static PricingBinding create(
      final Version version, final UsdUniverse universe, final Clock clock) {
    return switch (version) {
      case V1 -> new AnalyticsPricing(universe, FairValueSource.NO_VALUE);
      case V2 -> new PricingV2(universe, PricingV2.Options.defaults());
    };
  }
}
