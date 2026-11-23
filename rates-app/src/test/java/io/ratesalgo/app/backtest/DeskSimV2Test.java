package io.ratesalgo.app.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.app.loop.DeskSettings;
import io.ratesalgo.app.pricing.PricingBindings;
import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.backtest.BacktestConfig;
import io.ratesalgo.backtest.BacktestResult;
import io.ratesalgo.backtest.BacktestRunner;
import io.ratesalgo.backtest.FillRecord;
import io.ratesalgo.backtest.InstrumentSet;
import io.ratesalgo.backtest.RfqRecord;
import io.ratesalgo.backtest.Strategy;
import io.ratesalgo.backtest.StrategyContext;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.sim.PairedSessions;
import io.ratesalgo.backtest.sim.SimDay;
import io.ratesalgo.backtest.synth.SyntheticDay;
import io.ratesalgo.messages.sbe.BookUpdateDecoder;
import io.ratesalgo.messages.sbe.CurveInputDecoder;
import io.ratesalgo.messages.sbe.ExecutionReportDecoder;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteResponseDecoder;
import io.ratesalgo.quoting.PassReason;
import io.ratesalgo.quoting.QuotingEngine;
import io.ratesalgo.quoting.RfqOutcome;
import io.ratesalgo.quoting.StrategyConfig;
import io.ratesalgo.sim.MarketSpec;
import io.ratesalgo.sim.SessionSeeds;
import io.ratesalgo.sim.SimParams;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The production desk on simulator v2 in the back-tester: the id binding, the arms, a healthy
 * desk that quotes and hedges on the model's book, determinism, and the quote withdrawal reaching
 * the back-tester. Nothing here reads a P&amp;L level.
 */
class DeskSimV2Test {

