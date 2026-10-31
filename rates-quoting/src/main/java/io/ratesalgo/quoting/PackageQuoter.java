package io.ratesalgo.quoting;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.InstrumentDirectory;
import io.ratesalgo.api.KeyRateTenors;
import io.ratesalgo.hedge.KeyRateCovariance;
import io.ratesalgo.risk.RiskEngine;
import java.util.Arrays;

/**
 * Prices a package or structured RFQ as one trade (API_SPEC.md Section 12.7).
 *
 * <p>Each leg is first priced alone: its optimal half-spread {@code h_i} (bp) on its PV01 {@code
 * P_i} costs the client {@code h_i * P_i}. The package then nets: the dealer's net PV01 ladder
 * {@code L} is hedged once instead of leg by leg, so the package charge is
 *
 * <pre>
 *   charge = sum_i h_i P_i - (sum_i hedgeCost(P_i) - hedgeCost(|parallel(L)|)) + kappa (L' Sigma p + 0.5 L' Sigma L)
 * </pre>
 *
 * floored at {@code minHalfSpreadBp * max_i P_i}. The bracket is the netted-hedge saving; the last
 * term is the inventory charge on the net ladder against the book {@code p}. The charge is spread
 * over the legs as an equal bp distance on each, in the client-worse direction. Every leg passes
 * the risk engine's quote check one-sided; the package is passed if any leg fails.
 *
 * <p>There is no package message in SBE schema v1; P2's D2C venue carries package RFQs, and the
 * coordinator maps its legs to {@link PackageRequest} (deferred, see the P3 report).
 */
public final class PackageQuoter {

  private static final int K = KeyRateTenors.COUNT;

  /** Structured package types. */
  public enum Type {
    /** Two swaps or bonds at different tenors, DV01-neutral (for example 2s10s). */
    CURVE,
    /** Body against two wings, each wing carrying half the body's DV01. */
    BUTTERFLY,
    /** A bond against a swap, DV01-neutral (asset-swap spread, spread-over). */
    SPREAD_OVER,
    /** A bond future against a swap, DV01-neutral. */
    INVOICE_SPREAD,
    /** Front future against back future, equal and opposite contracts. */
    IMM_ROLL,
    /** One bond against another, DV01-neutral. */
    SWITCH
  }

  /** A package request: up to four legs, quantities signed from the client's side. */
  public static final class PackageRequest {
    private final Type type;
    private final int[] ids;
    private final long[] clientQuantity;

    private PackageRequest(final Type type, final int[] ids, final long[] clientQuantity) {
      this.type = type;
      this.ids = ids.clone();
      this.clientQuantity = clientQuantity.clone();
    }

    public Type type() {
      return type;
    }

    public int legs() {
      return ids.length;
    }

    public int instrumentId(final int leg) {
      return ids[leg];
    }

    /** Signed leg quantity from the client's side: positive is the client buying. */
    public long clientQuantity(final int leg) {
      return clientQuantity[leg];
    }
  }

  /** Result of pricing a package; reused. */
  public static final class Result {
    private boolean quoted;
    private final long[] legPrice = new long[4];
    private double legChargeSum;
    private double hedgeSaving;
    private double inventoryCharge;
    private double charge;
    private double distanceBp;
    private int rejection;
    private final Dv01Vector netLadder = new Dv01Vector(CurrencyCode.USD);

    public boolean quoted() {
      return quoted;
    }

    /** Decimal9 price of a leg, client-worse by the package distance. */
    public long legPrice(final int leg) {
      return legPrice[leg];
    }

    /** Sum of the legs' standalone charges, currency. */
    public double legChargeSum() {
      return legChargeSum;
    }

    /** Hedge cost saved by netting, currency. */
    public double hedgeSaving() {
      return hedgeSaving;
    }

    /** Inventory charge on the net ladder, currency (negative when it reduces risk). */
    public double inventoryCharge() {
      return inventoryCharge;
    }

    /** PackageRequest charge, currency. */
    public double charge() {
      return charge;
    }

    /** Distance applied to every leg, bp. */
    public double distanceBp() {
      return distanceBp;
    }

    /** Risk engine code when a leg failed its check, else 0. */
    public int rejection() {
      return rejection;
    }

    /** Copies the dealer's net PV01 ladder. */
    public void netLadder(final Dv01Vector out) {
      out.copyFrom(netLadder);
    }
  }

  private final InstrumentDirectory directory;
  private final RiskEngine risk;
  private final FairValueEngine fairValue;
  private final QuotingEngine engine;
  private final KeyRateCovariance covariance;
  private final double[] fair = new double[4];
  private final double[] l = new double[K];
  private final double[] p = new double[K];
  private final Dv01Vector book = new Dv01Vector(CurrencyCode.USD);

  public PackageQuoter(
      final InstrumentDirectory directory,
      final RiskEngine risk,
      final FairValueEngine fairValue,
      final QuotingEngine engine,
      final KeyRateCovariance covariance) {
    this.directory = directory;
    this.risk = risk;
    this.fairValue = fairValue;
    this.engine = engine;
    this.covariance = covariance;
  }

