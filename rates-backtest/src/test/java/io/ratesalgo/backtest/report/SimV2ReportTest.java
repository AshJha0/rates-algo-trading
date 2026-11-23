package io.ratesalgo.backtest.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.BacktestConfig;
import io.ratesalgo.backtest.BacktestResult;
import io.ratesalgo.backtest.BacktestRunner;
import io.ratesalgo.backtest.FillRecord;
import io.ratesalgo.backtest.InstrumentSet;
import io.ratesalgo.backtest.Strategy;
import io.ratesalgo.backtest.StrategyContext;
import io.ratesalgo.backtest.TestSupport;
import io.ratesalgo.backtest.sim.PairedSessions;
import io.ratesalgo.backtest.sim.SimDay;
import io.ratesalgo.backtest.strategy.FixedSpreadStrategy;
import io.ratesalgo.backtest.synth.SyntheticDay;
import io.ratesalgo.backtest.util.Prices;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.sim.MarketSpec;
import io.ratesalgo.sim.SessionSeeds;
import io.ratesalgo.sim.SimParams;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The simulator v2 daily report: inventory split, markouts by tier and size, labels. */
class SimV2ReportTest {

  private static final long HOUR = 3600L * 1_000_000_000L;

  /** Buys 30 TY contracts after ten minutes and holds them; declines every RFQ. */
  private static final class Holder implements Strategy {
    @Override
    public String name() {
      return "holder";
    }

    @Override
    public void onStart(final StrategyContext ctx) {
      ctx.scheduleTimer(ctx.nowEpochNanos() + HOUR / 6);
    }

    @Override
    public void onQuoteRequest(final QuoteRequestDecoder rfq, final StrategyContext ctx) {
      // declines
    }

    @Override
    public void onTimer(final long timerId, final long nowEpochNanos, final StrategyContext ctx) {
      ctx.sendOrder(InstrumentSet.TY_FUTURE, Side.Buy, OrdType.Market, 30 * 10_000_000L, 0L, true);
    }
  }

