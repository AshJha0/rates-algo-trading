package io.ratesalgo.cookbook;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.InMemoryBookSource;
import io.ratesalgo.api.InMemoryFairValueSource;
import io.ratesalgo.api.InMemoryInstrumentDirectory;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.ParametricSensitivityProvider;
import io.ratesalgo.api.RecordingHedgeOrderSink;
import io.ratesalgo.api.RecordingOutboundSink;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.hedge.HedgeUniverse;
import io.ratesalgo.hedge.Hedger;
import io.ratesalgo.hedge.TableHedgeCostModel;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.quoting.ClientTier;
import io.ratesalgo.quoting.ClobQuoter;
import io.ratesalgo.quoting.Desk;
import io.ratesalgo.quoting.HitRatePreset;
import io.ratesalgo.quoting.QuotingConfig;
import io.ratesalgo.quoting.QuotingEngine;
import io.ratesalgo.quoting.RfqOutcome;
import io.ratesalgo.quoting.TierTable;
import io.ratesalgo.risk.RiskLimits;
import java.io.IOException;
import java.util.Locale;

/** COOKBOOK.md recipe 14: quote an RFQ through the engine with a stub fair value; see the skew. */
public final class Recipe14QuoteRfq {

  private Recipe14QuoteRfq() {}

  public static void main(final String[] args) throws IOException {
    final ManualClock clock = new ManualClock(1_767_268_800_000_000_000L); // 2026-01-01T12:00Z
    final InMemoryInstrumentDirectory instruments = new InMemoryInstrumentDirectory();
    instruments.add(Instrument.bond(103, "UST10", CurrencyCode.USD, 10.0, 2_500_000_000L));
    final ParametricSensitivityProvider pv01 = new ParametricSensitivityProvider(instruments);
    pv01.defineAtTenor(103, -8.2e-4, 0.0); // PV01 per unit of face: -820 USD per 1m per bp
    final InMemoryFairValueSource fair = new InMemoryFairValueSource();
    fair.set(103, Decimal9.round(98.25), clock.epochNanos());

    final Desk desk =
        new Desk(
            new Desk.Inputs(
                instruments,
                pv01,
                fair,
                new InMemoryBookSource(4),
                new RiskLimits().bucketPv01(250_000.0),
                new HedgeUniverse(),
                new TableHedgeCostModel(1),
                new RecordingOutboundSink(),
                new RecordingOutboundSink(),
                new RecordingHedgeOrderSink(),
                clock,
                new QuotingConfig().kappa(2e-6).maxSkewBp(2.0),
                TierTable.single(1, new ClientTier(1, 1.0, 10_000_000_000L, true, false)),
                HitRatePreset.shippedDefault(),
                Hedger.config(),
                ClobQuoter.config()));
    final QuotingEngine engine = desk.quoting();

    // a client asks to buy 25m UST10 against four dealers; we quote an offer
    engine.onQuoteRequest(7, 1, 42, 103, Side.Buy, 2_500_000_000L, 4, 0L, 1L);
    print("flat book ", engine.outcome());

    // the same request when we are already long 150m: the offer improves
    desk.risk().onFill(103, 15_000_000_000L, fair.fairMid(103));
    engine.onQuoteRequest(7, 2, 43, 103, Side.Buy, 2_500_000_000L, 4, 0L, 2L);
    print("long 150m ", engine.outcome());

    // and the bid to a client who wants to sell us more is worse
    engine.onQuoteRequest(7, 3, 44, 103, Side.Sell, 2_500_000_000L, 4, 0L, 3L);
    print("long 150m ", engine.outcome());
  }

  private static void print(final String label, final RfqOutcome o) {
    System.out.println(
        String.format(
            Locale.ROOT,
            "%s%s %s %.0fm at %.6f: half %.3fbp, skew %+.3fbp, size %.3fbp, distance %.3fbp, P(win) %.3f",
            label,
            o.decision(),
            o.ourSide(),
            o.quantity() / 1e8,
            Decimal9.toDouble(o.price()),
            o.halfSpreadBp(),
            o.skewBp(),
            o.sizeTermBp(),
            o.distanceBp(),
            o.winProbability()));
  }
}
