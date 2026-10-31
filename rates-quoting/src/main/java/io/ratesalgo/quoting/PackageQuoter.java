package io.ratesalgo.quoting;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.InstrumentDirectory;
import io.ratesalgo.api.KeyRateTenors;
import io.ratesalgo.hedge.KeyRateCovariance;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.risk.KillSwitchListener;
import io.ratesalgo.risk.RiskEngine;
import java.util.Arrays;
import org.agrona.collections.Long2LongHashMap;

/**
 * Prices a package or structured RFQ as one trade on its net risk (API_SPEC.md Section 12.7) and
 * holds it through its time to live.
 *
 * <p>Controls (PNL_AUDIT item 12): a package passes the same gates a single RFQ does, in the same
 * order: kill switch, input health, the release pull window, instrument and currency, the client
 * re-quote throttle, fair value with input age, and the risk engine's quote check on every leg.
 * A quoted package reserves every leg's quantity until it is answered or its time to live
 * passes, as a single quote does.
 *
 * <p>Pricing on net risk. Each leg's optimal half-spread {@code h_i} (bp) is found from the
 * hit-rate model against the client's adverse cost, then widened for events, releases and
 * volatility as in the engine. The package spread is the PV01-weighted mean {@code hbar} charged
 * on the package's risk-equivalent PV01
 *
 * <pre>
 *   R = sqrt(L' Sigma L) * sum_i P_i / sum_i sqrt(d_i' Sigma d_i)
 * </pre>
 *
 * with {@code L} the dealer's net ladder, {@code d_i} leg i's ladder and {@code P_i} its PV01: the
 * net ladder's one-day risk in PV01 units of the legs' own volatility. A DV01-neutral curve trade
 * is charged on its curve risk, not on the sum of its legs. Then
 *
 * <pre>
 *   charge = hbar R + sum_i hedgeCost(P_i) - saving + kappa (L' Sigma p + 0.5 L' Sigma L)
 *   saving = max(0, sum_i hedgeCost(P_i) - hedgeCost(|parallel(L)|))
 * </pre>
 *
 * floored at {@code minHalfSpreadBp * max_i P_i}; {@code p} is the book. {@link
 * Result#spreadChargeSum} is {@code hbar R + sum_i hedgeCost(P_i)} so that {@code charge =
 * spread - saving + inventory}. The legs priced one by one cost {@code sum_i ((h_i + hedge_i) P_i
 * + kappa (d_i' Sigma p + 0.5 d_i' Sigma d_i))}. The charge is spread over the legs as an equal bp
 * distance on each, in the client-worse direction.
 *
 * <p>There is no package message in SBE schema v1; P2's D2C venue carries package RFQs, and the
 * coordinator maps its legs to {@link PackageRequest} and the venue's answer to {@link
 * #onResponse}. The Desk registers nothing here yet: the kill switch reaches {@link #onKill} once
 * the wiring registers this quoter, and until then {@link #quote} reads the kill switch itself.
 */
public final class PackageQuoter implements KillSwitchListener {

  private static final int K = KeyRateTenors.COUNT;
  private static final int LEGS = 4;
  private static final int MAX_LIVE = 64;
  private static final byte FREE = 0;
  private static final byte LIVE = 1;
  private static final byte WITHDRAWN = 2;
  private static final byte EXPIRED = 3;

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
    private PassReason reason = PassReason.NONE;
    private long packageId;
    private long validUntilNanos;
    private final long[] legPrice = new long[LEGS];
    private double legChargeSum;
    private double spreadChargeSum;
    private double hedgeSaving;
    private double inventoryCharge;
    private double charge;
    private double distanceBp;
    private double netRiskPv01;
    private int rejection;
    private final Dv01Vector netLadder = new Dv01Vector(CurrencyCode.USD);

    public boolean quoted() {
      return quoted;
    }

    /** Why the package was not quoted, or NONE. */
    public PassReason reason() {
      return reason;
    }

    /** Id to answer with in {@link PackageQuoter#onResponse}; 0 when not quoted. */
    public long packageId() {
      return packageId;
    }

    /** End of the package's time to live, epoch nanoseconds. */
    public long validUntilNanos() {
      return validUntilNanos;
    }

    /** Decimal9 price of a leg, client-worse by the package distance. */
    public long legPrice(final int leg) {
      return legPrice[leg];
    }

    /** Sum of the legs' standalone charges (spread, hedge and inventory), currency. */
    public double legChargeSum() {
      return legChargeSum;
    }

    /** Spread on the net risk plus the legs' standalone hedge cost, currency. */
    public double spreadChargeSum() {
      return spreadChargeSum;
    }

    /** Hedge cost saved by netting, currency. */
    public double hedgeSaving() {
      return hedgeSaving;
    }

    /** Inventory charge on the net ladder, currency (negative when it reduces risk). */
    public double inventoryCharge() {
      return inventoryCharge;
    }

    /** Package charge, currency. */
    public double charge() {
      return charge;
    }

    /** Distance applied to every leg, bp. */
    public double distanceBp() {
      return distanceBp;
    }

