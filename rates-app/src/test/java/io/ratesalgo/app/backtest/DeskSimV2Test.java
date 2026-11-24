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
import io.ratesalgo.backtest.experiment.BandParameters;
import io.ratesalgo.backtest.experiment.SessionExecutor;
import io.ratesalgo.backtest.Strategy;
import io.ratesalgo.backtest.StrategyContext;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.sim.PairedSessions;
import io.ratesalgo.backtest.sim.SimDay;
import io.ratesalgo.backtest.synth.SyntheticDay;
import io.ratesalgo.backtest.util.Yaml;
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
  void theArmsAreTheFamilysTenAndEachIsBuildable() {
    final DeskArms arms = new DeskArms();
    for (final String a : DeskArms.names()) {
      assertNull(arms.refusal(a), a);
      assertNotNull(arms.configurationHash(a), a);
    }
    assertTrue(arms.refusal("nobody").startsWith("unknown desk arm"));
    assertThrows(IllegalArgumentException.class, () -> DeskArms.profile("nobody"));
    assertEquals(10, DeskArms.FAMILY.size());
    assertEquals("baseline", DeskArms.FAMILY.get(0));
    assertEquals("legacy", DeskArms.FAMILY.get(9));
    assertEquals(DeskArms.FAMILY.subList(0, 9), DeskArms.COMMON_FOOTING);
    assertTrue(DeskArms.names().containsAll(DeskArms.FAMILY));
    assertEquals(DeskArms.FAMILY.size() + 2, DeskArms.names().size(), "the family's ten and the old names 'default', 'engine'");
    assertEquals(DeskSettings.StrategyPreset.V2, DeskArms.settings("v2").strategyPreset());
    assertEquals(DeskSettings.StrategyPreset.DEFAULT, DeskArms.settings("baseline").strategyPreset());
    assertEquals(DeskSettings.StrategyPreset.DEFAULT, DeskArms.settings("engine").strategyPreset());
    // the older names are the desk as built, not the baseline on the common footing
    assertEquals(DeskArms.profile("legacy"), DeskArms.profile("default"));
    assertEquals(DeskArms.profile("legacy"), DeskArms.profile("engine"));
    assertEquals(DeskProfile.v2(), DeskArms.profile("v2"));
    assertEquals(DeskProfile.simV2(DeskSettings.strategyV2()), DeskArms.profile("v2"));
    assertEquals(DeskProfile.simV2(DeskSettings.defaults()), DeskArms.profile("baseline"));
    assertEquals(DeskSettings.StrategyPreset.CUSTOM, DeskArms.settings("cover_only").strategyPreset());
  }

  @Test
  void baselineAndV2StandOnOneFootingAndDifferExactlyInTheStrategySwitches() {
    final DeskProfile baseline = DeskArms.profile("baseline");
    final DeskProfile v2 = DeskArms.profile("v2");
    // the same market, universe, pricing and router
    assertEquals(v2.market(), baseline.market());
    assertEquals(DeskProfile.Market.SIM_V2, baseline.market());
    assertEquals(v2.pricing(), baseline.pricing());
    assertEquals(io.ratesalgo.app.pricing.PricingBindings.Version.V2, baseline.pricing());
    assertEquals(v2.router(), baseline.router());
    assertEquals(DeskProfile.RouterModel.LADDER, baseline.router());
    assertEquals(
        java.util.Arrays.toString(v2.newUniverse().instrumentIds()),
        java.util.Arrays.toString(baseline.newUniverse().instrumentIds()));
    assertEquals(
        java.util.Arrays.toString(UsdUniverse.simV2().instrumentIds()),
        java.util.Arrays.toString(baseline.newUniverse().instrumentIds()));
    // the default strategy with its declared settings, unchanged: only the footing is v2's
    assertEquals(DeskSettings.defaults(), baseline.settings());
    assertEquals(DeskSettings.StrategySwitches.DEFAULT, baseline.settings().strategy());
    // swapping the strategy switches turns one arm into the other, and nothing else does
    assertEquals(v2, baseline.withSettings(baseline.settings().withStrategy(v2.settings().strategy())));
    assertEquals(baseline, v2.withSettings(v2.settings().withStrategy(baseline.settings().strategy())));
    final Set<String> strategyKeys = new TreeSet<>();
    for (final String k : baseline.resolved().keySet()) {
      if (k.startsWith("strategy.")) {
        strategyKeys.add(k);
      }
    }
    assertEquals(12, strategyKeys.size());
    assertEquals(strategyKeys, differences("baseline", "v2"));
    // every arm on the common footing differs from the baseline in strategy switches only
    for (final String arm : DeskArms.COMMON_FOOTING) {
      for (final String k : differences("baseline", arm)) {
        assertTrue(k.startsWith("strategy."), arm + " differs from baseline in " + k);
      }
    }
    // the built objects follow: the baseline quotes and hedges as the recorded strategy does
    assertFalse(DeskArms.settings("baseline").quoting().strategy().coverPricing());
    assertFalse(DeskArms.settings("baseline").hedger().internalising());
    assertEquals(1, DeskArms.settings("baseline").tiers().tierOf(DeskSettings.v2ClientId(0)).tier());
  }

  @Test
  void legacyIsTheDeskAsBuiltIn110() {
    // DeskSettings.defaults() of v1.1.0, pricing v1, the touch router, the recorded universe
    final Map<String, String> expected = new java.util.LinkedHashMap<>();
    expected.put("settings.housekeepingNanos", "10000000");
    expected.put("settings.hedgeIntervalNanos", "250000000");
    expected.put("settings.tradeRatePerSecond", "2.0E9");
    expected.put("settings.hedgeLotMinor", "100000000");
    expected.put("settings.hedgeMaxLots", "500.0");
    expected.put("settings.seed", "2026");
    expected.put("settings.quoteRatePerSecond", "1000.0");
    expected.put("settings.limitPreset", "BACKTEST");
    for (final String k : List.of("coverPricing", "reservationPrice", "factorCovariance", "factorHedging",
        "tickHedgeCost", "markoutStore", "tickRounding", "volatilityTtl")) {
      expected.put("strategy." + k, "false");
    }
    expected.put("strategy.volatilityReferenceTenor", "NaN");
    expected.put("strategy.pullFraction", "0.0");
    expected.put("strategy.internalisingHedger", "false");
    expected.put("strategy.clientTiers", "false");
    expected.put("pricing", "V1");
    expected.put("router", "TOUCH");
    expected.put("market", "STANDARD");
    expected.put("markoutLoad", "null");
    expected.put("markoutSave", "null");
    assertEquals(expected, DeskArms.profile("legacy").resolved());
    assertEquals(DeskProfile.defaults().resolved(), DeskArms.profile("legacy").resolved());
    assertEquals(DeskProfile.defaults().configurationHash(), DeskArms.profile("legacy").configurationHash());
    assertTrue(DeskArms.profile("legacy").isRecorded());
    assertFalse(DeskArms.profile("baseline").isRecorded());
    // it differs from the baseline in the footing only, never in a strategy switch
    assertEquals(Set.of("pricing", "router", "market"), differences("baseline", "legacy"));
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
    // cover_only is the baseline with one switch on
    assertEquals(Set.of("strategy.coverPricing"), differences("baseline", "cover_only"));
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
    // ten distinct configurations
    final Set<String> hashes = new TreeSet<>();
    for (final String a : DeskArms.FAMILY) {
      hashes.add(DeskArms.profile(a).configurationHash());
    }
    assertEquals(10, hashes.size());
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
    for (final String arm : List.of("legacy", "baseline", "cover_only")) {
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
  void theBaselineRunsUnchangedOnTheCommonFooting() {
    final SimDay day = day();
    final DeskStrategy desk = (DeskStrategy) new DeskArms().create("baseline", day);
    final Watched w = new Watched(desk, Long.MAX_VALUE);
    final BacktestResult r = run(day, w);
    // a healthy desk on the v2 market: fed whole by pricing v2, never short of an input or a price
    for (final PassReason bad : List.of(PassReason.UNHEALTHY, PassReason.STALE_INPUT, PassReason.NO_FAIR_VALUE,
        PassReason.UNKNOWN_INSTRUMENT)) {
      assertFalse(w.reasons.containsKey(bad.name()), w.byInstrument.toString());
    }
    assertTrue(desk.curveRounds() > 3_000, "rounds " + desk.curveRounds());
    assertEquals(0L, desk.desk().pricingV2().unfedInputs());
    // the whole universe: nothing declined for its instrument, the v2 desk's extra tenors quoted
    assertEquals(0L, desk.structuralDeclines());
    assertTrue(desk.quotes() > 10, "quotes " + desk.quotes());
    assertTrue(r.rfqs().stream().anyMatch(q -> q.quoted() && Set.of(105, 106, 107).contains(q.instrumentId())));
    // hedges routed to the model's books by the ladder router, every report matched
    assertTrue(desk.orders() > 0 && r.hedgeFills() > 0, "orders " + desk.orders() + " hedges " + r.hedgeFills());
    // a known defect outside the strategy, counted per arm in every session record: now and then a
    // report or cancel reject reaches the loop for a child its router no longer holds (reported to
    // the owner of DeskGroup; the v2 arm shows none on this window)
    assertEquals(desk.desk().unroutedReports(), desk.counters().get("unrouted_reports"));
    assertTrue(desk.desk().unroutedReports() * 100 <= desk.orders(), "unrouted " + desk.counters());
    assertEquals(0L, desk.desk().feedGaps());
    // one tier for every client, as the recorded desk has
    for (int t = 0; t < 3; t++) {
      for (int k = 0; k < 8; k++) {
        if (k != 1) {
          assertEquals(0L, desk.tierRoutes(t, k), "model tier " + t + " reached desk tier " + k);
        }
      }
    }
  }

  /** Session parts kept in memory, for a session run by the experiment's executor. */
  private static final class MemoryParts implements SessionExecutor.Parts {
    private final Map<String, Map<String, Object>> parts = new java.util.HashMap<>();

    @Override
    public Map<String, Object> row(final String row) {
      return parts.get(row);
    }

    @Override
    public void putRow(final String row, final Map<String, Object> value) {
      parts.put(row, value);
    }

    @Override
    public Map<String, Object> arm(final String row, final String arm) {
      return parts.get(row + "/" + arm);
    }

    @Override
    public void putArm(final String row, final String arm, final Map<String, Object> value) {
      parts.put(row + "/" + arm, value);
    }
  }

  @Test
  void allTenArmsSeeTheSameInputsUnderCommonRandomNumbers(@TempDir final Path work) {
    final long seed = SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 64_002);
    final BandParameters.Row central = new BandParameters.Row(BandParameters.CENTRAL, SimParams.loadDefault());
    final Map<String, Object> session =
        new SessionExecutor(presets, work)
            .run(seed, List.of(new SessionExecutor.RowRun(central, DeskArms.FAMILY)), new DeskArms(), new MemoryParts(),
                true, HOUR / 2);
    final Map<String, Object> row = Yaml.asMap(Yaml.asMap(session.get("rows"), "rows").get(BandParameters.CENTRAL), "row");
    final Map<String, Object> arms = Yaml.asMap(row.get("arms"), "arms");
    assertEquals(DeskArms.FAMILY, List.copyOf(arms.keySet()));
    final Set<String> configs = new TreeSet<>();
    for (final String arm : DeskArms.FAMILY) {
      final Map<String, Object> m = Yaml.asMap(arms.get(arm), arm);
      // one journal, replayed bit for bit by every arm
      assertEquals(row.get("journal_chain"), m.get("input_chain"), arm);
      assertEquals(r(row.get("rfqs")), r(m.get("rfqs_seen")), arm);
      configs.add(String.valueOf(m.get("config_hash")));
      // timing only: the defect count is kept, no P&L line is
      assertTrue(m.containsKey("unrouted_reports") && !m.containsKey("position_pnl"), arm);
      final long structural = r(m.get("structural_declines"));
      if ("legacy".equals(arm)) {
        assertTrue(structural > 0, "the recorded universe declines the 3, 7 and 20-year swaps and TY");
      } else {
        assertEquals(0L, structural, arm + " stands on the common footing");
      }
    }
    assertEquals(10, configs.size());
  }

  private static long r(final Object o) {
    return ((Number) o).longValue();
  }

  @Test
  void aV2ProfileNeedsTheSimulatorsBinding() {
    assertThrows(IllegalArgumentException.class, () -> new DeskStrategy(DeskProfile.v2(), UsdUniverse.simV2(), null));
    assertEquals(25_000_000L, DeskStrategy.roundTo(24_999_000L, 2_500_000L));
    assertEquals(-25_000_000L, DeskStrategy.roundTo(-23_750_000L, 2_500_000L));
    assertEquals(0L, DeskStrategy.roundTo(1_249_999L, 2_500_000L));
  }
}
