package io.ratesalgo.hedge;

import static io.ratesalgo.hedge.HedgeFixture.B10;
import static io.ratesalgo.hedge.HedgeFixture.B2;
import static io.ratesalgo.hedge.HedgeFixture.M;
import static io.ratesalgo.hedge.HedgeFixture.S10;
import static io.ratesalgo.hedge.HedgeFixture.TY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.ProductType;
import org.junit.jupiter.api.Test;

/** Review 2026-11-23, Q5: hedge cost from each instrument's own tick, not a flat 40 USD a lot. */
class TickHedgeCostModelTest {

  private static final long TICK_128 = 7_812_500L;

  private final HedgeFixture f = new HedgeFixture();
  private final HedgeUniverse u = new HedgeUniverse().add(B2, M, 100).add(B10, M, 100).add(TY, HedgeFixture.CONTRACT, 100);
  private final VenueTicks ticks = inst -> inst.type() == ProductType.BOND ? TICK_128 : 0L;

  @Test
  void halfATickOnOneMillionFaceIsThirtyNineDollars() {
    final TickHedgeCostModel c = new TickHedgeCostModel(f.directory, u, ticks, 0.5, 8);
    assertEquals(78.125, c.tickValuePerLot(B10), 1e-9);
    assertEquals(39.0625, c.linearPerLot(B2), 1e-9);
    assertEquals(39.0625, c.linearPerLot(B10), 1e-9);
    assertEquals(0.0, c.impactPerLot2(B10));
    // per DV01 the same tick costs the 2-year four times what it costs the 10-year
    final double perDv01Two = c.linearPerLot(B2) / (1.9e-4 * 1e6);
    final double perDv01Ten = c.linearPerLot(B10) / (8.2e-4 * 1e6);
    assertEquals(0.206, perDv01Two, 0.001);
    assertEquals(0.048, perDv01Ten, 0.001);
  }

  @Test
  void unknownTickLotOrInstrumentIsNeverTraded() {
    final TickHedgeCostModel c = new TickHedgeCostModel(f.directory, u, ticks, 0.5, 8);
    assertEquals(Double.POSITIVE_INFINITY, c.linearPerLot(TY), "no tick for the future");
    assertEquals(Double.POSITIVE_INFINITY, c.linearPerLot(S10), "not in the universe");
    assertEquals(Double.POSITIVE_INFINITY, c.linearPerLot(999), "unknown instrument");
    assertTrue(Double.isNaN(c.tickValuePerLot(999)));
  }

  @Test
  void perInstrumentSettingsOverrideTheDefault() {
    final TickHedgeCostModel c = new TickHedgeCostModel(f.directory, u, ticks, 0.5, 2).set(B10, 1.0, 5.0, 0.25);
    assertEquals(78.125 + 5.0, c.linearPerLot(B10), 1e-9);
    assertEquals(0.25, c.impactPerLot2(B10));
    assertEquals(2.0 * (78.125 + 5.0) + 0.25 * 4.0, c.cost(B10, -2.0), 1e-9);
    c.set(B10, 0.5, 0.0, 0.0).set(B2, 0.5, 0.0, 0.0);
    assertThrows(IllegalStateException.class, () -> c.set(TY, 0.5, 0.0, 0.0));
    assertThrows(IllegalArgumentException.class, () -> c.set(B10, -0.5, 0.0, 0.0));
    assertThrows(IllegalArgumentException.class, () -> c.set(B10, 0.5, -1.0, 0.0));
    assertThrows(IllegalArgumentException.class, () -> c.set(B10, 0.5, 0.0, -1.0));
    assertThrows(IllegalArgumentException.class, () -> new TickHedgeCostModel(f.directory, u, ticks, -1.0, 2));
  }

  @Test
  void theQuadraticImpactMatchesTheSquareRootLawAtTheReferenceSize() {
    // SIM_V2_DESIGN.md Section 8: 50mm of the 10-year against 40bn a day, Y 0.7, sd 0.43 points
    final double lots = 50.0;
    final double volume = 40_000.0;
    final double value = 1e6 * 0.01; // one point on one 1mm lot
    final double b = TickHedgeCostModel.squareRootImpactPerLot2(0.7, 0.43, volume, lots, value);
    final double peak = 0.7 * 0.43 * Math.sqrt(lots / volume);
    assertEquals(0.0106, peak, 0.0001);
    assertEquals((2.0 / 3.0) * peak * lots * value, b * lots * lots, 1e-9);
    assertTrue(b * 4.0 * lots * lots > (2.0 / 3.0) * 0.7 * 0.43 * Math.sqrt(2.0 * lots / volume) * 2.0 * lots * value,
        "above the reference the quadratic overstates");
    assertThrows(IllegalArgumentException.class, () -> TickHedgeCostModel.squareRootImpactPerLot2(0.7, 0.43, 0.0, lots, value));
    assertThrows(IllegalArgumentException.class, () -> TickHedgeCostModel.squareRootImpactPerLot2(-0.1, 0.43, 1.0, lots, value));
    assertThrows(IllegalArgumentException.class, () -> TickHedgeCostModel.squareRootImpactPerLot2(0.7, -0.4, 1.0, lots, value));
    assertThrows(IllegalArgumentException.class, () -> TickHedgeCostModel.squareRootImpactPerLot2(0.7, 0.4, 1.0, 0.0, value));
    assertThrows(IllegalArgumentException.class, () -> TickHedgeCostModel.squareRootImpactPerLot2(0.7, 0.4, 1.0, 1.0, -1.0));
    assertEquals(0L, VenueTicks.NONE.tick(f.directory.byId(B10)));
    assertTrue(HedgeAvailability.ALL.available(B10));
  }
}