  /** A DV01-neutral pair: the client trades {@code quantity1} of the first leg. */
  public PackageRequest neutralPair(final Type type, final int id1, final long quantity1, final int id2) {
    final long q2 =
        type == Type.IMM_ROLL ? -quantity1 : neutral(quantity1, id1, id2, 1.0);
    return new PackageRequest(type, new int[] {id1, id2}, new long[] {quantity1, q2});
  }

  /** A butterfly: the client trades {@code bodyQuantity} of the body. */
  public PackageRequest butterfly(final int wing1, final int body, final long bodyQuantity, final int wing2) {
    return new PackageRequest(
        Type.BUTTERFLY,
        new int[] {wing1, body, wing2},
        new long[] {neutral(bodyQuantity, body, wing1, 0.5), bodyQuantity, neutral(bodyQuantity, body, wing2, 0.5)});
  }

  private long neutral(final long q1, final int id1, final int id2, final double share) {
    final Instrument i1 = directory.byId(id1);
    final Instrument i2 = directory.byId(id2);
    final double pv01 = risk.unitParallelPv01(id1) * i1.major(q1) * share;
    final double q2Major = -pv01 / risk.unitParallelPv01(id2);
    final long step = engine.config().minQuoteQuantity();
    final long q2 = Math.round(q2Major * i2.minorPerMajor() / step) * step;
    return q2;
  }

  /** Prices {@code pkg} for {@code clientId} against {@code dealers} competitors. */
  public Result quote(final PackageRequest pkg, final int clientId, final int dealers, final Result out) {
    final long now = engine.clock().epochNanos();
    out.quoted = false;
    out.rejection = 0;
    final QuotingConfig cfg = engine.config();
    final Instrument first = directory.byId(pkg.instrumentId(0));
    Arrays.fill(l, 0.0);
    double chargeSum = 0.0;
    double standaloneHedge = 0.0;
    double totalPv01 = 0.0;
    double maxPv01 = 0.0;
    for (int i = 0; i < pkg.legs(); i++) {
      final int id = pkg.instrumentId(i);
      final Instrument inst = directory.byId(id);
      if (inst == null || inst.currency() != first.currency() || !fairValue.compute(inst, now)
          || now - fairValue.oldestNanos() > cfg.maxInputAgeNanos()) {
        return out;
      }
      fair[i] = fairValue.fair();
      final double qMajor = inst.major(pkg.clientQuantity(i));
      final double legPv01 = Math.abs(risk.unitParallelPv01(id) * qMajor);
      final double hedgeBp = engine.hedgeCostBp(inst, legPv01);
      final HitRateModel hr = engine.hitRate();
      final double base =
          hr.base(Math.abs((double) pkg.clientQuantity(i)) / inst.typicalQuantity(), inst.tenorYears(), 1.0, dealers);
      final double cost = engine.toxicity().adverseBp(clientId) + hedgeBp;
      final double h = Math.max(cfg.minHalfSpreadBp, engine.spread().optimal(base, cost, cfg.maxHalfSpreadBp));
      chargeSum += h * legPv01;
      standaloneHedge += hedgeBp * legPv01;
      totalPv01 += legPv01;
      maxPv01 = Math.max(maxPv01, legPv01);
      for (int k = 0; k < K; k++) {
        l[k] -= risk.unitPv01(id, k) * qMajor;
      }
    }
    double netPar = 0.0;
    for (int k = 0; k < K; k++) {
      netPar += l[k];
    }
    final double netHedge = engine.hedgeCostBp(first, Math.abs(netPar)) * Math.abs(netPar);
    final double saving = Math.max(0.0, standaloneHedge - netHedge);
    risk.ladder(first.currency(), book);
    book.copyTo(p);
    final double inventory = cfg.kappa() * (covariance.quadratic(l, p) + 0.5 * covariance.quadratic(l, l));
    final double charge = Math.max(cfg.minHalfSpreadBp * maxPv01, chargeSum - saving + inventory);
    final double distance = totalPv01 > 0.0 ? charge / totalPv01 : 0.0;
    for (int i = 0; i < pkg.legs(); i++) {
      final int id = pkg.instrumentId(i);
      final double qpb = risk.quotePerBp(id);
      final boolean clientBuys = pkg.clientQuantity(i) > 0;
      final long px = clientBuys ? Decimal9.ceil(fair[i] + distance * qpb) : Decimal9.floor(fair[i] - distance * qpb);
      final long qty = Math.abs(pkg.clientQuantity(i));
      final int r =
          clientBuys ? risk.checkQuote(id, 0L, 0L, px, qty, 0L) : risk.checkQuote(id, px, qty, 0L, 0L, 0L);
      if (r != RiskEngine.OK) {
        out.rejection = r;
        return out;
      }
      out.legPrice[i] = px;
    }
    out.netLadder.currency(first.currency());
    for (int k = 0; k < K; k++) {
      out.netLadder.set(k, l[k]);
    }
    out.legChargeSum = chargeSum;
    out.hedgeSaving = saving;
    out.inventoryCharge = inventory;
    out.charge = charge;
    out.distanceBp = distance;
    out.quoted = true;
    return out;
  }
}