  private static final long HOUR = 3600L * 1_000_000_000L;
  private static final long SEED = SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 64_000);

  @TempDir static Path dir;

  private static MarketSpec spec;
  private static Presets presets;
  private static Path journal;

  @BeforeAll
  static void generate() {
    spec = new MarketSpec(SimParams.loadDefault());
    presets = Presets.load(DeskBacktest.presets());
    journal = dir.resolve("window.raj");
    SyntheticDay.generateSimV2(
        SimDay.of(spec, SEED, PairedSessions.labelDate(SEED)), SyntheticDay.Publication.simV2(presets), journal, null, 2 * HOUR);
  }

  private static SimDay day() {
    return SimDay.of(spec, SEED, PairedSessions.labelDate(SEED));
  }

  private static BacktestResult run(final SimDay day, final Strategy s) {
    return new BacktestRunner(BacktestConfig.forSimV2(presets, day, List.of(journal), null)).run(s);
  }

  @Test
  void theBindingMapsTheModelsUniverseOntoTheDesks() {
    final UsdUniverse universe = UsdUniverse.standard();
    final SimBinding b = SimBinding.of(spec, universe);
    // four swap tenors and four Treasuries; the 3, 7 and 20 year swaps and TY are not the desk's
    assertEquals(8, b.boundInstruments());
    final Set<Integer> desk = new TreeSet<>();
    for (final UsdUniverse.SwapSpec s : universe.swaps()) {
      desk.add(s.instrumentId());
    }
    for (final int id : new int[] {InstrumentSet.SOFR_2Y, InstrumentSet.SOFR_5Y, InstrumentSet.SOFR_10Y, InstrumentSet.SOFR_30Y}) {
      assertTrue(desk.contains(b.deskId(id)), "swap " + id);
      assertEquals(id, b.simId(b.deskId(id)));
    }
    for (final int id : new int[] {105, 106, 107, InstrumentSet.TY_FUTURE}) {
      assertEquals(0, b.deskId(id));
    }
    for (final UsdUniverse.BondSpec bond : universe.bonds()) {
      assertEquals(bond.instrumentId(), b.deskId(b.simId(bond.instrumentId())));
    }
    assertThrows(IllegalArgumentException.class, () -> b.simId(999_999));
    // one fixed desk client id per tier, ids the V2 tier table places in tiers A, B and C
    assertEquals(2001, b.clientIdOfTier(1));
    assertEquals(2003, b.clientIdOfTier(2));
    assertEquals(2005, b.clientIdOfTier(3));
    for (int t = 1; t <= 3; t++) {
      assertEquals(t - 1, DeskSettings.V2_CLIENT_TIERS.get(b.clientIdOfTier(t)));
    }
    assertEquals(7, b.withClientIds(List.of(7, 8, 9)).clientIdOfTier(1));
    assertThrows(IllegalArgumentException.class, () -> b.withClientIds(List.of(1)));
    assertThrows(IllegalArgumentException.class, () -> b.clientIdOfTier(4));
  }

  @Test
  void theArmsAreNamedExactlyAndUnwiredOnesRefuse() {
    final DeskArms arms = new DeskArms();
    assertNull(arms.refusal("default"));
    assertNull(arms.refusal("engine"));
    assertNull(arms.refusal("v2"));
    assertTrue(arms.refusal("nobody").startsWith("unknown desk arm"));
    for (final String a : List.of("cover_pricing", "v2_without_reservation", "v2_without_factor", "v2_hedge_at_trigger",
        "v2_without_markouts", "v2_without_protection")) {
      assertEquals(DeskArms.W1, arms.refusal(a), a);
      assertThrows(IllegalStateException.class, () -> DeskArms.settings(a));
    }
    assertEquals(DeskArms.W2, arms.refusal("v2_pricing_v2"));
    assertEquals(PricingBindings.Version.V2, DeskArms.arm("default_pricing_v2").pricing());
    assertEquals(DeskArms.W3, arms.refusal("default_router_ladder"));
    assertEquals(DeskArms.Router.LADDER, DeskArms.arm("v2_router_ladder").router());
    assertEquals(DeskSettings.StrategyPreset.V2, DeskArms.settings("v2").strategyPreset());
    assertEquals(DeskSettings.StrategyPreset.DEFAULT, DeskArms.settings("engine").strategyPreset());
    assertThrows(IllegalArgumentException.class, () -> DeskArms.arm("nobody"));
    assertEquals(13, DeskArms.names().size());
    // each ablation turns off exactly its own switches
    final StrategyConfig r = DeskArms.arm("v2_without_reservation").quoting().apply(StrategyConfig.allOn());
    assertFalse(r.reservationPrice());
    assertTrue(r.coverPricing() && r.factorCovariance() && r.factorHedging() && r.markoutStore() && r.tickRounding());
    final StrategyConfig f = DeskArms.arm("v2_without_factor").quoting().apply(StrategyConfig.allOn());
    assertFalse(f.factorCovariance() || f.factorHedging() || f.tickHedgeCost());
    assertTrue(f.reservationPrice() && f.coverPricing());
    final StrategyConfig p = DeskArms.arm("v2_without_protection").quoting().apply(StrategyConfig.allOn());
    assertFalse(p.tickRounding() || p.volatilityTtl());
    assertEquals(0.0, DeskArms.arm("v2_without_protection").pullFraction(), 0.0);
    final StrategyConfig c = DeskArms.arm("cover_pricing").quoting().apply(StrategyConfig.allOn());
    assertTrue(c.coverPricing());
    assertFalse(c.reservationPrice() || c.factorHedging() || c.markoutStore());
    assertTrue(DeskArms.arm("v2_hedge_at_trigger").defaultHedger());
    assertFalse(DeskArms.arm("v2_without_markouts").quoting().apply(StrategyConfig.allOn()).markoutStore());
  }

  /** The desk with a recorder of its decisions, and a kill switch thrown at one RFQ. */
  private static final class Watched implements Strategy {
    final DeskStrategy desk;
    final Map<String, Integer> reasons = new TreeMap<>();
    final Set<Integer> clients = new TreeSet<>();
    final long killAtRfq;
    long killedRfq;

    Watched(final DeskStrategy desk, final long killAtRfq) {
      this.desk = desk;
      this.killAtRfq = killAtRfq;
    }

    @Override
    public String name() {
      return "watched";
    }

    @Override
    public void onStart(final StrategyContext c) {

      desk.onStart(c);
      desk.desk().desk().quoting().recorder(
          new QuotingEngine.RfqRecordListener() {
            @Override
            public void onDecision(final RfqOutcome o) {
              reasons.merge(String.valueOf(o.reason()), 1, Integer::sum);
              clients.add(o.clientId());
            }

            @Override
            public void onResult(
                final long rfqId, final long quoteId, final io.ratesalgo.messages.sbe.QuoteStatus status, final long ourPrice,
                final long coverPrice, final double coverGapBp, final int rank) {}
          });
    }

    @Override
    public void onQuoteRequest(final QuoteRequestDecoder rfq, final StrategyContext c) {
      final long id = rfq.rfqId();
      final long quotes = desk.quotes();
      desk.onQuoteRequest(rfq, c);
      if (killedRfq == 0 && desk.quotes() > quotes && desk.quotes() >= killAtRfq) {
        // the operator kills the desk while this quote is live: the engine withdraws it
        killedRfq = id;
        desk.desk().desk().killSwitch().trigger(KillReason.Manual, 0, 1);
        desk.onTimer(0L, c.nowEpochNanos(), c);
      }
    }

    @Override
    public void onBookUpdate(final BookUpdateDecoder u, final StrategyContext c) {
      desk.onBookUpdate(u, c);
    }

    @Override
    public void onCurveInput(final CurveInputDecoder i, final StrategyContext c) {
      desk.onCurveInput(i, c);
    }

    @Override
    public void onQuoteResponse(final QuoteResponseDecoder r, final StrategyContext c) {
      desk.onQuoteResponse(r, c);
    }

    @Override
    public void onExecutionReport(final ExecutionReportDecoder r, final StrategyContext c) {
      desk.onExecutionReport(r, c);
    }

    @Override
    public void onTimer(final long timerId, final long now, final StrategyContext c) {
      desk.onTimer(timerId, now, c);
    }
  }

  @Test
  void theDeskQuotesAndHedgesOnTheModelsMarketAndIsDeterministic() {
    final UsdUniverse universe = UsdUniverse.standard();
    final SimDay day = day();
    final Watched w = new Watched(new DeskStrategy(universe, DeskSettings.defaults(), SimBinding.of(spec, universe)), Long.MAX_VALUE);
    final BacktestResult r = run(day, w);
    // a healthy desk: no RFQ passed for unhealthy or stale inputs, or for want of a fair value
    for (final PassReason bad : List.of(PassReason.UNHEALTHY, PassReason.STALE_INPUT, PassReason.NO_FAIR_VALUE)) {
      assertFalse(w.reasons.containsKey(bad.name()), w.reasons.toString());
    }
    assertTrue(w.desk.quotes() > 10, "quotes " + w.desk.quotes());
    assertTrue(r.clientFills() > 0);
    // hedges reached the model's book as child orders and were filled there
    assertTrue(w.desk.orders() > 0 && r.hedgeFills() > 0, "orders " + w.desk.orders() + " hedges " + r.hedgeFills());
    for (final FillRecord f : r.fills()) {
      if (f.hedge()) {
        assertTrue(day.instrument(f.instrumentId()).hasBook());
      }
    }
    assertTrue(day.levelOffset(day.closeEpochNanos()) != 0.0, "the desk's own orders moved the level factor");
    // clients reach the desk with their tier's fixed id
    assertTrue(Set.of(2001, 2003, 2005).containsAll(w.clients) && !w.clients.isEmpty(), w.clients.toString());
    // RFQs on instruments the desk does not hold are not shown to it
    for (final RfqRecord q : r.rfqs()) {
      if (Set.of(105, 106, 107).contains(q.instrumentId())) {
        assertFalse(q.quoted());
      }
    }
    // the same seed is the same run
    final UsdUniverse again = UsdUniverse.standard();
    final BacktestResult second =
        run(day(), new DeskStrategy(again, DeskSettings.defaults(), SimBinding.of(spec, again)));
    assertEquals(r.outputChainHash(), second.outputChainHash());
    assertEquals(r.total(), second.total());
  }

  @Test
  void aQuoteWithdrawalReachesTheBackTester() {
    final UsdUniverse universe = UsdUniverse.standard();
    final Watched w = new Watched(new DeskStrategy(universe, DeskSettings.defaults(), SimBinding.of(spec, universe)), 3);
    final BacktestResult r = run(day(), w);
    assertTrue(w.killedRfq != 0);
    assertTrue(w.desk.withdrawals() >= 1, "withdrawals " + w.desk.withdrawals());
    final RfqRecord killed = r.rfqs().stream().filter(q -> q.rfqId() == w.killedRfq).findFirst().orElseThrow();
    assertTrue(killed.withdrawn());
    assertFalse(killed.won());
    assertTrue(w.reasons.containsKey(PassReason.KILLED.name()), w.reasons.toString());
  }

  @Test
  void theDailyReportOfASessionIsLabelledSimulatorOutput(@TempDir final Path out) throws java.io.IOException {
    final DeskBacktest.SimRun run =
        DeskBacktest.runSimV2(SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 64_001), DeskSettings.defaults(), DeskBacktest.presets(), out,
            HOUR);
    assertTrue(run.result().rfqs().size() > 0);
    final String md = java.nio.file.Files.readString(out.resolve("report-v2.md"));
    assertTrue(md.contains("Simulator v2 output under data/calibration/sim_v2_params.json"), md);
    assertTrue(java.nio.file.Files.readString(out.resolve("report-v2.json")).contains("\"markouts\""));
    assertTrue(java.nio.file.Files.isRegularFile(out.resolve("results.raj")));
    assertEquals(SessionSeeds.ACCEPTANCE + 64_001, run.day().model().seed());
    assertThrows(IllegalArgumentException.class,
        () -> DeskBacktest.runSimV2(8_020_549L, DeskSettings.defaults(), DeskBacktest.presets(), out, HOUR));
  }

  @Test
  void theDeskArmsRunASessionThroughTheFactory() {
    final DeskArms arms = new DeskArms();
    final SimDay day = day();
    final Strategy s = arms.create("v2", day);
    assertTrue(s instanceof DeskStrategy);
    final BacktestResult r = run(day, s);
    assertTrue(((DeskStrategy) s).quotes() > 0);
    // every quote the V2 desk sent was settled by the model, and every other RFQ declined
    assertEquals(r.rfqs().size(), r.rfqs().stream().filter(q -> q.quoted() && q.settled()).count() + r.declines());
  }
}
