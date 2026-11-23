package io.ratesalgo.backtest.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.InstrumentSet;
import io.ratesalgo.backtest.Strategy;
import io.ratesalgo.backtest.StrategyContext;
import io.ratesalgo.backtest.TestSupport;
import io.ratesalgo.backtest.pnl.Attribution;
import io.ratesalgo.backtest.stats.Stats;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.sim.DayType;
import io.ratesalgo.sim.MarketSpec;
import io.ratesalgo.sim.SessionSeeds;
import io.ratesalgo.sim.SimParams;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The experiment harness of design section 12: sessions by seed base, arms on common random
 * numbers, the paired primary test and the pilot's arithmetic.
 *
 * <p>No experiment is run here and no desk strategy is used. The arms are toys with a known
 * relation (one buys a few futures and holds them, the other buys twice as many), so the
 * assertions are about the pairing, not about any P&amp;L level.
 */
class PairedSessionsTest {

  /** Declines every RFQ; after one hour buys {@code contracts} TY at market and holds them. */
  private static final class Holder implements Strategy {
    private final int contracts;

    Holder(final int contracts) {
      this.contracts = contracts;
    }

    @Override
    public String name() {
      return "hold_" + contracts;
    }

    @Override
    public void onStart(final StrategyContext ctx) {
      ctx.scheduleTimer(ctx.nowEpochNanos() + 3600L * 1_000_000_000L);
    }

    @Override
    public void onQuoteRequest(final QuoteRequestDecoder rfq, final StrategyContext ctx) {
      // no quote
    }

    @Override
    public void onTimer(final long timerId, final long nowEpochNanos, final StrategyContext ctx) {
      if (contracts > 0) {
        ctx.sendOrder(InstrumentSet.TY_FUTURE, Side.Buy, OrdType.Market, contracts * 10_000_000L, 0L, true);
      }
    }
  }

  @Test
  void armsRunTheSameSessionsAndThePairedTestIsOnTheirDifference(@TempDir final Path dir) throws IOException {
    final MarketSpec spec = new MarketSpec(SimParams.loadDefault());
    final PairedSessions harness = new PairedSessions(spec, TestSupport.presets(), dir);
    final List<PairedSessions.ArmResult> arms =
        harness.run(
            SessionSeeds.ACCEPTANCE,
            80_000,
            4,
            List.of(
                new PairedSessions.Arm("idle", () -> new Holder(0)),
                new PairedSessions.Arm("hold_1", () -> new Holder(1)),
                new PairedSessions.Arm("hold_2", () -> new Holder(2))));
    assertEquals(3, arms.size());
    final PairedSessions.ArmResult idle = arms.get(0);
    final PairedSessions.ArmResult one = arms.get(1);
    final PairedSessions.ArmResult two = arms.get(2);
    for (int n = 0; n < 4; n++) {
      final long seed = SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 80_000 + n);
      // common random numbers: the same seed, day type and RFQs for every arm
      for (final PairedSessions.ArmResult arm : arms) {
        assertEquals(seed, arm.sessions().get(n).seed());
        assertEquals(SessionSeeds.dayType(seed), arm.sessions().get(n).dayType());
        assertEquals(idle.sessions().get(n).rfqs(), arm.sessions().get(n).rfqs());
        assertEquals(0, arm.sessions().get(n).clientFills());
        assertEquals(0.0, arm.sessions().get(n).clientDv01Usd(), 0.0);
      }
      assertTrue(idle.sessions().get(n).rfqs() > 100);
      // an arm that does nothing has no P&L; holding moves with the session's rates
      assertEquals(0.0, idle.sessions().get(n).pnlUsd(), 0.0);
      assertEquals(0, idle.sessions().get(n).hedgeFills());
      assertEquals(1, one.sessions().get(n).hedgeFills());
      assertNotEquals(0.0, one.sessions().get(n).pnlUsd());
      // two contracts on the same path earn twice one contract's P&L, but for the second contract's
      // own impact (well under a dollar): the arms share the path, the touch and the fee
      assertEquals(2.0 * one.sessions().get(n).pnlUsd(), two.sessions().get(n).pnlUsd(), 5.0);
    }
    assertEquals("hold_1", one.name());
    assertEquals(0.0, one.meanClientDv01Usd(), 0.0);

    final PairedSessions.Comparison c = PairedSessions.compare(two, one, 11L);
    final double[] d = Stats.pairedDifferences(two.pnlUsd(), one.pnlUsd());
    assertEquals(Stats.mean(d), c.paired().meanDifference(), 1e-9);
    assertEquals(4, c.paired().n());
    assertEquals(3.0, c.paired().df(), 0.0);
    assertTrue(c.bootstrap().lower() <= c.paired().meanDifference() && c.paired().meanDifference() <= c.bootstrap().upper());
    assertEquals(0.95, c.bootstrap().level(), 0.0);
    assertEquals("hold_2", c.treatment());
    assertEquals("hold_1", c.baseline());
    // no client trades in the baseline arm, so there is no DV01 to normalise by
    assertTrue(Double.isNaN(c.meanDifferenceBpOfClientDv01()));
    // the paired difference is far tighter than either arm: that is what the pairing buys
    assertTrue(Stats.variance(d) < Stats.variance(two.pnlUsd()));

