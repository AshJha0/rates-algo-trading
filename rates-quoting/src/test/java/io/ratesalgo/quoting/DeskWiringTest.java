package io.ratesalgo.quoting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.hedge.ExposureBasisAttribution;
import org.junit.jupiter.api.Test;

/** PNL_AUDIT.md item 16: the desk wires a basis attribution per currency into its hedgers. */
class DeskWiringTest {

  @Test
  void eachCurrencyHasItsOwnBasisAttributionFedByTheRiskEngine() {
    final TestMarket m = new TestMarket();
    final ExposureBasisAttribution usd = m.desk.basisAttribution(CurrencyCode.USD);
    assertNotSame(usd, m.desk.basisAttribution(CurrencyCode.EUR));
    assertEquals(0L, usd.fills());
    final long px = m.fair.fairMid(TestMarket.TY);
    m.desk.risk().onFill(TestMarket.UST10, 100 * 100_000_000L, m.fair.fairMid(TestMarket.UST10));
    m.desk.risk().onFill(TestMarket.TY, -100 * 10_000_000L, px);
    m.desk.hedger(CurrencyCode.USD).analytics().onFill(
        m.directory.byId(TestMarket.TY), -100 * 10_000_000L, px, px, 1.0,
        m.desk.risk().unitParallelPv01(TestMarket.TY));
    assertEquals(1L, usd.fills(), "the hedger's analytics forward fills to the desk's attribution");
    assertEquals(m.desk.risk().basisExposure(CurrencyCode.USD), usd.basisExposure(), 0.0);
    assertEquals(Math.abs(usd.basisExposure()), usd.basisAdded(), 1e-6, "all of the basis came from the hedge");
    assertEquals(0.0, usd.slippage(io.ratesalgo.api.ProductType.FUTURE), Decimal9.toDouble(1L));
  }
}
