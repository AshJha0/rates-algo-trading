package io.ratesalgo.cookbook;

import io.ratesalgo.sim.MarketModel;
import io.ratesalgo.sim.MarketSpec;
import io.ratesalgo.sim.RfqDecision;
import io.ratesalgo.sim.SessionSeeds;
import io.ratesalgo.sim.SimInstrument;
import io.ratesalgo.sim.SimParams;
import io.ratesalgo.sim.SimParams.Scalar;
import io.ratesalgo.sim.SimRfq;
import java.util.Locale;

/** COOKBOOK.md recipe 40: open a simulator v2 session and read its parameters, RFQs and factors. */
public final class Recipe40SimV2Session {

  private Recipe40SimV2Session() {}

  public static void main(final String[] args) {
    final SimParams p = SimParams.loadDefault();
    final MarketSpec spec = new MarketSpec(p);
    System.out.println("calibrated parameters");
    System.out.printf(Locale.ROOT, "  swap RFQs per session %.1f%n", p.get(Scalar.SWAP_RFQS_PER_DAY));
    System.out.printf(
        Locale.ROOT, "  competitor median half-spread %.4f bp, log sd %.2f%n",
        p.get(Scalar.MEDIAN_HALF_SPREAD_BP), p.get(Scalar.COMPETITOR_LOG_SD));
    System.out.printf(
        Locale.ROOT, "  informed fraction %.2f, move per informed trade %.3f bp%n",
        p.get(Scalar.INFORMED_FRACTION), p.get(Scalar.MOVE_PER_INFORMED_TRADE_BP));
    System.out.printf(
        Locale.ROOT, "  factor sd per sqrt-minute: level %.4f, slope %.4f, curvature %.4f bp%n",
        p.factorSdPerSqrtMinute(0), p.factorSdPerSqrtMinute(1), p.factorSdPerSqrtMinute(2));

    // seeds of the acceptance base only: the other bases are reserved for registered experiments
    final long seed = SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 95_001);
    final MarketModel day = SessionSeeds.session(spec, seed);
    System.out.printf(
        Locale.ROOT, "session %d, day type %s, %d steps of %d s%n",
        seed, day.dayType(), spec.steps(), MarketSpec.STEP_NANOS / 1_000_000_000L);

    int swaps = 0;
    int informed = 0;
    for (final SimRfq r : day.rfqs()) {
      swaps += spec.instrument(r.instrument()).kind() == SimInstrument.Kind.SWAP ? 1 : 0;
      informed += r.informed() ? 1 : 0;
    }
    System.out.printf(
        Locale.ROOT, "RFQs %d: %d swaps, %d Treasuries; %d informed%n",
        day.rfqs().size(), swaps, day.rfqs().size() - swaps, informed);
    for (final SimRfq r : day.rfqs().subList(0, 3)) {
      // the client's decision when the desk does not quote at all
      final RfqDecision d = day.decide(r, Double.NaN, 0L);
      System.out.printf(
          Locale.ROOT, "  %s client %s %.0fm, tier index %d, %d dealers asked: %s%s%n",
          spec.instrument(r.instrument()).key(), r.clientBuys() ? "buys" : "sells", r.quantityUnits() / 1e6,
          r.tier(), r.dealersAsked(), d.outcome(),
          d.traded() ? String.format(Locale.ROOT, " at %.3f bp from mid", d.winnerHalfSpreadBp()) : "");
    }

    // the 10-year yield change of the session is loadings times factors plus its own term
    final int i = spec.indexOf("UST-10Y");
    final int last = spec.steps() - 1;
    final String[] names = {"level", "slope", "curvature"};
    double sum = 0.0;
    for (int f = 0; f < SimParams.FACTORS; f++) {
      final double part = spec.loading(i, f) * day.factor(f, last);
      sum += part;
      System.out.printf(
          Locale.ROOT, "%-9s moved %+.3f bp x loading %+.4f = %+.3f bp%n",
          names[f], day.factor(f, last), spec.loading(i, f), part);
    }
    System.out.printf(
        Locale.ROOT, "idiosyncratic %+.3f bp, 10-year yield change %+.3f bp%n",
        day.yieldChangeBp(i, last) - sum, day.yieldChangeBp(i, last));
    System.out.println(
        "same seed, same session: " + (SessionSeeds.session(spec, seed).replayHash() == day.replayHash()));
  }
}