    // the journals are deleted once their arms have run
    try (Stream<Path> left = Files.list(dir)) {
      assertEquals(0, left.count());
    }
  }

  private static PairedSessions.ArmResult arm(final String name, final long firstSeed, final double... pnl) {
    final List<PairedSessions.Session> sessions = new ArrayList<>();
    for (int n = 0; n < pnl.length; n++) {
      sessions.add(
          new PairedSessions.Session(
              firstSeed + n, DayType.NONE, new Attribution(0, 0, 0, 0, 0, 0, 0, pnl[n]), 250_000.0, 230, 60, 5));
    }
    return new PairedSessions.ArmResult(name, sessions);
  }

  @Test
  void comparisonReportsTheDifferenceInUsdAndInBpOfClientDv01() {
    final PairedSessions.ArmResult base = arm("base", 100, 10_000, -20_000, 5_000, 40_000, -15_000, 0, 12_000, -8_000);
    final PairedSessions.ArmResult treated =
        arm("treated", 100, 12_500, -18_000, 7_000, 43_500, -13_000, 1_500, 15_000, -5_500);
    final PairedSessions.Comparison c = PairedSessions.compare(treated, base, 7L);
    assertEquals(2_375.0, c.paired().meanDifference(), 1e-9);
    // 2,375 USD on 250,000 USD of client DV01 per session is 0.0095 bp
    assertEquals(0.0095, c.meanDifferenceBpOfClientDv01(), 1e-12);
    assertTrue(c.paired().p() < 0.001);
    assertTrue(c.bootstrap().lower() > 0.0);
    assertThrows(IllegalArgumentException.class, () -> PairedSessions.compare(treated, arm("other", 101, new double[8]), 7L));
    assertThrows(IllegalArgumentException.class, () -> PairedSessions.compare(treated, arm("short", 100, 1.0, 2.0), 7L));
  }

  @Test
  void pilotGivesTheSessionsAConfirmationSetNeeds() {
    // the design's worked table: sigma / delta = 157 / 30 and correlation 0.9 need 92 sessions at
    // alpha 0.005 and power 0.9, far below the floor of 2,000
    final double sdOfDifference = 157_000.0 * Math.sqrt(2.0 * (1.0 - 0.9));
    assertEquals(92, (long) Math.ceil(PairedSessions.SESSIONS_PER_VARIANCE_RATIO * Math.pow(sdOfDifference / 30_000.0, 2)));
    assertEquals(2000, PairedSessions.impliedSessions(sdOfDifference, 30_000.0));
    // uncorrelated arms: 916 per the table, still under the floor; a difference sd of 20 effects needs 6,680
    assertEquals(916.0, PairedSessions.SESSIONS_PER_VARIANCE_RATIO * 2.0 * Math.pow(157.0 / 30.0, 2), 2.0);
    assertEquals(6680, PairedSessions.impliedSessions(600_000.0, 30_000.0));
    assertThrows(IllegalArgumentException.class, () -> PairedSessions.impliedSessions(1.0, 0.0));
    assertThrows(IllegalArgumentException.class, () -> PairedSessions.impliedSessions(-1.0, 1.0));
    // (z_0.0025 + z_0.10)^2 with the model's own inverse normal
    final double z = io.ratesalgo.sim.Gaussian.inverse(1.0 - 0.0025) + io.ratesalgo.sim.Gaussian.inverse(0.9);
    assertEquals(PairedSessions.SESSIONS_PER_VARIANCE_RATIO, z * z, 0.05);

    final PairedSessions.ArmResult base = arm("base", 0, 100, -300, 200, 400, -100, 0, 250, -150);
    final PairedSessions.ArmResult shifted = arm("shifted", 0, 150, -260, 230, 470, -80, 40, 270, -100);
    final PairedSessions.Pilot pilot = PairedSessions.pilot(base, shifted, 10.0);
    assertEquals(8, pilot.sessions());
    assertEquals(Math.sqrt(Stats.variance(base.pnlUsd())), pilot.sessionSd(), 1e-9);
    assertEquals(Math.sqrt(Stats.variance(Stats.pairedDifferences(shifted.pnlUsd(), base.pnlUsd()))), pilot.differenceSd(), 1e-9);
    assertTrue(pilot.correlation() > 0.99 && pilot.correlation() <= 1.0);
    assertEquals(10.0, pilot.smallestEffectUsd(), 0.0);
    assertEquals(PairedSessions.impliedSessions(pilot.differenceSd(), 10.0), pilot.impliedSessions());
    assertTrue(Double.isNaN(PairedSessions.pilot(arm("flat", 0, 1, 1, 1), arm("flat2", 0, 2, 3, 5), 1.0).correlation()));
  }

  @Test
  void dataSetKeyNamesTheSessionsNotThePreRegistration() {
    final String pilot = PairedSessions.dataSetKey(SessionSeeds.PILOT, 0, 200);
    assertEquals(pilot, PairedSessions.dataSetKey(SessionSeeds.PILOT, 0, 200));
    assertNotEquals(pilot, PairedSessions.dataSetKey(SessionSeeds.PILOT, 0, 199));
    assertNotEquals(pilot, PairedSessions.dataSetKey(SessionSeeds.EXPLORATION, 0, 200));
    assertNotEquals(pilot, PairedSessions.dataSetKey(SessionSeeds.PILOT, 1, 200));
    assertTrue(pilot.startsWith("sessions:"));
    assertEquals(PairedSessions.labelDate(29_000_000L).plusDays(5), PairedSessions.labelDate(29_000_005L));
    // the label of a session does not depend on its base, its key does
    assertEquals(PairedSessions.labelDate(30_000_005L), PairedSessions.labelDate(29_000_005L));
  }
}
