package io.ratesalgo.cookbook;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.InMemoryBookSource;
import io.ratesalgo.api.InMemoryFairValueSource;
import io.ratesalgo.api.InMemoryInstrumentDirectory;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.KeyRateTenors;
import io.ratesalgo.api.ParametricSensitivityProvider;
import io.ratesalgo.api.RecordingHedgeOrderSink;
import io.ratesalgo.api.RecordingOutboundSink;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.hedge.HedgeUniverse;
import io.ratesalgo.hedge.Hedger;
import io.ratesalgo.hedge.TableHedgeCostModel;
import io.ratesalgo.quoting.ClientTier;
import io.ratesalgo.quoting.ClobQuoter;
import io.ratesalgo.quoting.Desk;
import io.ratesalgo.quoting.HitRatePreset;
import io.ratesalgo.quoting.PackageQuoter;
import io.ratesalgo.quoting.QuotingConfig;
import io.ratesalgo.quoting.TierTable;
import io.ratesalgo.risk.RiskLimits;
import java.io.IOException;
import java.util.Locale;

/** COOKBOOK.md recipe 27: price a curve and a butterfly RFQ as one package, with the net ladder. */
public final class Recipe27PackageRfq {

  private Recipe27PackageRfq() {}

  static final long FACE_1M = 100_000_000L;
  static final int SW2 = 301;
  static final int SW5 = 302;
  static final int SW10 = 303;
  static final int SW30 = 304;

  public static void main(final String[] args) throws IOException {
    final ManualClock clock = new ManualClock(1_767_268_800_000_000_000L); // 2026-01-01T12:00Z
    final InMemoryInstrumentDirectory dir = new InMemoryInstrumentDirectory();
    final InMemoryFairValueSource fair = new InMemoryFairValueSource();
    // USD SOFR swaps quoted in rate percent, each with its futures hedge and a duration
    final int[] futures = {201, 202, 203, 204};
    final String[] futureNames = {"TU", "FV", "TY", "US"};
    final double[] futureDuration = {1.9, 4.3, 6.5, 13.0};
    final double[] futurePrice = {103.20, 108.40, 111.10, 117.30};
    final int[] swaps = {SW2, SW5, SW10, SW30};
    final double[] tenor = {2.0, 5.0, 10.0, 30.0};
    final double[] swapDuration = {1.9, 4.5, 8.2, 17.0};
    final double[] swapRate = {3.80, 3.70, 3.85, 3.95};
    for (int i = 0; i < 4; i++) {
      dir.add(Instrument.future(futures[i], futureNames[i], CurrencyCode.USD, tenor[i] * 0.7, 50 * FACE_1M));
      dir.add(Instrument.swap(swaps[i], "SOFR" + (int) tenor[i], CurrencyCode.USD, tenor[i], swapDuration[i],
          futures[i], 0.9, 50 * FACE_1M));
    }
    final ParametricSensitivityProvider pv01 = new ParametricSensitivityProvider(dir);
    final HedgeUniverse universe = new HedgeUniverse();
    final TableHedgeCostModel costs = new TableHedgeCostModel(16);
    final long now = clock.epochNanos();
    for (int i = 0; i < 4; i++) {
      pv01.defineAtTenor(futures[i], -futureDuration[i] * 1e-4, 0.002);
      pv01.defineAtTenor(swaps[i], swapDuration[i] * 1e-4, 0.002);
      fair.set(futures[i], Decimal9.round(futurePrice[i]), now);
      fair.set(swaps[i], Decimal9.round(swapRate[i]), now);
      universe.add(futures[i], 10_000_000L, 5_000).add(swaps[i], FACE_1M, 2_000);
      costs.set(futures[i], 4.0 * (i + 1), 1.0, 0.002).set(swaps[i], swapDuration[i] * 10, 0.0, 0.01 * (i + 1));
    }
    final Desk desk = new Desk(new Desk.Inputs(dir, pv01, fair, new InMemoryBookSource(16),
        new RiskLimits().bucketPv01(250_000.0).netPv01(400_000.0).grossPv01(1_500_000.0).priceCollarBp(15.0),
        universe, costs, new RecordingOutboundSink(), new RecordingOutboundSink(), new RecordingHedgeOrderSink(),
        clock, new QuotingConfig(), TierTable.single(1, new ClientTier(1, 1.0, 500 * FACE_1M, true, false)),
        HitRatePreset.shippedDefault(), Hedger.config(), ClobQuoter.config()));
    final PackageQuoter quoter = desk.packages();

    // 2s10s steepener: the client pays fixed on 100m 2y and receives on a DV01-matched 10y
    show(quoter, quoter.neutralPair(PackageQuoter.Type.CURVE, SW2, 100 * FACE_1M, SW10), dir);
    // 2s5s10s butterfly: the client receives on 50m 5y, each wing carries half the body's DV01
    show(quoter, quoter.butterfly(SW2, SW5, -50 * FACE_1M, SW10), dir);
  }

  private static void show(final PackageQuoter quoter, final PackageQuoter.PackageRequest pkg,
      final InMemoryInstrumentDirectory dir) {
    final PackageQuoter.Result r = quoter.quote(pkg, 1, 4, new PackageQuoter.Result());
    System.out.println(pkg.type() + " quoted " + r.quoted());
    for (int i = 0; i < pkg.legs(); i++) {
      final long q = pkg.clientQuantity(i);
      System.out.println(String.format(Locale.ROOT, "  %-7s client %-7s %6.1fm at %.6f%%",
          dir.byId(pkg.instrumentId(i)).symbol(), q > 0 ? "pays" : "receives", Math.abs(q) / 1e8,
          Decimal9.toDouble(r.legPrice(i))));
    }
    System.out.println(String.format(Locale.ROOT, "  legs one by one %.0f USD: spread %.0f + inventory %.0f",
        r.legChargeSum(), r.spreadChargeSum(), r.legChargeSum() - r.spreadChargeSum()));
    System.out.println(String.format(Locale.ROOT,
        "  package %.0f USD: spread %.0f - hedge saving %.0f + inventory on the net ladder %.0f",
        r.charge(), r.spreadChargeSum(), r.hedgeSaving(), r.inventoryCharge()));
    System.out.println(String.format(Locale.ROOT, "  distance %.4f bp on every leg", r.distanceBp()));
    final Dv01Vector net = new Dv01Vector(CurrencyCode.USD);
    r.netLadder(net);
    final StringBuilder ladder = new StringBuilder("  dealer net DV01");
    for (int k = 0; k < KeyRateTenors.COUNT; k++) {
      ladder.append(String.format(Locale.ROOT, " %s %.0f", KeyRateTenors.label(k), net.get(k) + 0.0));
    }
    System.out.println(ladder);
    System.out.println(String.format(Locale.ROOT, "  parallel %.0f, gross %.0f USD per bp", net.parallel() + 0.0,
        net.gross()));
  }
}
