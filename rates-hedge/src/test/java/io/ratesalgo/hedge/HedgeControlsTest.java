package io.ratesalgo.hedge;

import static io.ratesalgo.hedge.HedgeFixture.B10;
import static io.ratesalgo.hedge.HedgeFixture.CONTRACT;
import static io.ratesalgo.hedge.HedgeFixture.M;
import static io.ratesalgo.hedge.HedgeFixture.RX;
import static io.ratesalgo.hedge.HedgeFixture.S10;
import static io.ratesalgo.hedge.HedgeFixture.TY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.ProductType;
import io.ratesalgo.api.RecordingHedgeOrderSink;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.risk.KillState;
import io.ratesalgo.risk.RiskLimits;
import org.junit.jupiter.api.Test;

/** PNL_AUDIT.md items 14 and 16 on the hedge side. */
class HedgeControlsTest {

  /**
   * The daily-loss kill no longer cancels the hedges: the parents keep working, later timers send
   * more risk-reducing tranches, and the book ends flatter than it was at the kill.
   */
  @Test
  void theDailyLossKillKeepsFlatteningTheBook() {
    final HedgeFixture f = new HedgeFixture(new RiskLimits().dailyLoss(50_000.0));
    final Hedger h = f.hedger(Hedger.config().firstTranche(0.5).triggerStdDev(1_000.0));
    f.risk.startOfDay();
    f.risk.onFill(B10, 100 * M, f.fair.fairMid(B10)); // -82k at 10Y
    assertTrue(h.onTimer(f.clock.epochNanos()) > 0);
    final int parents = h.workingParents();
    // the 10Y bond falls a point: about -1mm, past the 50k daily loss
    f.fair.set(B10, Decimal9.round(97.25), f.clock.epochNanos());
    f.risk.onFairValue(B10, Decimal9.round(97.25));
    assertEquals(KillState.KILLED, f.kill.state());
    assertTrue(f.risk.flattenOnly());
    assertEquals(1L, h.flattenKills());
    assertEquals(parents, h.workingParents(), "no parent cancelled");
    assertEquals(0, f.sink.cancelAllCount());
    final double atKill = Math.abs(f.risk.netPv01(CurrencyCode.USD));
    fillAll(f, h);
    f.clock.advance(30_000_000_000L);
    final int before = f.sink.parents().size();
    assertTrue(h.onTimer(f.clock.epochNanos()) > 0, "still hedging while killed");
    for (int i = before; i < f.sink.parents().size(); i++) {
      final RecordingHedgeOrderSink.Parent p = f.sink.parents().get(i);
      final double sign = p.side() == io.ratesalgo.messages.sbe.Side.Buy ? 1.0 : -1.0;
      assertTrue(sign * f.risk.unitParallelPv01(p.instrumentId()) > 0.0, "every new parent adds PV01 to a short book");
    }
    fillAll(f, h);
    assertTrue(Math.abs(f.risk.netPv01(CurrencyCode.USD)) < 0.5 * atKill);
  }

  @Test
  void anyOtherKillStillCancelsTheHedges() {
    final HedgeFixture f = new HedgeFixture(new RiskLimits().dailyLoss(50_000.0));
    final Hedger h = f.hedger(Hedger.config().triggerStdDev(1_000.0));
    f.risk.onFill(B10, 100 * M, f.fair.fairMid(B10));
    assertTrue(h.onTimer(f.clock.epochNanos()) > 0);
    f.kill.trigger(KillReason.Manual, 0, 1);
    assertEquals(0, h.workingParents());
    assertEquals(0L, h.flattenKills());
    assertEquals(0, h.onTimer(f.clock.epochNanos()));
  }

  /**
   * A 10Y receiver swap hedged in the 10Y future: rate risk falls, swap-spread risk rises by the
   * PV01 the future took on. A hedge of bonds in the future moves basis the same way.
   */
  @Test
  void attributionMeasuresTheSpreadRiskAHedgeOpens() {
    final HedgeFixture f = new HedgeFixture();
    final ExposureBasisAttribution attr = new ExposureBasisAttribution(f.risk, CurrencyCode.USD);
    f.risk.onFill(S10, 100 * M, f.fair.fairMid(S10)); // swaps +82k
    final long arrival = f.fair.fairMid(TY);
    final long px = arrival + Decimal9.round(1.0 / 64);
    f.risk.onFill(TY, 1_000 * CONTRACT, px); // futures -80k
    attr.onHedgeFill(TY, 1_000 * CONTRACT, px, arrival);
    assertEquals(80_000.0, attr.swapSpreadAdded(), 1e-6);
    assertEquals(80_000.0, attr.swapSpreadExposure(), 1e-6);
    assertEquals(0.0, attr.basisAdded(), 1e-9, "no bonds, no futures-versus-cash basis");
    assertEquals(1_000 * 100_000 / 100.0 / 64, attr.slippage(ProductType.FUTURE), 1e-6, "1/64 on 100mm face");
    assertEquals(0.0, attr.slippage(ProductType.SWAP));
    // unwinding half the futures hedge removes half the spread risk it opened
    f.risk.onFill(TY, -500 * CONTRACT, arrival);
    attr.onHedgeFill(TY, -500 * CONTRACT, arrival, arrival);
    assertEquals(40_000.0, attr.swapSpreadAdded(), 1e-6);
    // bonds against the remaining futures: basis
    f.risk.onFill(B10, -50 * M, f.fair.fairMid(B10)); // bonds +41k
    attr.onHedgeFill(B10, -50 * M, f.fair.fairMid(B10), f.fair.fairMid(B10));
    assertEquals(-40_000.0, attr.basisExposure(), 1e-6);
    assertEquals(40_000.0, attr.basisAdded(), 1e-6);
    assertEquals(3L, attr.fills());
    attr.onHedgeFill(RX, CONTRACT, arrival, arrival);
    attr.onHedgeFill(999, CONTRACT, arrival, arrival);
    assertEquals(3L, attr.fills(), "other currencies and unknown instruments are ignored");
  }

  private static void fillAll(final HedgeFixture f, final Hedger h) {
    for (int p = h.workingParents() - 1; p >= 0; p--) {
      final long id = h.workingParentId(p);
      int instrument = 0;
      for (final RecordingHedgeOrderSink.Parent par : f.sink.parents()) {
        if (par.parentId() == id) {
          instrument = par.instrumentId();
        }
      }
      h.onHedgeFill(id, f.fair.fairMid(instrument), Math.abs(h.remaining(id)));
    }
  }
}
