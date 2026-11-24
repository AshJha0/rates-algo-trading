package io.ratesalgo.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The carry holder and the two additive interfaces, exercised through minimal sources. */
class CarryRollTest {

  @Test
  void theHolderIsSetReadAndCleared() {
    final CarryRoll c = new CarryRoll();
    assertEquals(0, c.horizonDays());
    assertEquals(0.0, c.breakEvenBp(), 0.0);
    c.set(30, 0.65, -0.20);
    assertEquals(30, c.horizonDays());
    assertEquals(0.65, c.carryBp(), 0.0);
    assertEquals(-0.20, c.rollDownBp(), 0.0);
    assertEquals(0.45, c.breakEvenBp(), 1e-15);
    c.clear();
    assertEquals(0, c.horizonDays());
    assertEquals(0.0, c.carryBp(), 0.0);
    assertEquals(0.0, c.rollDownBp(), 0.0);
  }

  @Test
  void aSourceFillsTheCallersHolderAndReportsUnknownInstruments() {
    final CarrySource source =
        (instrumentId, out) -> {
          if (instrumentId != 7) {
            out.clear();
            return false;
          }
          out.set(1, 0.02, 0.01);
          return true;
        };
    final CarryRoll out = new CarryRoll();
    assertTrue(source.carry(7, out));
    assertEquals(0.03, out.breakEvenBp(), 1e-15);
    assertFalse(source.carry(8, out));
    assertEquals(0.0, out.breakEvenBp(), 0.0);
  }

  @Test
  void aSpreadLadderUsesTheSameVectorAndSignAsTheRateLadder() {
    // a long bond: negative at its tenor on both ladders; a swap: zero on the spread ladder
    final SpreadSensitivityProvider spreads =
        (instrumentId, quantityMinor, out) -> {
          out.clear();
          out.currency(CurrencyCode.USD);
          if (instrumentId == 1) {
            out.set(5, -0.085 * quantityMinor / 100.0);
          }
          return instrumentId == 1 || instrumentId == 2;
        };
    final Dv01Vector v = new Dv01Vector(CurrencyCode.USD);
    assertTrue(spreads.spreadPv01(1, 1_000_000L, v));
    assertEquals(-850.0, v.parallel(), 1e-9);
    assertEquals("10Y", KeyRateTenors.label(5));
    assertTrue(spreads.spreadPv01(2, 1_000_000L, v));
    assertEquals(0.0, v.gross(), 0.0);
    assertFalse(spreads.spreadPv01(3, 1_000_000L, v));
  }
}