    /** Risk-equivalent PV01 of the net ladder, currency per bp. */
    public double netRiskPv01() {
      return netRiskPv01;
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
  private final double[] fair = new double[LEGS];
  private final double[] l = new double[K];
  private final double[][] legLadder = new double[LEGS][K];
  private final double[] p = new double[K];
  private final Dv01Vector book = new Dv01Vector(CurrencyCode.USD);
  private final Long2LongHashMap lastQuote = new Long2LongHashMap(256, 0.5f, Long.MIN_VALUE);

  // live packages
  private final byte[] state = new byte[MAX_LIVE];
  private final long[] pId = new long[MAX_LIVE];
  private final int[] pLegs = new int[MAX_LIVE];
  private final int[][] pInstrument = new int[MAX_LIVE][LEGS];
  private final long[][] pDealerQty = new long[MAX_LIVE][LEGS];
  private final long[][] pPrice = new long[MAX_LIVE][LEGS];
  private final long[] pValidUntil = new long[MAX_LIVE];
  private long nextPackageId;
  private long packagesQuoted;
  private long packageFills;
  private long lateFills;
  private long expiries;
  private long unmatched;

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
    poll(now);
    out.quoted = false;
    out.reason = PassReason.NONE;
    out.packageId = 0L;
    out.rejection = 0;
    final QuotingConfig cfg = engine.config();
    if (!risk.killSwitch().isActive()) {
      return pass(out, PassReason.KILLED);
    }
    if (!engine.health().healthy(now)) {
      return pass(out, PassReason.UNHEALTHY);
    }
    if (cfg.inReleasePull(now)) {
      return pass(out, PassReason.RELEASE_WINDOW);
    }
    final Instrument first = directory.byId(pkg.instrumentId(0));
    if (first == null || pkg.legs() > LEGS) {
      return pass(out, PassReason.UNKNOWN_INSTRUMENT);
    }
    final long last = lastQuote.get(clientId);
    if (last != Long.MIN_VALUE && now - last < cfg.requoteThrottleNanos) {
      return pass(out, PassReason.THROTTLED);
    }
    if (freeSlot() < 0) {
      return pass(out, PassReason.BOOK_FULL);
    }
    Arrays.fill(l, 0.0);
    double spreadPv01 = 0.0;
    double standaloneHedge = 0.0;
    double totalPv01 = 0.0;
    double maxPv01 = 0.0;
    double legRisk = 0.0;
    final HitRateModel hr = engine.hitRate();
    final double session = QuotingEngine.liquidSession(first.currency(), now);
    for (int i = 0; i < pkg.legs(); i++) {
      final int id = pkg.instrumentId(i);
      final Instrument inst = directory.byId(id);
      if (inst == null || inst.currency() != first.currency() || pkg.clientQuantity(i) == 0L) {
        return pass(out, PassReason.UNKNOWN_INSTRUMENT);
      }
      if (!fairValue.compute(inst, now)) {
        return pass(out, PassReason.NO_FAIR_VALUE);
      }
      if (now - fairValue.oldestNanos() > cfg.maxInputAgeNanos()) {
        return pass(out, PassReason.STALE_INPUT);
      }
      fair[i] = fairValue.fair();
      final double qMajor = inst.major(pkg.clientQuantity(i));
      final double legPv01 = Math.abs(risk.unitParallelPv01(id) * qMajor);
      final double hedgeBp = engine.hedgeCostBp(inst, legPv01);
      final double base =
          hr.base(Math.abs((double) pkg.clientQuantity(i)) / inst.typicalQuantity(), inst.tenorYears(), session, dealers);
      final double h =
          clamp(
              engine.widen(engine.spread().optimal(base, engine.toxicity().adverseBp(clientId), cfg.maxHalfSpreadBp),
                  first.currency(), now),
              cfg.minHalfSpreadBp, cfg.maxHalfSpreadBp);
      spreadPv01 += h * legPv01;
      standaloneHedge += hedgeBp * legPv01;
      totalPv01 += legPv01;
      maxPv01 = Math.max(maxPv01, legPv01);
      for (int k = 0; k < K; k++) {
        legLadder[i][k] = -risk.unitPv01(id, k) * qMajor;
        l[k] += legLadder[i][k];
      }
      legRisk += Math.sqrt(Math.max(0.0, covariance.quadratic(legLadder[i], legLadder[i])));
    }
    double netPar = 0.0;
    for (int k = 0; k < K; k++) {
      netPar += l[k];
    }
    final double netRisk =
        legRisk > 0.0 ? Math.sqrt(Math.max(0.0, covariance.quadratic(l, l))) * totalPv01 / legRisk : 0.0;
    final double hbar = totalPv01 > 0.0 ? spreadPv01 / totalPv01 : 0.0;
    final double netHedge = engine.hedgeCostBp(first, Math.abs(netPar)) * Math.abs(netPar);
    final double saving = Math.max(0.0, standaloneHedge - netHedge);
    risk.ladder(first.currency(), book);
    book.copyTo(p);
    final double inventory = cfg.kappa() * (covariance.quadratic(l, p) + 0.5 * covariance.quadratic(l, l));
    double standaloneInventory = 0.0;
    for (int i = 0; i < pkg.legs(); i++) {
      standaloneInventory +=
          cfg.kappa() * (covariance.quadratic(legLadder[i], p) + 0.5 * covariance.quadratic(legLadder[i], legLadder[i]));
    }
    final double spreadCharge = hbar * netRisk + standaloneHedge;
    final double charge = Math.max(cfg.minHalfSpreadBp * maxPv01, spreadCharge - saving + inventory);
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
        return pass(out, PassReason.RISK_REJECT);
      }
      out.legPrice[i] = px;
    }
    // hold: reserve every leg for the time to live
    final int s = freeSlot();
    final long id = ++nextPackageId;
    state[s] = LIVE;
    pId[s] = id;
    pLegs[s] = pkg.legs();
    pValidUntil[s] = now + cfg.firmTtlNanos();
    for (int i = 0; i < pkg.legs(); i++) {
      final long dealerQty = -pkg.clientQuantity(i);
      pInstrument[s][i] = pkg.instrumentId(i);
      pDealerQty[s][i] = dealerQty;
      pPrice[s][i] = out.legPrice[i];
      risk.reserve(pkg.instrumentId(i), dealerQty > 0 ? dealerQty : 0L, dealerQty > 0 ? 0L : -dealerQty);
    }
    lastQuote.put(clientId, now);
    packagesQuoted++;
    out.netLadder.currency(first.currency());
    for (int k = 0; k < K; k++) {
      out.netLadder.set(k, l[k]);
    }
    out.legChargeSum = spreadPv01 + standaloneHedge + standaloneInventory;
    out.spreadChargeSum = spreadCharge;
    out.hedgeSaving = saving;
    out.inventoryCharge = inventory;
    out.charge = charge;
    out.distanceBp = distance;
    out.netRiskPv01 = netRisk;
    out.packageId = id;
    out.validUntilNanos = pValidUntil[s];
    out.quoted = true;
    return out;
  }

  /**
   * The venue's answer for a package. Done books every leg to risk at the quoted leg prices, so
   * the hedger sees the net ladder; a Done for a package that expired or was withdrawn here is
   * still booked once (the venue may have accepted it before our expiry reached it). Any answer
   * releases the reservation of a package still held. Returns false for an unknown id.
   */
  public boolean onResponse(final long packageId, final boolean done) {
    for (int s = 0; s < MAX_LIVE; s++) {
      if (state[s] == FREE || pId[s] != packageId) {
        continue;
      }
      if (state[s] != EXPIRED) {
        release(s);
      } else if (done) {
        lateFills++;
      }
      if (done) {
        packageFills++;
        for (int i = 0; i < pLegs[s]; i++) {
          risk.onFill(pInstrument[s][i], pDealerQty[s][i], pPrice[s][i]);
        }
      }
      state[s] = FREE;
      return true;
    }
    unmatched++;
    return false;
  }

  /** Expires packages whose time to live has passed; their slot is kept for a late Done. */
  public void poll(final long nowNanos) {
    for (int s = 0; s < MAX_LIVE; s++) {
      if ((state[s] == LIVE || state[s] == WITHDRAWN) && pValidUntil[s] <= nowNanos) {
        release(s);
        state[s] = EXPIRED;
        expiries++;
      }
    }
  }

  /**
   * Withdraws every live package. There is no package withdraw message in schema v1, so the
   * reservation is held until the venue answers or the time to live passes, as for a single quote
   * being withdrawn.
   */
  @Override
  public void onKill(final KillReason reason, final long epochNanos) {
    for (int s = 0; s < MAX_LIVE; s++) {
      if (state[s] == LIVE) {
        state[s] = WITHDRAWN;
      }
    }
  }

  /** Packages quoted and held (live or withdrawn, not yet answered or expired). */
  public int heldPackages() {
    int n = 0;
    for (int s = 0; s < MAX_LIVE; s++) {
      if (state[s] == LIVE || state[s] == WITHDRAWN) {
        n++;
      }
    }
    return n;
  }

  public long packagesQuoted() {
    return packagesQuoted;
  }

  public long packageFills() {
    return packageFills;
  }

  /** Done answers booked for packages that had already expired here. */
  public long lateFills() {
    return lateFills;
  }

  public long expiries() {
    return expiries;
  }

  /** Answers for packages no longer remembered. */
  public long unmatchedResponses() {
    return unmatched;
  }

  private int freeSlot() {
    int expired = -1;
    long oldest = Long.MAX_VALUE;
    for (int s = 0; s < MAX_LIVE; s++) {
      if (state[s] == FREE) {
        return s;
      }
      if (state[s] == EXPIRED && pId[s] < oldest) {
        oldest = pId[s];
        expired = s;
      }
    }
    return expired;
  }

  private void release(final int s) {
    for (int i = 0; i < pLegs[s]; i++) {
      final long q = pDealerQty[s][i];
      risk.release(pInstrument[s][i], q > 0 ? q : 0L, q > 0 ? 0L : -q);
    }
  }

  private static Result pass(final Result out, final PassReason reason) {
    out.reason = reason;
    return out;
  }

  private static double clamp(final double v, final double lo, final double hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }
}
