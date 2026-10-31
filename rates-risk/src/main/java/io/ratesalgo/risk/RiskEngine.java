package io.ratesalgo.risk;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.FairValueSource;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.InstrumentDirectory;
import io.ratesalgo.api.KeyRateTenors;
import io.ratesalgo.api.OutboundSink;
import io.ratesalgo.api.ProductType;
import io.ratesalgo.api.SensitivityProvider;
import io.ratesalgo.common.time.Clock;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.LimitKind;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.RiskLimitBreachEncoder;
import io.ratesalgo.messages.sbe.Side;
import java.nio.ByteBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Real-time risk: positions, the key-rate PV01 ladder per currency, basis and curve exposures,
 * daily P&amp;L, pre-trade checks and post-trade alerts (API_SPEC.md Section 13).
 *
 * <p>State is incremental. A fill revalues one instrument's contribution; a curve move revalues
 * the instruments in that currency; neither touches the rest. {@link #fullLadder} recomputes from
 * positions alone so tests can assert the two agree.
 *
 * <p>Live quotes, resting orders and hedge parents reserve their quantity with {@link #reserve}.
 * Pre-trade checks use the worst case over every reservation filling or not: positions, every
 * pillar and net PV01 exactly, gross PV01 by the bound sum over pillars of the larger absolute
 * extreme. A check that fails sends a RiskLimitBreach to the alert sink and returns the limit kind;
 * the caller must not send the message.
 *
 * <p>Single-writer. No allocation after construction.
 */
public final class RiskEngine {

  /** Check passed. */
  public static final int OK = 0;

  /** Refused because the kill switch is not ACTIVE. No breach message is sent. */
  public static final int REJECT_KILLED = -1;

  /** Refused because the instrument, its sensitivities or its fair value are unknown. */
  public static final int REJECT_UNKNOWN = -2;

  private static final int K = KeyRateTenors.COUNT;
  private static final int C = CurrencyCode.COUNT;

  private final InstrumentDirectory directory;
  private final SensitivityProvider sensitivities;
  private final FairValueSource fairValues;
  private final RiskLimits limits;
  private final KillSwitchController killSwitch;
  private final OutboundSink alerts;
  private final Clock clock;

  private final long[] position;
  private final long[] pendingBuy;
  private final long[] pendingSell;
  private final double[][] contribution;
  private final double[][] unit;
  private final double[] unitParallel;
  private final boolean[] known;
  private final double[][] resHi;
  private final double[][] resLo;
  private final double[] resNetHi;
  private final double[] resNetLo;
  private final double[] mark;
  private final boolean[] marked;
  private final boolean[] positionAlerted;

  private final double[][] ladder = new double[C][K];
  private final double[][] resHiAgg = new double[C][K];
  private final double[][] resLoAgg = new double[C][K];
  private final double[] resNetHiAgg = new double[C];
  private final double[] resNetLoAgg = new double[C];
  private final double[][] productPv01 = new double[C][ProductType.COUNT];
  private final double[] cash = new double[C];
  private final double[] markValue = new double[C];
  private final double[] startOfDay = new double[C];
  private final boolean[][] postAlerted = new boolean[C][16];

  private final TokenBucket orderRate;
  private final TokenBucket quoteRate;
  private final TokenBucket messageRate;

  private final Dv01Vector scratch = new Dv01Vector(CurrencyCode.USD);
  private final double[] newHi = new double[K];
  private final double[] newLo = new double[K];

  private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final RiskLimitBreachEncoder breach = new RiskLimitBreachEncoder();

  private int lastScope;
  private double lastLimit;
  private double lastObserved;
  private long breachCount;
  private long preTradeRejects;

  /** An engine over every instrument in {@code directory}, with no positions. */
  public RiskEngine(
      final InstrumentDirectory directory,
      final SensitivityProvider sensitivities,
      final FairValueSource fairValues,
      final RiskLimits limits,
      final KillSwitchController killSwitch,
      final OutboundSink alerts,
      final Clock clock) {
    this.directory = directory;
    this.sensitivities = sensitivities;
    this.fairValues = fairValues;
    this.limits = limits;
    this.killSwitch = killSwitch;
    this.alerts = alerts;
    this.clock = clock;
    final int n = directory.size();
    position = new long[n];
    pendingBuy = new long[n];
    pendingSell = new long[n];
    contribution = new double[n][K];
    unit = new double[n][K];
    unitParallel = new double[n];
    known = new boolean[n];
    resHi = new double[n][K];
    resLo = new double[n][K];
    resNetHi = new double[n];
    resNetLo = new double[n];
    mark = new double[n];
    marked = new boolean[n];
    positionAlerted = new boolean[n];
    final long now = clock.nanoTime();
    orderRate = new TokenBucket(limits.orderRatePerSecond(), limits.burstSeconds(), now);
    quoteRate = new TokenBucket(limits.quoteRatePerSecond(), limits.burstSeconds(), now);
    messageRate = new TokenBucket(limits.messageRatePerSecond(), limits.burstSeconds(), now);
    for (int i = 0; i < n; i++) {
      refreshUnit(i);
    }
  }

  /** Re-reads rate limits after the limit set changed. */
  public void limitsChanged() {
    final long now = clock.nanoTime();
    orderRate.reset(limits.orderRatePerSecond(), limits.burstSeconds(), now);
    quoteRate.reset(limits.quoteRatePerSecond(), limits.burstSeconds(), now);
    messageRate.reset(limits.messageRatePerSecond(), limits.burstSeconds(), now);
  }

  // ---------------------------------------------------------------- state updates

  /**
   * Books a fill. {@code signedQuantityMinor} is from our side: positive when we bought. The
   * caller releases any reservation the fill consumed before calling this.
   */
  public void onFill(final int instrumentId, final long signedQuantityMinor, final long price) {
    final int i = index(instrumentId);
    final Instrument inst = directory.byIndex(i);
    final int c = inst.currency().index();
    final double qty = inst.major(signedQuantityMinor);
    final double px = Decimal9.toDouble(price);
    if (!marked[i]) {
      final long fairMid = fairValues.fairMid(instrumentId);
      mark[i] = fairMid == FairValueSource.NO_VALUE ? px : Decimal9.toDouble(fairMid);
      marked[i] = true;
    }
    cash[c] -= qty * px * inst.quoteValue();
    markValue[c] += qty * mark[i] * inst.quoteValue();
    position[i] += signedQuantityMinor;
    revalue(i);
    postTrade(c, i);
  }

  /** Marks an instrument to a new fair value (Decimal9) and updates daily P&amp;L. */
  public void onFairValue(final int instrumentId, final long fairMid) {
    final int i = index(instrumentId);
    final Instrument inst = directory.byIndex(i);
    final int c = inst.currency().index();
    final double px = Decimal9.toDouble(fairMid);
    if (marked[i]) {
      markValue[c] += inst.major(position[i]) * (px - mark[i]) * inst.quoteValue();
    } else {
      markValue[c] += inst.major(position[i]) * px * inst.quoteValue();
      marked[i] = true;
    }
    mark[i] = px;
    postTrade(c, -1);
  }

  /**
   * The curves of {@code currency} moved: re-reads unit sensitivities and revalues every position
   * and reservation in that currency, then runs post-trade checks.
   */
  public void onCurveMove(final CurrencyCode currency) {
    final int n = directory.size();
    for (int i = 0; i < n; i++) {
      if (directory.byIndex(i).currency() == currency) {
        refreshUnit(i);
        revalue(i);
        setReservation(i, pendingBuy[i], pendingSell[i]);
      }
    }
    postTrade(currency.index(), -1);
  }

  /** Starts a new day: P&amp;L is measured from the current value. */
  public void startOfDay() {
    for (int c = 0; c < C; c++) {
      startOfDay[c] = cash[c] + markValue[c];
      postAlerted[c][LimitKind.DailyLoss.value()] = false;
    }
  }

  /** Adds a reservation for a live quote, resting order or hedge parent. */
  public void reserve(final int instrumentId, final long buyMinor, final long sellMinor) {
    final int i = index(instrumentId);
    setReservation(i, pendingBuy[i] + buyMinor, pendingSell[i] + sellMinor);
  }

  /** Releases a reservation. Releasing more than is reserved is a bookkeeping error and throws. */
  public void release(final int instrumentId, final long buyMinor, final long sellMinor) {
    final int i = index(instrumentId);
    final long b = pendingBuy[i] - buyMinor;
    final long s = pendingSell[i] - sellMinor;
    if (b < 0 || s < 0) {
      throw new IllegalStateException(
          "release beyond reservation on "
              + instrumentId
              + ": buy "
              + pendingBuy[i]
              + "-"
              + buyMinor
              + ", sell "
              + pendingSell[i]
              + "-"
              + sellMinor);
    }
    setReservation(i, b, s);
  }

  // ---------------------------------------------------------------- pre-trade checks

  /**
   * Checks a two-way (or one-way, with a zero quantity) quote before it is sent. A hit on the bid
   * makes us buy {@code bidQuantity}; a lift of the ask makes us sell {@code askQuantity}. Order of
   * checks (API_SPEC.md Section 13.2): kill switch, instrument known, maximum size, fat finger,
   * price collar, position, pillar PV01, net PV01, gross PV01, daily loss, quote rate, message
   * rate. On success one quote token and one message token are taken.
   */
  public int checkQuote(
      final int instrumentId,
      final long bidPrice,
      final long bidQuantity,
      final long askPrice,
      final long askQuantity,
      final long journalSeqNo) {
    if (!killSwitch.isActive()) {
      preTradeRejects++;
      return REJECT_KILLED;
    }
    final int i = directory.indexOf(instrumentId);
    if (i < 0 || !known[i]) {
      preTradeRejects++;
      return REJECT_UNKNOWN;
    }
    int r = sizeAndPrice(i, bidPrice, bidQuantity);
    if (r == OK) {
      r = sizeAndPrice(i, askPrice, askQuantity);
    }
    if (r == OK) {
      r = envelope(i, bidQuantity, askQuantity);
    }
    if (r == OK) {
      r = dailyLoss(directory.byIndex(i).currency().index());
    }
    if (r == OK) {
      final long now = clock.nanoTime();
      if (!quoteRate.available(now)) {
        r = rate(LimitKind.QuoteRate, limits.quoteRatePerSecond());
      } else if (!messageRate.available(now)) {
        r = rate(LimitKind.MessageRate, limits.messageRatePerSecond());
      } else {
        quoteRate.take();
        messageRate.take();
      }
    }
    return finish(r, journalSeqNo);
  }

  /**
   * Checks an order before it is sent. {@code side} is ours. A risk-reducing order (one whose
   * parallel PV01 offsets the currency's net PV01 without overshooting it) skips the PV01 and
   * daily-loss checks so that a breached book can always be hedged; size, fat-finger, collar,
   * position and rate checks still apply.
   */
  public int checkOrder(
      final int instrumentId,
      final Side side,
      final long quantity,
      final long price,
      final long journalSeqNo) {
    if (!killSwitch.isActive()) {
      preTradeRejects++;
      return REJECT_KILLED;
    }
    final int i = directory.indexOf(instrumentId);
    if (i < 0 || !known[i]) {
      preTradeRejects++;
      return REJECT_UNKNOWN;
    }
    final long buy = side == Side.Buy ? quantity : 0L;
    final long sell = side == Side.Buy ? 0L : quantity;
    int r = sizeAndPrice(i, price, quantity);
    if (r == OK) {
      r = riskReducing(i, buy - sell) ? positionOnly(i, buy, sell) : envelope(i, buy, sell);
    }
    if (r == OK && !riskReducing(i, buy - sell)) {
      r = dailyLoss(directory.byIndex(i).currency().index());
    }
    if (r == OK) {
      final long now = clock.nanoTime();
      if (!orderRate.available(now)) {
        r = rate(LimitKind.OrderRate, limits.orderRatePerSecond());
      } else if (!messageRate.available(now)) {
        r = rate(LimitKind.MessageRate, limits.messageRatePerSecond());
      } else {
        orderRate.take();
        messageRate.take();
      }
    }
    return finish(r, journalSeqNo);
  }

  /**
   * Counts an exempt message (cancel, quote withdrawal) against the message rate without ever
   * refusing it: withdrawing risk is never rate limited.
   */
  public void countExemptMessage() {
    messageRate.takeExempt(clock.nanoTime());
  }

  /**
   * Largest additional quantity on {@code ourSide} that passes the position and PV01 envelope,
   * given current positions and reservations; zero if none. Used by the quoting engine to size
   * down before checking.
   */
  public long headroom(final int instrumentId, final Side ourSide) {
    final int i = directory.indexOf(instrumentId);
    if (i < 0 || !known[i] || !killSwitch.isActive()) {
      return 0L;
    }
    final boolean buy = ourSide == Side.Buy;
    final long maxPos = limits.maxPosition(instrumentId);
    long room =
        buy
            ? maxPos - (position[i] + pendingBuy[i])
            : maxPos + (position[i] - pendingSell[i]);
    room = Math.min(room, limits.maxOrderSize());
    if (room <= 0) {
      return 0L;
    }
    // bisection on the envelope, which is monotone in the added quantity
    if (envelopeQuiet(i, buy ? room : 0L, buy ? 0L : room)) {
      return room;
    }
    long lo = 0L;
    long hi = room;
    for (int iter = 0; iter < 48 && hi - lo > 1; iter++) {
      final long mid = lo + (hi - lo) / 2;
      if (envelopeQuiet(i, buy ? mid : 0L, buy ? 0L : mid)) {
        lo = mid;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  // ---------------------------------------------------------------- reads

  /** Position in minor units. */
  public long position(final int instrumentId) {
    return position[index(instrumentId)];
  }

  /** Reserved buy quantity. */
  public long pendingBuy(final int instrumentId) {
    return pendingBuy[index(instrumentId)];
  }

  /** Reserved sell quantity. */
  public long pendingSell(final int instrumentId) {
    return pendingSell[index(instrumentId)];
  }

  /** Copies the incremental ladder of {@code currency} into {@code out}. */
  public void ladder(final CurrencyCode currency, final Dv01Vector out) {
    out.currency(currency);
    for (int k = 0; k < K; k++) {
      out.set(k, ladder[currency.index()][k]);
    }
  }

  /** Recomputes the ladder of {@code currency} from positions alone (full revaluation). */
  public void fullLadder(final CurrencyCode currency, final Dv01Vector out) {
    out.clear();
    out.currency(currency);
    final int n = directory.size();
    for (int i = 0; i < n; i++) {
      final Instrument inst = directory.byIndex(i);
      if (inst.currency() == currency && position[i] != 0) {
        sensitivities.pv01(inst.id(), position[i], scratch);
        out.addScaled(scratch, 1.0);
      }
    }
  }

  /** Net PV01 of a currency. */
  public double netPv01(final CurrencyCode currency) {
    double s = 0.0;
    for (int k = 0; k < K; k++) {
      s += ladder[currency.index()][k];
    }
    return s;
  }

  /** Gross PV01 of a currency: sum of absolute pillar values. */
  public double grossPv01(final CurrencyCode currency) {
    double s = 0.0;
    for (int k = 0; k < K; k++) {
      s += Math.abs(ladder[currency.index()][k]);
    }
    return s;
  }

  /** Parallel PV01 of one product family in a currency. */
  public double productPv01(final CurrencyCode currency, final ProductType type) {
    return productPv01[currency.index()][type.index()];
  }

  /**
   * Futures-versus-cash basis exposure: the PV01 that futures and cash bonds offset each other by,
   * signed as the futures leg. Zero when they have the same sign. A one basis point widening of
   * the futures-implied yield over cash changes P&amp;L by this amount.
   */
  public double basisExposure(final CurrencyCode currency) {
    return offset(
        productPv01[currency.index()][ProductType.FUTURE.index()],
        productPv01[currency.index()][ProductType.BOND.index()]);
  }

  /** Swap-spread exposure: PV01 offset between swaps and bonds plus futures, signed as swaps. */
  public double swapSpreadExposure(final CurrencyCode currency) {
    final double[] p = productPv01[currency.index()];
    return offset(
        p[ProductType.SWAP.index()], p[ProductType.BOND.index()] + p[ProductType.FUTURE.index()]);
  }

  /**
   * Curve-spread exposure: P&amp;L of a steepener that moves the 2Y pillar down half a basis point
   * and the 10Y pillar up half a basis point, interpolating linearly in pillar position between
   * them and holding the slope's ends flat outside.
   */
  public double curveSpreadExposure(final CurrencyCode currency) {
    double s = 0.0;
    for (int k = 0; k < K; k++) {
      final double y = KeyRateTenors.years(k);
      final double w = y <= 2.0 ? -0.5 : (y >= 10.0 ? 0.5 : -0.5 + (y - 2.0) / 8.0);
      s += w * ladder[currency.index()][k];
    }
    return s;
  }

  /** P&amp;L since start of day in a currency, major units. */
  public double dailyPnl(final CurrencyCode currency) {
    final int c = currency.index();
    return cash[c] + markValue[c] - startOfDay[c];
  }

  /** Per-major-unit parallel PV01 of an instrument at the current curve. */
  public double unitParallelPv01(final int instrumentId) {
    return unitParallel[index(instrumentId)];
  }

  /** Per-major-unit PV01 of an instrument at pillar {@code k}. */
  public double unitPv01(final int instrumentId, final int k) {
    return unit[index(instrumentId)][k];
  }

  /** Breach messages sent, pre- and post-trade. */
  public long breachCount() {
    return breachCount;
  }

  /** Pre-trade checks that refused. */
  public long preTradeRejects() {
    return preTradeRejects;
  }

  public RiskLimits limits() {
    return limits;
  }

  public KillSwitchController killSwitch() {
    return killSwitch;
  }

  public InstrumentDirectory directory() {
    return directory;
  }

  /** Quote units per basis point of value for one major unit, or 0 if unknown. */
  public double quotePerBp(final int instrumentId) {
    final int i = index(instrumentId);
    return Math.abs(unitParallel[i]) / directory.byIndex(i).quoteValue();
  }

  // ---------------------------------------------------------------- internals

  private int index(final int instrumentId) {
    final int i = directory.indexOf(instrumentId);
    if (i < 0) {
      throw new IllegalArgumentException("unknown instrument " + instrumentId);
    }
    return i;
  }

  private void refreshUnit(final int i) {
    final Instrument inst = directory.byIndex(i);
    known[i] = sensitivities.pv01(inst.id(), inst.minorPerMajor(), scratch);
    double p = 0.0;
    for (int k = 0; k < K; k++) {
      unit[i][k] = scratch.get(k);
      p += scratch.get(k);
    }
    unitParallel[i] = p;
    known[i] = known[i] && p != 0.0;
  }

  private void revalue(final int i) {
    final Instrument inst = directory.byIndex(i);
    final int c = inst.currency().index();
    if (position[i] == 0) {
      scratch.clear();
    } else {
      sensitivities.pv01(inst.id(), position[i], scratch);
    }
    double oldPar = 0.0;
    double newPar = 0.0;
    for (int k = 0; k < K; k++) {
      final double v = scratch.get(k);
      ladder[c][k] += v - contribution[i][k];
      oldPar += contribution[i][k];
      newPar += v;
      contribution[i][k] = v;
    }
    productPv01[c][inst.type().index()] += newPar - oldPar;
  }

  private void term(final int i, final long buy, final long sell, final double[] hi, final double[] lo) {
    final Instrument inst = directory.byIndex(i);
    final double b = inst.major(buy);
    final double s = inst.major(sell);
    for (int k = 0; k < K; k++) {
      final double up = unit[i][k] * b;
      final double down = -unit[i][k] * s;
      hi[k] = Math.max(0.0, Math.max(up, down));
      lo[k] = Math.min(0.0, Math.min(up, down));
    }
  }

  private void setReservation(final int i, final long buy, final long sell) {
    final int c = directory.byIndex(i).currency().index();
    term(i, buy, sell, newHi, newLo);
    for (int k = 0; k < K; k++) {
      resHiAgg[c][k] += newHi[k] - resHi[i][k];
      resLoAgg[c][k] += newLo[k] - resLo[i][k];
      resHi[i][k] = newHi[k];
      resLo[i][k] = newLo[k];
    }
    final Instrument inst = directory.byIndex(i);
    final double up = unitParallel[i] * inst.major(buy);
    final double down = -unitParallel[i] * inst.major(sell);
    final double netHi = Math.max(0.0, Math.max(up, down));
    final double netLo = Math.min(0.0, Math.min(up, down));
    resNetHiAgg[c] += netHi - resNetHi[i];
    resNetLoAgg[c] += netLo - resNetLo[i];
    resNetHi[i] = netHi;
    resNetLo[i] = netLo;
    pendingBuy[i] = buy;
    pendingSell[i] = sell;
  }

  private int sizeAndPrice(final int i, final long price, final long quantity) {
    if (quantity == 0) {
      return OK;
    }
    final Instrument inst = directory.byIndex(i);
    if (quantity < 0 || quantity > limits.maxOrderSize()) {
      return record(
          LimitKind.MaxOrderSize, inst.id(), inst.major(limits.maxOrderSize()), inst.major(quantity));
    }
    if (quantity > limits.fatFingerQuantity()) {
      return record(
          LimitKind.FatFinger, inst.id(), inst.major(limits.fatFingerQuantity()), inst.major(quantity));
    }
    final long fairMid = fairValues.fairMid(inst.id());
    if (fairMid == FairValueSource.NO_VALUE) {
      return REJECT_UNKNOWN;
    }
    final double fair = Decimal9.toDouble(fairMid);
    final double px = Decimal9.toDouble(price);
    final double rel = fair == 0.0 ? Math.abs(px) : Math.abs(px / fair - 1.0);
    if (rel > limits.fatFingerFraction()) {
      return record(LimitKind.FatFinger, inst.id(), limits.fatFingerFraction(), rel);
    }
    final double bp = Math.abs(px - fair) * inst.quoteValue() / Math.abs(unitParallel[i]);
    if (bp > limits.priceCollarBp()) {
      return record(LimitKind.PriceCollar, inst.id(), limits.priceCollarBp(), bp);
    }
    return OK;
  }

  private int positionOnly(final int i, final long buy, final long sell) {
    final Instrument inst = directory.byIndex(i);
    final long maxPos = limits.maxPosition(inst.id());
    final long hiPos = position[i] + pendingBuy[i] + buy;
    final long loPos = position[i] - pendingSell[i] - sell;
    if (hiPos > maxPos) {
      return record(LimitKind.Position, inst.id(), inst.major(maxPos), inst.major(hiPos));
    }
    if (loPos < -maxPos) {
      return record(LimitKind.Position, inst.id(), inst.major(maxPos), inst.major(loPos));
    }
    return OK;
  }

  private int envelope(final int i, final long buy, final long sell) {
    final int r = positionOnly(i, buy, sell);
    if (r != OK) {
      return r;
    }
    return pv01Envelope(i, buy, sell, true);
  }

  private boolean envelopeQuiet(final int i, final long buy, final long sell) {
    final Instrument inst = directory.byIndex(i);
    final long maxPos = limits.maxPosition(inst.id());
    if (position[i] + pendingBuy[i] + buy > maxPos || position[i] - pendingSell[i] - sell < -maxPos) {
      return false;
    }
    return pv01Envelope(i, buy, sell, false) == OK;
  }

  private int pv01Envelope(final int i, final long buy, final long sell, final boolean report) {
    final Instrument inst = directory.byIndex(i);
    final int c = inst.currency().index();
    term(i, pendingBuy[i] + buy, pendingSell[i] + sell, newHi, newLo);
    double gross = 0.0;
    double net = 0.0;
    for (int k = 0; k < K; k++) {
      final double base = ladder[c][k];
      net += base;
      final double hi = base + resHiAgg[c][k] - resHi[i][k] + newHi[k];
      final double lo = base + resLoAgg[c][k] - resLo[i][k] + newLo[k];
      final double lim = limits.bucketPv01(k);
      if (hi > lim || lo < -lim) {
        return report ? record(LimitKind.BucketDv01, k, lim, hi > lim ? hi : lo) : LimitKind.BucketDv01.value();
      }
      gross += Math.max(Math.abs(hi), Math.abs(lo));
    }
    final double up = unitParallel[i] * inst.major(pendingBuy[i] + buy);
    final double down = -unitParallel[i] * inst.major(pendingSell[i] + sell);
    final double netHi = net + resNetHiAgg[c] - resNetHi[i] + Math.max(0.0, Math.max(up, down));
    final double netLo = net + resNetLoAgg[c] - resNetLo[i] + Math.min(0.0, Math.min(up, down));
    if (netHi > limits.netPv01() || netLo < -limits.netPv01()) {
      return report
          ? record(LimitKind.NetDv01, c, limits.netPv01(), netHi > limits.netPv01() ? netHi : netLo)
          : LimitKind.NetDv01.value();
    }
    if (gross > limits.grossPv01()) {
      return report ? record(LimitKind.GrossDv01, c, limits.grossPv01(), gross) : LimitKind.GrossDv01.value();
    }
    return OK;
  }

  private boolean riskReducing(final int i, final long signedQuantity) {
    final Instrument inst = directory.byIndex(i);
    final double orderPv01 = unitParallel[i] * inst.major(signedQuantity);
    final double net = netPv01(inst.currency());
    return orderPv01 * net < 0.0 && Math.abs(orderPv01) <= Math.abs(net);
  }

  private int dailyLoss(final int c) {
    final double pnl = cash[c] + markValue[c] - startOfDay[c];
    if (pnl < -limits.dailyLoss()) {
      return record(LimitKind.DailyLoss, c, -limits.dailyLoss(), pnl);
    }
    return OK;
  }

  private int rate(final LimitKind kind, final double limit) {
    return record(kind, 0, limit, limit);
  }

  private int record(final LimitKind kind, final int scope, final double limit, final double observed) {
    lastScope = scope;
    lastLimit = limit;
    lastObserved = observed;
    return kind.value();
  }

  private int finish(final int r, final long journalSeqNo) {
    if (r != OK) {
      preTradeRejects++;
      if (r > 0) {
        publish(LimitKind.get((short) r), lastScope, lastLimit, lastObserved, journalSeqNo);
      }
    }
    return r;
  }

  private void publish(
      final LimitKind kind, final int scope, final double limit, final double observed, final long seq) {
    breachCount++;
    breach
        .wrapAndApplyHeader(buffer, 0, header)
        .limitKind(kind)
        .scopeId(scope)
        .rejectedMessageSeqNo(seq)
        .timestampNanos(clock.epochNanos());
    breach.limitValue().mantissa(Decimal9.saturating(limit));
    breach.observedValue().mantissa(Decimal9.saturating(observed));
    alerts.send(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + breach.encodedLength());
  }

  /** Post-trade alerts, edge-triggered: one message when a limit becomes breached. */
  private void postTrade(final int c, final int instrumentIndex) {
    if (instrumentIndex >= 0) {
      final Instrument inst = directory.byIndex(instrumentIndex);
      final long maxPos = limits.maxPosition(inst.id());
      final boolean over = Math.abs(position[instrumentIndex]) > maxPos;
      if (over && !positionAlerted[instrumentIndex]) {
        publish(LimitKind.Position, inst.id(), inst.major(maxPos), inst.major(position[instrumentIndex]), 0L);
      }
      positionAlerted[instrumentIndex] = over;
    }
    double net = 0.0;
    double gross = 0.0;
    int worstBucket = -1;
    double worstValue = 0.0;
    for (int k = 0; k < K; k++) {
      final double v = ladder[c][k];
      net += v;
      gross += Math.abs(v);
      if (Math.abs(v) > limits.bucketPv01(k) && worstBucket < 0) {
        worstBucket = k;
        worstValue = v;
      }
    }
    edge(c, LimitKind.BucketDv01, worstBucket >= 0, worstBucket, worstBucket >= 0 ? limits.bucketPv01(worstBucket) : 0.0, worstValue);
    edge(c, LimitKind.NetDv01, Math.abs(net) > limits.netPv01(), c, limits.netPv01(), net);
    edge(c, LimitKind.GrossDv01, gross > limits.grossPv01(), c, limits.grossPv01(), gross);
    final double pnl = cash[c] + markValue[c] - startOfDay[c];
    final boolean loss = pnl < -limits.dailyLoss();
    final boolean newLoss = loss && !postAlerted[c][LimitKind.DailyLoss.value()];
    edge(c, LimitKind.DailyLoss, loss, c, -limits.dailyLoss(), pnl);
    if (newLoss && limits.killOnDailyLoss()) {
      killSwitch.trigger(KillReason.LimitBreach, 0, 0);
    }
  }

  private void edge(
      final int c,
      final LimitKind kind,
      final boolean breached,
      final int scope,
      final double limit,
      final double observed) {
    final int slot = kind.value();
    if (breached && !postAlerted[c][slot]) {
      publish(kind, scope, limit, observed, 0L);
    }
    postAlerted[c][slot] = breached;
  }

  private static double offset(final double a, final double b) {
    if (a * b >= 0.0) {
      return 0.0;
    }
    return Math.signum(a) * Math.min(Math.abs(a), Math.abs(b));
  }
}
