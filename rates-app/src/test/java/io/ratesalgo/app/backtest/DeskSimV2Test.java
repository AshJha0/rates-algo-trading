package io.ratesalgo.app.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.app.loop.DeskProfile;
import io.ratesalgo.app.loop.DeskSettings;
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
    // one desk client id per tier: the ids the simulator sends on the wire (design D12)
    assertEquals(2001, b.clientIdOfTier(1));
    assertEquals(2002, b.clientIdOfTier(2));
    assertEquals(2003, b.clientIdOfTier(3));
    for (int t = 1; t <= 3; t++) {
      assertEquals(t - 1, DeskSettings.V2_CLIENT_TIERS.get(b.clientIdOfTier(t)));
      assertEquals(DeskSettings.v2ClientId(t - 1), b.clientIdOfTier(t));
    }
    assertEquals(7, b.withClientIds(List.of(7, 8, 9)).clientIdOfTier(1));
    assertThrows(IllegalArgumentException.class, () -> b.withClientIds(List.of(1)));
    assertThrows(IllegalArgumentException.class, () -> b.clientIdOfTier(4));
    assertThrows(IllegalArgumentException.class, () -> b.clientIdOfTier(0));
    assertThrows(IllegalArgumentException.class, () -> DeskSettings.v2ClientId(3));
    assertThrows(IllegalArgumentException.class, () -> DeskSettings.v2ClientId(-1));
    assertThrows(IllegalArgumentException.class, () -> b.simInstrument(999));
  }

  @Test
  void theV2UniverseHoldsEveryModelInstrument() {
    final UsdUniverse universe = UsdUniverse.simV2();
    final SimBinding b = SimBinding.of(spec, universe);
    // seven swaps, four Treasuries and TY: nothing of the model is hidden from the v2 desk
    assertEquals(spec.instruments(), b.boundInstruments());
    for (int i = 0; i < spec.instruments(); i++) {
      final int sim = spec.instrument(i).id();
      assertTrue(b.deskId(sim) != 0, "model instrument " + sim);
      assertEquals(sim, b.simId(b.deskId(sim)));
    }
    assertEquals(universe.futureIds()[0], b.deskId(InstrumentSet.TY_FUTURE));
  }

  @Test
  void theArmsAreTheFamilysNineAndEachIsBuildable() {
    final DeskArms arms = new DeskArms();
    for (final String a : DeskArms.names()) {
      assertNull(arms.refusal(a), a);
      assertNotNull(arms.configurationHash(a), a);
    }
    assertTrue(arms.refusal("nobody").startsWith("unknown desk arm"));
    assertThrows(IllegalArgumentException.class, () -> DeskArms.profile("nobody"));
    assertEquals(9, DeskArms.FAMILY.size());
    assertTrue(DeskArms.names().containsAll(DeskArms.FAMILY));
    assertEquals(DeskArms.FAMILY.size() + 1, DeskArms.names().size(), "the family's nine and the old name 'engine'");
    assertEquals(DeskSettings.StrategyPreset.V2, DeskArms.settings("v2").strategyPreset());
    assertEquals(DeskSettings.StrategyPreset.DEFAULT, DeskArms.settings("engine").strategyPreset());
    assertEquals(DeskArms.profile("default"), DeskArms.profile("engine"));
    assertEquals(DeskProfile.v2(), DeskArms.profile("v2"));
    assertEquals(DeskProfile.defaults(), DeskArms.profile("default"));
    assertEquals(DeskSettings.StrategyPreset.CUSTOM, DeskArms.settings("cover_only").strategyPreset());
  }

  /** The keys of {@code arm}'s resolved configuration whose value differs from {@code reference}'s. */
  private static Set<String> differences(final String reference, final String arm) {
    final Map<String, String> a = DeskArms.profile(reference).resolved();
    final Map<String, String> b = DeskArms.profile(arm).resolved();
    assertEquals(a.keySet(), b.keySet());
    final Set<String> out = new TreeSet<>();
    for (final String k : a.keySet()) {
      if (!a.get(k).equals(b.get(k))) {
        out.add(k);
      }
    }
    return out;
  }

  @Test
  void eachAblationDiffersFromV2InExactlyItsOwnSwitches() {
    assertEquals(Set.of("strategy.coverPricing"), differences("v2", "v2_minus_cover_pricing"));
    assertEquals(Set.of("strategy.reservationPrice"), differences("v2", "v2_minus_reservation_price"));
    assertEquals(
        Set.of("strategy.factorCovariance", "strategy.factorHedging", "strategy.tickHedgeCost"),
        differences("v2", "v2_minus_factor_hedging"));
    assertEquals(Set.of("strategy.internalisingHedger"), differences("v2", "v2_minus_internalisation"));
    assertEquals(Set.of("strategy.markoutStore"), differences("v2", "v2_minus_markouts"));
    assertEquals(
        Set.of("strategy.tickRounding", "strategy.volatilityTtl", "strategy.pullFraction"),
        differences("v2", "v2_minus_quote_protection"));
    // cover_only is the default desk with one switch on
    assertEquals(Set.of("strategy.coverPricing"), differences("default", "cover_only"));
    // the headline: every component of the desk differs, the shared settings do not
    final Set<String> headline = differences("default", "v2");
    assertTrue(headline.containsAll(Set.of("pricing", "router", "market", "strategy.clientTiers", "strategy.pullFraction")));
    for (final String k : headline) {
      assertFalse(k.startsWith("settings."), k);
    }
    // the resolved configuration is what each arm builds: the quoting engine's switches, the hedger
    final StrategyConfig r = DeskArms.settings("v2_minus_reservation_price").quoting().strategy();
    assertFalse(r.reservationPrice());
    assertTrue(r.coverPricing() && r.factorCovariance() && r.factorHedging() && r.markoutStore() && r.tickRounding());
    final StrategyConfig f = DeskArms.settings("v2_minus_factor_hedging").quoting().strategy();
    assertFalse(f.factorCovariance() || f.factorHedging() || f.tickHedgeCost());
    final StrategyConfig p = DeskArms.settings("v2_minus_quote_protection").quoting().strategy();
    assertFalse(p.tickRounding() || p.volatilityTtl());
    assertTrue(p.coverPricing());
    assertFalse(DeskArms.settings("v2_minus_internalisation").hedger().internalising());
    assertTrue(DeskArms.settings("v2").hedger().internalising());
    assertFalse(DeskArms.settings("v2_minus_markouts").quoting().strategy().markoutStore());
    assertFalse(DeskArms.settings("v2_minus_cover_pricing").quoting().strategy().coverPricing());
    final StrategyConfig c = DeskArms.settings("cover_only").quoting().strategy();
    assertTrue(c.coverPricing());
    assertFalse(c.reservationPrice() || c.factorHedging() || c.markoutStore() || c.tickRounding());
    assertFalse(DeskArms.settings("cover_only").hedger().internalising());
    // nine distinct configurations
    final Set<String> hashes = new TreeSet<>();
    for (final String a : DeskArms.FAMILY) {
      hashes.add(DeskArms.profile(a).configurationHash());
    }
    assertEquals(9, hashes.size());
    assertEquals(DeskProfile.v2().configurationHash(), DeskArms.profile("v2").configurationHash());
  }

  /** The desk with a recorder of its decisions, and a kill switch thrown at one RFQ. */
  private static final class Watched implements Strategy {
    final DeskStrategy desk;
    final Map<String, Integer> reasons = new TreeMap<>();
    final Map<String, Integer> byInstrument = new TreeMap<>();
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
              byInstrument.merge(o.reason() + ":" + o.instrumentId(), 1, Integer::sum);
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
    assertTrue(Set.of(2001, 2002, 2003).containsAll(w.clients) && !w.clients.isEmpty(), w.clients.toString());
    // the recorded universe holds four swap tenors: the other three are declined for that reason
    assertTrue(w.desk.structuralDeclines() > 0);
    assertEquals(w.desk.rfqsSeen(), r.rfqs().size());
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
  void theV2ArmRunsTheWholeV2DeskOnTheModelsMarket() {
    final DeskArms arms = new DeskArms();
    final SimDay day = day();
    final DeskStrategy desk = (DeskStrategy) arms.create("v2", day);
    final Watched w = new Watched(desk, Long.MAX_VALUE);
    final BacktestResult r = run(day, w);
    assertTrue(desk.quotes() > 0);
    // every quote the V2 desk sent was settled by the model, and every other RFQ declined
    assertEquals(r.rfqs().size(), r.rfqs().stream().filter(q -> q.quoted() && q.settled()).count() + r.declines());
    // pricing v2, fed whole: one round per published second, and never short of a fair value
    assertTrue(desk.curveRounds() > 3_000, "rounds " + desk.curveRounds());
    assertEquals(0L, desk.desk().pricingV2().unfedInputs());
    for (final PassReason bad : List.of(PassReason.UNHEALTHY, PassReason.STALE_INPUT, PassReason.NO_FAIR_VALUE,
        PassReason.UNKNOWN_INSTRUMENT)) {
      assertFalse(w.reasons.containsKey(bad.name()), w.byInstrument.toString());
    }
    // the full simulator v2 universe: no RFQ is declined for its instrument, every tenor is quoted
    assertEquals(0L, desk.structuralDeclines());
    final Set<Integer> quotedSwaps = new TreeSet<>();
    for (final RfqRecord q : r.rfqs()) {
      if (q.quoted() && q.instrumentId() < 200) {
        quotedSwaps.add(q.instrumentId());
      }
    }
    assertTrue(quotedSwaps.stream().anyMatch(id -> Set.of(105, 106, 107).contains(id)), quotedSwaps.toString());
    // hedges went to the model's books through both Treasury CLOBs, every fill reported back
    assertTrue(desk.orders() > 0 && r.hedgeFills() > 0, "orders " + desk.orders() + " hedges " + r.hedgeFills());
    assertEquals(0L, desk.desk().unroutedReports());
    assertEquals(0L, desk.desk().feedGaps());
    // every tier reached the desk's own tier for it
    for (int t = 0; t < 3; t++) {
      for (int k = 0; k < 8; k++) {
        if (k != DeskSettings.V2_TIERS.get(t)) {
          assertEquals(0L, desk.tierRoutes(t, k), "model tier " + t + " reached desk tier " + k);
        }
      }
    }
    assertTrue(desk.tierRoutes(0, DeskSettings.V2_TIERS.get(0)) + desk.tierRoutes(1, DeskSettings.V2_TIERS.get(1)) > 0);
    // the same seed is the same run
    final DeskStrategy again = (DeskStrategy) arms.create("v2", day());
    final BacktestResult second = run(day(), again);
    assertEquals(r.outputChainHash(), second.outputChainHash());
    assertEquals(desk.counters(), again.counters());
  }

  @Test
  void everyArmIsPricedAsOfTheDesksOwnBusinessDateAndQuotesTreasuries() {
    final DeskArms arms = new DeskArms();
    for (final String arm : List.of("default", "cover_only")) {
      final DeskStrategy desk = (DeskStrategy) arms.create(arm, day());
      final Watched w = new Watched(desk, Long.MAX_VALUE);
      run(day(), w);
      // the session's label date is far past the reference data's on-the-run Treasuries; priced
      // as of the label date every Treasury RFQ would be an unknown instrument
      assertFalse(w.reasons.containsKey(PassReason.UNKNOWN_INSTRUMENT.name()), arm + " " + w.byInstrument);
      assertTrue(w.byInstrument.keySet().stream().anyMatch(k -> k.startsWith("NONE:100")), arm + " " + w.byInstrument);
      assertTrue(desk.desk().desk().quoting().rfqs() > 0);
    }
  }

  @Test
  void aV2ProfileNeedsTheSimulatorsBinding() {
    assertThrows(IllegalArgumentException.class, () -> new DeskStrategy(DeskProfile.v2(), UsdUniverse.simV2(), null));
    assertEquals(25_000_000L, DeskStrategy.roundTo(24_999_000L, 2_500_000L));
    assertEquals(-25_000_000L, DeskStrategy.roundTo(-23_750_000L, 2_500_000L));
    assertEquals(0L, DeskStrategy.roundTo(1_249_999L, 2_500_000L));
  }
}