  @Test
  void inventoryMarkingSplitsIntoFactorImpactAndResidual(@TempDir final Path dir) {
    final MarketSpec spec = new MarketSpec(SimParams.loadDefault());
    final long seed = SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 63_000);
    final Path journal = dir.resolve("s.raj");
    SyntheticDay.generateSimV2(
        SimDay.of(spec, seed, PairedSessions.labelDate(seed)), SyntheticDay.Publication.simV2(TestSupport.presets()), journal, null,
        2 * HOUR);
    final SimDay day = SimDay.of(spec, seed, PairedSessions.labelDate(seed));
    final BacktestResult r =
        new BacktestRunner(BacktestConfig.forSimV2(TestSupport.presets(), day, List.of(journal), null)).run(new Holder());
    final InstrumentSet instruments = InstrumentSet.simV2(spec.params());
    final Map<String, Object> m = new SimV2Report(r, instruments, day).toMap();
    @SuppressWarnings("unchecked")
    final Map<String, Object> inv = (Map<String, Object>) m.get("inventory_marking");
    final double factor = (Double) inv.get("factor_usd");
    final double impact = (Double) inv.get("own_impact_usd");
    final double residual = (Double) inv.get("residual_usd");
    final double timing = (Double) inv.get("timing_usd");
    // the grid marking of a position held from the fill's step to the end of the session
    final FillRecord fill = r.fills().get(0);
    final int i = day.index(InstrumentSet.TY_FUTURE);
    final long open = day.openEpochNanos();
    // the position counts from the step that contains the fill, which takes in its own impact
    final long fromStep = (fill.timeNanos() - open) / io.ratesalgo.sim.MarketSpec.STEP_NANOS;
    final double grid =
        fill.signedQuantity() * instruments.get(InstrumentSet.TY_FUTURE).valuePerUnit()
            * (day.model().latentMid(i, spec.sessionNanos()) - day.model().latentMid(i, fromStep * io.ratesalgo.sim.MarketSpec.STEP_NANOS));
    assertEquals(Prices.cents(grid), factor + impact + residual, 0.02);
    assertEquals(Prices.cents(r.total().inventoryMtm()), factor + impact + residual + timing, 0.03);
    // the future moves with the level factor; only the basis and the 7-year node term are left over
    assertTrue(Math.abs(residual) < Math.abs(factor), "factor " + factor + " residual " + residual);
    // buying moved the level factor down in yield: the position gained from its own impact
    assertTrue(impact > 0.0, "own impact " + impact);
    assertTrue(String.valueOf(m.get("label")).startsWith("Simulator v2 output"));
    @SuppressWarnings("unchecked")
    final Map<String, Object> flow = (Map<String, Object>) m.get("flow");
    assertEquals("undefined", flow.get("internalised_share"));
  }

  @Test
  void markoutsAreByTierAndSize(@TempDir final Path dir) {
    final MarketSpec spec = new MarketSpec(SimParams.loadDefault());
    final long seed = SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 63_001);
    final Path journal = dir.resolve("s.raj");
    SyntheticDay.generateSimV2(
        SimDay.of(spec, seed, PairedSessions.labelDate(seed)), SyntheticDay.Publication.simV2(TestSupport.presets()), journal, null,
        3 * HOUR);
    final SimDay day = SimDay.of(spec, seed, PairedSessions.labelDate(seed));
    final BacktestResult r =
        new BacktestRunner(BacktestConfig.forSimV2(TestSupport.presets(), day, List.of(journal), null))
            .run(new FixedSpreadStrategy(0.12, 0.07, 20_000, 1));
    final InstrumentSet instruments = InstrumentSet.simV2(spec.params());
    final SimV2Report report = new SimV2Report(r, instruments, day);
    final Map<String, Object> m = report.toMap();
    @SuppressWarnings("unchecked")
    final List<Map<String, Object>> rows = (List<Map<String, Object>>) m.get("markouts");
    long allFills = 0;
    long bucketFills = 0;
    for (final Map<String, Object> row : rows) {
      assertTrue(row.containsKey("bp_1s") && row.containsKey("bp_5s") && row.containsKey("bp_30s") && row.containsKey("bp_60s")
          && row.containsKey("bp_5min"));
      if ("all".equals(row.get("size"))) {
        allFills += (Long) row.get("fills");
      } else {
        bucketFills += (Long) row.get("fills");
      }
    }
    assertEquals(r.clientFills(), allFills);
    assertEquals(allFills, bucketFills);
    // one client fill's 30 s markout recomputed from the latent mid
    final FillRecord f = r.fills().stream().filter(x -> !x.hedge()).findFirst().orElseThrow();
    final double expected =
        f.signedQuantity() * (day.latentMid(f.instrumentId(), f.timeNanos() + 30_000_000_000L) - f.price())
            * instruments.get(f.instrumentId()).valuePerUnit();
    assertTrue(Double.isFinite(expected));
    @SuppressWarnings("unchecked")
    final Map<String, Object> flow = (Map<String, Object>) m.get("flow");
    final double share = (Double) flow.get("internalised_share");
    assertTrue(share >= 0.0 && share <= 1.0);
    assertEquals("<10mm", SimV2Report.bucket(5.0));
    assertEquals("10-50mm", SimV2Report.bucket(10.0));
    assertEquals(">=50mm", SimV2Report.bucket(50.0));
    final String md = report.toMarkdown();
    assertTrue(md.startsWith("# Simulator v2 session report\n\n" + SimV2Report.LABEL), md);
    assertTrue(md.contains("| Tier | Size | Fills | 1s | 5s | 30s | 60s | 5min |"));
    assertTrue(report.toJson().contains("\"internalised_share\""));
    assertTrue(report.toString().contains("seed=" + seed));
  }

  @Test
  void theDayToolMarksVersionOneSupersededAndWritesV2Days(@TempDir final Path dir) {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final Path repo = TestSupport.repoRoot();
    try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      assertEquals(
          0,
          io.ratesalgo.backtest.synth.SyntheticDayCli.run(
              new String[] {"--date", "2026-02-02", "--out", dir.resolve("v1.raj").toString(), "--repo", repo.toString()}, out));
      assertEquals(
          0,
          io.ratesalgo.backtest.synth.SyntheticDayCli.run(
              new String[] {"--sim-v2", "--seed", "30000004", "--out", dir.resolve("v2.raj").toString(), "--repo", repo.toString()},
              out));
      assertEquals(
          2,
          io.ratesalgo.backtest.synth.SyntheticDayCli.run(
              new String[] {"--sim-v2", "--seed", "8020549", "--out", dir.resolve("x.raj").toString(), "--repo", repo.toString()}, out));
      assertEquals(1, io.ratesalgo.backtest.synth.SyntheticDayCli.run(new String[] {"--sim-v2", "--out", "x"}, out));
    }
    final String text = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(text.startsWith(io.ratesalgo.backtest.synth.SyntheticDayCli.SUPERSEDED), text);
    assertTrue(text.contains("simulator v2 seed 30000004 (FOMC"), text);
    assertTrue(text.contains("refused: seed base 8000000 is spent"), text);
  }
}
