package io.ratesalgo.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The round-2 cost controls (PNL_AUDIT.md items 15 and 18): adverse selection on a filled passive
 * child, per-instrument volatility and message cost. With the controls off the costs are exactly
 * the version 1 costs, which CostModelTest pins.
 */
class RouterControlsTest {

  private static final long MM = RouterPresets.ONE_MILLION;
  private static final long HORIZON = 30_000_000_000L;
  private static final int INSTRUMENT = 77;

  /** Venue 1 has an empty queue and a high trade rate, so passive is cheap there. */
  private static CostTable passiveTable() {
    final CostTable t = CostModelTest.table(2 * MM, HORIZON);
    t.instrumentId = INSTRUMENT;
    t.sameSideDepth[1] = 0;
    t.tradeRate[1] = Math.round(20.0 * MM * 1e9);
    return t;
  }

  @Test
  void controlsOffLeaveVersionOneCostsUnchanged() {
    final RouterParams v1 = RouterPresets.global();
    final RouterParams off = v1.withControls(1, 0.0, 0.0, RouterParams.Volatility.NONE);
    assertEquals(v1, off);
    final Allocator a = new Allocator();
    final CostTable t = passiveTable();
    for (int r = 0; r < 2; r++) {
      assertEquals(a.evaluate(t, r, 2 * MM, v1, RouterPresets.clob(r + 1)), a.evaluate(t, r, 2 * MM, off, RouterPresets.clob(r + 1)));
    }
  }

  @Test
  void aFilledPassiveChildPaysAnAdverseMoveAndAggressiveDoesNot() {
    final RouterParams v1 = RouterPresets.global();
    final RouterParams adverse = v1.withControls(2, 1.0, 0.0, RouterParams.Volatility.NONE);
    final Allocator a = new Allocator();
    final CostTable t = passiveTable();
    final VenueParams vp = RouterPresets.clob(2);
    final double passiveBefore = passive(a, t, v1, vp);
    final double aggressiveBefore = aggressive(a, t, v1, vp);
    final double passiveAfter = passive(a, t, adverse, vp);
    final double aggressiveAfter = aggressive(a, t, adverse, vp);
    assertEquals(aggressiveBefore, aggressiveAfter, 0.0, "aggressive children are not charged");
    final double bound = v1.sigmaPerSqrtSecond() * Math.sqrt(HORIZON / 1e9);
    assertTrue(passiveAfter > passiveBefore, "filled passive children pay the adverse term");
    assertTrue(passiveAfter - passiveBefore <= bound, "at most one sigma over the horizon");
    // the term is linear in its factor
    final double five = passive(a, t, v1.withControls(2, 5.0, 0.0, RouterParams.Volatility.NONE), vp);
    assertEquals(5.0 * (passiveAfter - passiveBefore), five - passiveBefore, 1e-12);
    // and grows with the queue wait: behind a queue the same factor costs more
    final VenueParams vp0 = RouterPresets.clob(1);
    final double queued = passive(a, t, adverse, vp0) - passive(a, t, v1, vp0);
    assertTrue(queued > passiveAfter - passiveBefore, "queued " + queued);
    assertFalse(queued > bound, "still at most one sigma over the horizon");
  }

  @Test
  void eachInstrumentUsesItsOwnVolatility() {
    final RouterParams vol =
        RouterPresets.global()
            .withControls(2, 0.0, 0.0, RouterParams.Volatility.of(new int[] {INSTRUMENT}, new double[] {1.0}));
    assertEquals(1.0, vol.sigmaDailyPoints(INSTRUMENT));
    assertEquals(0.5, vol.sigmaDailyPoints(INSTRUMENT + 1), "unlisted instruments use the global value");
    final VenueParams vp = RouterPresets.clob(1);
    final CostTable t = CostModelTest.table(10 * MM, 0);
    t.instrumentId = INSTRUMENT;
    final double hs = RouterPresets.TICK_64TH / 2e9;
    final double impact = 0.6 * 1.0 * Math.sqrt(10.0 * MM / vp.adv());
    final double latency = 1.0 * 1.0 / Math.sqrt(23_400.0) * Math.sqrt(250e-6);
    assertEquals(hs + impact + 0.0005 + latency, new Allocator().evaluate(t, 0, 10 * MM, vol, vp), 1e-12);
    t.instrumentId = INSTRUMENT + 1;
    assertNotEquals(hs + impact + 0.0005 + latency, new Allocator().evaluate(t, 0, 10 * MM, vol, vp));
  }

