package io.ratesalgo.quoting;

import io.ratesalgo.api.BookSource;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.FairValueSource;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.InstrumentDirectory;
import io.ratesalgo.api.OutboundSink;
import io.ratesalgo.common.time.Clock;
import io.ratesalgo.messages.sbe.CancelReason;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.NewOrderEncoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.OrderCancelEncoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import io.ratesalgo.risk.KillSwitchListener;
import io.ratesalgo.risk.RiskEngine;
import java.nio.ByteBuffer;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Resting two-way quotes on a central limit order book (API_SPEC.md Section 12.8).
 *
 * <ul>
 *   <li>Queue-position aware: a resting order is kept while its price is within {@code
 *       requoteThresholdBp} of the target, so it keeps its place in the queue; the estimated
 *       quantity ahead of it falls as the displayed size at its level falls.
 *   <li>Pulls on adverse signals: a move in the futures signal of more than {@code futuresJumpBp}
 *       since the last update cancels both sides for a cool-down; a book imbalance beyond {@code
 *       imbalanceThreshold} cancels the side about to be run over.
 *   <li>Re-quote throttle: at most one cancel-and-replace per side per {@code minRequoteNanos}.
 *   <li>Participation cap: resting size at most {@code participation} times the displayed size at
 *       the best price on that side, and never more than the risk headroom.
 *   <li>Self-match prevention: a buy is never placed at or above our own resting sell, nor a sell
 *       at or below our own resting buy.
 *   <li>Every order passes {@link RiskEngine#checkOrder} and reserves its quantity until the venue
 *       reports it filled, cancelled or rejected. Cancels are never rate limited.
 * </ul>
 *
 * Allocation-free after construction; up to 32 instruments.
 */
public final class ClobQuoter implements KillSwitchListener {

  private static final int MAX = 32;

  /** Settings; every value is a declared assumption. */
  public static final class Config {
    int venueId = 1;
    double halfSpreadBp = 0.25;
    double requoteThresholdBp = 0.10;
    long minRequoteNanos = 50_000_000L;
    double futuresJumpBp = 0.5;
    double imbalanceThreshold = 0.6;
    long cooldownNanos = 1_000_000_000L;
    double participation = 0.25;
    long baseQuantity = 1_000_000_000L;
    long minQuantity = 100_000_000L;
    long clOrdIdBase = 1L << 50;

    public Config venueId(final int v) {
      venueId = v;
      return this;
    }

    public Config halfSpreadBp(final double v) {
      halfSpreadBp = v;
      return this;
    }

    public Config requoteThresholdBp(final double v) {
      requoteThresholdBp = v;
      return this;
    }

    public Config minRequoteNanos(final long v) {
      minRequoteNanos = v;
      return this;
    }

    public Config futuresJumpBp(final double v) {
      futuresJumpBp = v;
      return this;
    }

    public Config imbalanceThreshold(final double v) {
      imbalanceThreshold = v;
      return this;
    }

    public Config cooldownNanos(final long v) {
      cooldownNanos = v;
      return this;
    }

    public Config participation(final double v) {
      participation = v;
      return this;
    }

    public Config baseQuantity(final long v) {
      baseQuantity = v;
      return this;
    }

    public Config minQuantity(final long v) {
      minQuantity = v;
      return this;
    }
  }

  private final InstrumentDirectory directory;
  private final RiskEngine risk;
  private final FairValueEngine fairValue;
  private final BookSource book;
  private final OutboundSink gateway;
  private final Clock clock;
  private final Config config;
  private final long maxInputAgeNanos;

  private final int[] ids = new int[MAX];
  private int count;
  // per instrument and side (index 2*i for bid, 2*i+1 for ask)
  private final long[] clOrdId = new long[2 * MAX];
  private final long[] price = new long[2 * MAX];
  private final long[] leaves = new long[2 * MAX];
  private final long[] reserved = new long[2 * MAX];
  private final boolean[] cancelPending = new boolean[2 * MAX];
  private final long[] queueAhead = new long[2 * MAX];
  private final long[] lastAmend = new long[2 * MAX];
  private final long[] pulledUntil = new long[MAX];
  private final double[] lastSignalMid = new double[MAX];
  private final Long2LongHashMap orders = new Long2LongHashMap(256, 0.5f, -1L);
  private long nextClOrdId;

  private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final NewOrderEncoder newOrder = new NewOrderEncoder();
  private final OrderCancelEncoder cancel = new OrderCancelEncoder();
  private long ordersSent;
  private long cancelsSent;
  private long adversePulls;
  private long selfMatchesPrevented;
  private long throttled;

  public ClobQuoter(
      final InstrumentDirectory directory,
      final RiskEngine risk,
      final FairValueEngine fairValue,
      final BookSource book,
      final OutboundSink gateway,
      final Clock clock,
      final Config config,
      final long maxInputAgeNanos) {
    this.directory = directory;
    this.risk = risk;
    this.fairValue = fairValue;
    this.book = book;
    this.gateway = gateway;
    this.clock = clock;
    this.config = config;
    this.maxInputAgeNanos = maxInputAgeNanos;
    this.nextClOrdId = config.clOrdIdBase;
  }

  /** Default settings. */
  public static Config config() {
    return new Config();
  }

  /** Starts quoting an instrument. */
  public void enable(final int instrumentId) {
    if (count == MAX || directory.byId(instrumentId) == null) {
      throw new IllegalArgumentException("cannot enable " + instrumentId);
    }
    ids[count] = instrumentId;
    lastSignalMid[count] = Double.NaN;
    count++;
  }

  /** Re-evaluates one instrument: pulls, keeps, re-quotes or places. */
  public void update(final int instrumentId, final long nowNanos) {
    final int i = slot(instrumentId);
    if (i < 0) {
      return;
    }
    if (!risk.killSwitch().isActive()) {
      return;
    }
    final Instrument inst = directory.byId(instrumentId);
    if (!fairValue.compute(inst, nowNanos) || nowNanos - fairValue.oldestNanos() > maxInputAgeNanos) {
      cancelSide(2 * i, CancelReason.StaleInput, nowNanos);
      cancelSide(2 * i + 1, CancelReason.StaleInput, nowNanos);
      return;
    }
    final double fair = fairValue.fair();
    final double qpb = risk.quotePerBp(instrumentId);
    // adverse: futures jump
    final int signal = inst.signalInstrumentId() != 0 ? inst.signalInstrumentId() : instrumentId;
    final double sigMid = mid(signal);
    if (!Double.isNaN(sigMid)) {
      final double prev = lastSignalMid[i];
      lastSignalMid[i] = sigMid;
      if (!Double.isNaN(prev) && Math.abs(sigMid - prev) / risk.quotePerBp(signal) > config.futuresJumpBp) {
        adversePulls++;
        pulledUntil[i] = nowNanos + config.cooldownNanos;
        cancelSide(2 * i, CancelReason.AdverseSignal, nowNanos);
        cancelSide(2 * i + 1, CancelReason.AdverseSignal, nowNanos);
        return;
      }
    }
    if (nowNanos < pulledUntil[i]) {
      return;
    }
    // adverse: imbalance
    final long bs = book.bidSize(instrumentId);
    final long as = book.askSize(instrumentId);
    final double imbalance = bs + as > 0 ? (double) (bs - as) / (bs + as) : 0.0;
    final boolean suppressAsk = imbalance > config.imbalanceThreshold;
    final boolean suppressBid = imbalance < -config.imbalanceThreshold;
    if (suppressAsk) {
      adversePulls += cancelSide(2 * i + 1, CancelReason.AdverseSignal, nowNanos) ? 1 : 0;
    }
    if (suppressBid) {
      adversePulls += cancelSide(2 * i, CancelReason.AdverseSignal, nowNanos) ? 1 : 0;
    }
    final long bidTarget = Decimal9.floor(fair - config.halfSpreadBp * qpb);
    final long askTarget = Decimal9.ceil(fair + config.halfSpreadBp * qpb);
    if (!suppressBid) {
      side(i, true, bidTarget, bs, qpb, nowNanos);
    }
    if (!suppressAsk) {
      side(i, false, askTarget, as, qpb, nowNanos);
    }
  }

  /** A venue execution report for one of our orders. */
  public void onExecution(
      final long id, final long lastQuantity, final long lastPrice, final OrdStatus status) {
    final int s = (int) orders.get(id);
    if (s < 0) {
      return;
    }
    final int instrumentId = ids[s / 2];
    final boolean buy = (s & 1) == 0;
    if (lastQuantity > 0) {
      final long q = Math.min(lastQuantity, leaves[s]);
      leaves[s] -= q;
      unreserve(s, instrumentId, buy, q);
      risk.onFill(instrumentId, buy ? q : -q, lastPrice);
    }
    if (status == OrdStatus.Filled || status == OrdStatus.Cancelled || status == OrdStatus.Rejected || leaves[s] == 0) {
      unreserve(s, instrumentId, buy, reserved[s]);
      orders.remove(id);
      clOrdId[s] = 0L;
      leaves[s] = 0L;
      cancelPending[s] = false;
    }
  }

  /** Recomputes the estimated quantity ahead of each resting order from the book. */
  public void onBook(final int instrumentId) {
    final int i = slot(instrumentId);
    if (i < 0) {
      return;
    }
    for (int b = 0; b < 2; b++) {
      final int s = 2 * i + b;
      if (clOrdId[s] == 0L) {
        continue;
      }
      final long best = b == 0 ? book.bestBid(instrumentId) : book.bestAsk(instrumentId);
      final long size = b == 0 ? book.bidSize(instrumentId) : book.askSize(instrumentId);
      if (best == price[s]) {
        queueAhead[s] = Math.min(queueAhead[s], Math.max(0L, size - leaves[s]));
      }
    }
  }

  @Override
  public void onKill(final KillReason reason, final long epochNanos) {
    for (int s = 0; s < 2 * count; s++) {
      cancelSide(s, CancelReason.KillSwitch, epochNanos);
    }
  }

  /** Our resting order id on a side, or 0. */
  public long restingOrder(final int instrumentId, final boolean bid) {
    final int i = slot(instrumentId);
    return i < 0 ? 0L : clOrdId[2 * i + (bid ? 0 : 1)];
  }

  /** Resting price, Decimal9, or 0. */
  public long restingPrice(final int instrumentId, final boolean bid) {
    final int i = slot(instrumentId);
    return i < 0 ? 0L : price[2 * i + (bid ? 0 : 1)];
  }

  /** Estimated quantity ahead of our resting order. */
  public long queueAhead(final int instrumentId, final boolean bid) {
    final int i = slot(instrumentId);
    return i < 0 ? 0L : queueAhead[2 * i + (bid ? 0 : 1)];
  }

  /** Whether a cancel is outstanding on that side. */
  public boolean cancelPending(final int instrumentId, final boolean bid) {
    final int i = slot(instrumentId);
    return i >= 0 && cancelPending[2 * i + (bid ? 0 : 1)];
  }

  public long ordersSent() {
    return ordersSent;
  }

  public long cancelsSent() {
    return cancelsSent;
  }

  public long adversePulls() {
    return adversePulls;
  }

  public long selfMatchesPrevented() {
    return selfMatchesPrevented;
  }

  public long throttled() {
    return throttled;
  }

  private void side(
      final int i, final boolean bid, final long target, final long levelSize, final double qpb, final long now) {
    final int s = 2 * i + (bid ? 0 : 1);
    final int instrumentId = ids[i];
    if (clOrdId[s] != 0L) {
      if (cancelPending[s]) {
        return;
      }
      if (Math.abs(Decimal9.toDouble(target) - Decimal9.toDouble(price[s])) <= config.requoteThresholdBp * qpb) {
        return; // keep queue priority
      }
      if (now - lastAmend[s] < config.minRequoteNanos) {
        throttled++;
        return;
      }
      cancelSide(s, CancelReason.Requote, now);
      return; // replace after the venue confirms the cancel
    }
    if (now - lastAmend[s] < config.minRequoteNanos) {
      throttled++;
      return;
    }
    final int other = bid ? s + 1 : s - 1;
    if (clOrdId[other] != 0L && (bid ? price[other] <= target : price[other] >= target)) {
      selfMatchesPrevented++;
      return;
    }
    long qty = Math.min(config.baseQuantity, (long) (config.participation * levelSize));
    qty = Math.min(qty, risk.headroom(instrumentId, bid ? Side.Buy : Side.Sell));
    qty -= qty % config.minQuantity;
    if (qty <= 0) {
      return;
    }
    final Side side = bid ? Side.Buy : Side.Sell;
    if (risk.checkOrder(instrumentId, side, qty, target, 0L) != RiskEngine.OK) {
      return;
    }
    risk.reserve(instrumentId, bid ? qty : 0L, bid ? 0L : qty);
    final long id = ++nextClOrdId;
    newOrder
        .wrapAndApplyHeader(buffer, 0, header)
        .venueId(config.venueId)
        .clOrdId(id)
        .parentId(0L)
        .instrumentId(instrumentId)
        .side(side)
        .ordType(OrdType.Limit)
        .timeInForce(TimeInForce.Day)
        .quantity(qty)
        .expireNanos(0L)
        .sentNanos(clock.epochNanos());
    newOrder.price().mantissa(target);
    gateway.send(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + newOrder.encodedLength());
    ordersSent++;
    clOrdId[s] = id;
    price[s] = target;
    leaves[s] = qty;
    reserved[s] = qty;
    cancelPending[s] = false;
    lastAmend[s] = now;
    final long best = bid ? book.bestBid(instrumentId) : book.bestAsk(instrumentId);
    queueAhead[s] = best == target ? levelSize : 0L;
    orders.put(id, s);
  }

  private boolean cancelSide(final int s, final CancelReason reason, final long now) {
    if (clOrdId[s] == 0L || cancelPending[s]) {
      return false;
    }
    final int instrumentId = ids[s / 2];
    cancel
        .wrapAndApplyHeader(buffer, 0, header)
        .venueId(config.venueId)
        .clOrdId(++nextClOrdId)
        .origClOrdId(clOrdId[s])
        .instrumentId(instrumentId)
        .side((s & 1) == 0 ? Side.Buy : Side.Sell)
        .reason(reason)
        .sentNanos(now);
    risk.countExemptMessage();
    gateway.send(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + cancel.encodedLength());
    cancelsSent++;
    cancelPending[s] = true;
    lastAmend[s] = now;
    return true;
  }

  private void unreserve(final int s, final int instrumentId, final boolean buy, final long q) {
    final long r = Math.min(q, reserved[s]);
    if (r > 0) {
      risk.release(instrumentId, buy ? r : 0L, buy ? 0L : r);
      reserved[s] -= r;
    }
  }

  private double mid(final int id) {
    final long b = book.bestBid(id);
    final long a = book.bestAsk(id);
    if (b == FairValueSource.NO_VALUE || a == FairValueSource.NO_VALUE) {
      return Double.NaN;
    }
    return 0.5 * (Decimal9.toDouble(b) + Decimal9.toDouble(a));
  }

  private int slot(final int instrumentId) {
    for (int i = 0; i < count; i++) {
      if (ids[i] == instrumentId) {
        return i;
      }
    }
    return -1;
  }
}
