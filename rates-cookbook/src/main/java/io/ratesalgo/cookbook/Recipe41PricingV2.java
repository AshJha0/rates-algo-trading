package io.ratesalgo.cookbook;

import io.ratesalgo.analytics.curve.SofrCurve;
import io.ratesalgo.app.pricing.PricingV2;
import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.refdata.RefData;
import io.ratesalgo.sim.MarketModel;
import io.ratesalgo.sim.MarketSpec;
import io.ratesalgo.sim.SessionSeeds;
import io.ratesalgo.sim.SimParams;
import io.ratesalgo.venues.Venues;
import io.ratesalgo.venues.sim.SimV2CurveRound;
import io.ratesalgo.venues.sim.SimV2Session;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** COOKBOOK.md recipe 41: pricing v2's SOFR curve and a leave-one-out fair value. */
public final class Recipe41PricingV2 {

  private static final long ROUND_NANOS = 500_000_000L;
  private static final double BP = 1e-4;

  private Recipe41PricingV2() {}

  public static void main(final String[] args) {
    final ManualClock clock = new ManualClock(Venues.T0);
    final MarketSpec spec = new MarketSpec(SimParams.loadDefault());
    final MarketModel model =
        SessionSeeds.session(spec, SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 95_001));
    final SimV2Session session =
        new SimV2Session(
            model, SimV2Session.instruments(spec, RefData.loadDefault(), Venues.TRADE_DATE), clock);
    final SimV2CurveRound quotes = new SimV2CurveRound(session);
    final UsdUniverse universe = UsdUniverse.simV2();
    final PricingV2 pricing = new PricingV2(universe, PricingV2.Options.defaults());

    // one round of the simulator's curve quotes: the whole build set under one timestamp
    send(pricing, quotes, clock.epochNanos(), -1, 0L);
    final SofrCurve curve = pricing.curve();
    final Map<SofrCurve.Kind, Integer> kinds = new EnumMap<>(SofrCurve.Kind.class);
    for (int i = 0; i < curve.inputCount(); i++) {
      kinds.merge(curve.kind(i), 1, Integer::sum);
    }
    System.out.println("inputs " + kinds + ", not yet fed " + pricing.unfedInputs());
    for (final int months : new int[] {24, 60, 120, 360}) {
      final int in = curve.swapInput(months);
      System.out.printf(
          Locale.ROOT, "  %2dY par quote %.4f %%, zero rate %.4f %%%n",
          months / 12, 100.0 * curve.quote(in), 100.0 * curve.curve().zeroRate(curve.pillarDate(in)));
    }

    final int tenYear = curve.swapInput(120);
    final int tenYearId = idOf(universe, 120);
    final int sevenYearId = idOf(universe, 84);
    final double quote0 = curve.quote(tenYear);
    final double model0 = pricing.modelValue(tenYearId);
    final double fair0 = pricing.fairValue(tenYearId);
    final double seven0 = pricing.modelValue(sevenYearId);
    System.out.printf(
        Locale.ROOT, "10Y quote %.4f %%, leave-one-out %.3f %%, fair %.4f %%%n",
        100.0 * quote0, 100.0 * model0, 100.0 * fair0);

    // half a second later the same quotes, except that the 10-year alone is 2 bp higher
    clock.advance(ROUND_NANOS);
    send(pricing, quotes, clock.epochNanos(), tenYear, 20_000_000L);
    System.out.println("the 10Y quote alone moves up 2 bp:");
    System.out.printf(
        Locale.ROOT, "  curve's 10Y quote %+.3f bp%n", (pricing.curve().quote(tenYear) - quote0) / BP);
    System.out.printf(
        Locale.ROOT, "  10Y leave-one-out value moved %.3f bp%n",
        Math.abs(pricing.modelValue(tenYearId) - model0) / BP);
    System.out.printf(
        Locale.ROOT, "  10Y fair value %+.4f bp%n", (pricing.fairValue(tenYearId) - fair0) / BP);
    System.out.printf(
        Locale.ROOT, "  7Y leave-one-out value %+.3f bp%n", (pricing.modelValue(sevenYearId) - seven0) / BP);
  }

  private static int idOf(final UsdUniverse universe, final int months) {
    for (final UsdUniverse.SwapSpec s : universe.swaps()) {
      if (s.tenorMonths() == months) {
        return s.instrumentId();
      }
    }
    throw new IllegalArgumentException("no swap of " + months + " months");
  }

  /** Sends every input of the build set; input {@code skewed} is sent {@code skew} higher (percent mantissa). */
  private static void send(
      final PricingV2 pricing, final SimV2CurveRound quotes, final long stamp, final int skewed, final long skew) {
    pricing.advanceTo(stamp);
    final SofrCurve c = pricing.curve();
    for (int i = 0; i < pricing.inputCount(); i++) {
      final long value =
          switch (c.kind(i)) {
            case OVERNIGHT -> quotes.overnight();
            case SR1, SR3 -> quotes.futurePrice();
            case SWAP -> quotes.parSwap(c.tenorMonths(i)) + (i == skewed ? skew : 0L);
          };
      pricing.setInput(pricing.inputId(i), value, stamp);
    }
    pricing.repriceIfDirty();
  }
}