  @Test
  void messagesAreCostedAndAnUnfilledPassiveChildCostsACancelAndAReplacement() {
    final double c = 0.001;
    final RouterParams v1 = RouterPresets.global();
    final RouterParams msg = v1.withControls(2, 0.0, c, RouterParams.Volatility.NONE);
    final Allocator a = new Allocator();
    final CostTable t = passiveTable();
    final VenueParams vp = RouterPresets.clob(1);
    final long q = 2 * MM;
    final double perMessage = c * MM / q;
    assertEquals(perMessage, aggressive(a, t, msg, vp) - aggressive(a, t, v1, vp), 1e-15);
    a.evaluate(t, 0, q, v1, vp);
    final double extra = passive(a, t, msg, vp) - passive(a, t, v1, vp);
    // one new order always, plus a cancel and an aggressive order with probability 1 - P
    assertTrue(extra >= perMessage - 1e-15 && extra <= 3.0 * perMessage + 1e-15, "extra " + extra);
    assertTrue(extra > perMessage, "venue 0 has a queue, so it may not fill");
  }

  @Test
  void controlsPresetAndValidation() {
    final RouterParams p = RouterPresets.globalWithControls(RouterParams.Volatility.NONE);
    assertEquals(RouterPresets.CONTROLS_CONFIG_VERSION, p.configVersion());
    assertTrue(p.filledAdverseFactor() > 0.0 && p.messageCostPoints() > 0.0);
    assertEquals(p, p.withHorizons(p.scheduleHorizonNanos(), p.passiveHorizonMaxNanos()));
    final RouterParams v1 = RouterPresets.global();
    assertThrows(IllegalArgumentException.class, () -> v1.withControls(2, -0.1, 0.0, RouterParams.Volatility.NONE));
    assertThrows(IllegalArgumentException.class, () -> v1.withControls(2, 0.0, 2.0, RouterParams.Volatility.NONE));
    assertThrows(IllegalArgumentException.class, () -> v1.withControls(2, 0.0, 0.0, null));
    assertThrows(IllegalArgumentException.class, () -> RouterParams.Volatility.of(new int[] {1}, new double[0]));
    assertThrows(IllegalArgumentException.class, () -> RouterParams.Volatility.of(new int[] {1}, new double[] {0.0}));
    assertThrows(
        IllegalArgumentException.class, () -> RouterParams.Volatility.of(new int[] {1, 1}, new double[] {0.5, 0.6}));
    final RouterParams.Volatility v = RouterParams.Volatility.of(new int[] {3}, new double[] {0.4});
    assertEquals(v, RouterParams.Volatility.of(new int[] {3}, new double[] {0.4}));
    assertEquals(v.hashCode(), RouterParams.Volatility.of(new int[] {3}, new double[] {0.4}).hashCode());
    assertNotEquals(v, RouterParams.Volatility.NONE);
    assertEquals(1, v.size());
  }

  private static double passive(final Allocator a, final CostTable t, final RouterParams rp, final VenueParams vp) {
    final CostTable u = new CostTable();
    u.copyFrom(t);
    u.aggressiveAllowed[1] = false;
    u.aggressiveAllowed[0] = false;
    return a.evaluate(u, rowOf(vp), 2 * MM, rp, vp);
  }

  private static double aggressive(final Allocator a, final CostTable t, final RouterParams rp, final VenueParams vp) {
    final CostTable u = new CostTable();
    u.copyFrom(t);
    u.passiveAllowed[1] = false;
    u.passiveAllowed[0] = false;
    return a.evaluate(u, rowOf(vp), 2 * MM, rp, vp);
  }

  private static int rowOf(final VenueParams vp) {
    return vp.venueId() - 1;
  }
}
