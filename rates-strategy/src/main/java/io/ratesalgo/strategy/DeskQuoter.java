package io.ratesalgo.strategy;

import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.InstrumentSet;
import io.ratesalgo.backtest.Strategy;
import io.ratesalgo.backtest.StrategyContext;
import io.ratesalgo.backtest.util.Prices;
import io.ratesalgo.hedge.AxeSource;
import io.ratesalgo.messages.sbe.ExecutionReportDecoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteResponseDecoder;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;
import org.agrona.collections.Long2LongHashMap;

/**
 * The desk strategy: the hit-rate-optimal quoter of experiment P5-001 with the six behavioural
 * desk features plugged in (API_SPEC.md Section 19.8).
 *
 * <p>Per RFQ: refresh the {@link RiskLadder}, ask the {@link QuoteSizer} whether to quote the
 * size, add the size premium and the {@link InventoryPenalty} to the spread optimiser's costs,
 * look the client's tier up in the {@link ClientTiering}, optimise the half-spread, take off the
 * axe improvement of the {@link AxeSource}, and quote symmetrically around mid. Per client fill:
 * feed the hedge timing's flow estimate, schedule a markout for the tiering, and ask the {@link
 * HedgeBudget} and {@link HedgeTiming} whether to hedge into the 10-year note future.
 *
 * <p>With every feature at its default ({@link #baselineFeatures}) the strategy sends the same
 * messages as {@code ModelSpreadStrategy} on the same journal, byte for byte
 * (DeskQuoterBaselineTest), so the experiments compare each feature against today's engine. The
 * per-RFQ and per-fill paths of the strategy itself allocate nothing once warmed up
 * (DeskQuoterAllocationTest); the back-tester around it does.
 */
public final class DeskQuoter implements Strategy {

  /** The features a desk strategy runs with. */
  public record Features(
      RiskLadder ladder,
      SpreadOptimizer spread,
      HedgeBudget budget,
      HedgeTiming timing,
      AxeSource axes,
      QuoteSizer sizer,
      ClientTiering tiering,
      InventoryPenalty penalty,
      long markoutHorizonNanos) {

    public Features {
      if (markoutHorizonNanos <= 0) {
        throw new IllegalArgumentException("markout horizon must be positive");
      }
    }
  }

  /** Every feature at today's behaviour. */
  public static Features baselineFeatures(
      final RiskLadder ladder,
      final SpreadOptimizer spread,
      final double hedgeThresholdDv01,
      final int clientsPerTier,
      final long markoutHorizonNanos) {
    return new Features(
        ladder,
        spread,
        HedgeBudget.threshold(hedgeThresholdDv01),
        HedgeTiming.ALWAYS_IMMEDIATE,
        AxeSource.NONE,
        QuoteSizer.UNLIMITED,
        new StaticTiering(clientsPerTier),
        InventoryPenalty.NONE,
        markoutHorizonNanos);
  }

  private static final int SLOTS = 8192;
  private static final long RECHECK = Long.MAX_VALUE;
  private static final long NONE = -1L;

  private final String name;
  private final Features f;
  private final RiskLadder ladder;
  private final int hedgeIndex;

  // live RFQs: rfqId -> slot
  private final Long2LongHashMap rfqSlot = new Long2LongHashMap(SLOTS * 2, 0.5f, NONE);
  private final int[] rfqIndex = new int[SLOTS];
  private final boolean[] rfqWeBuy = new boolean[SLOTS];
  private final long[] rfqQuantity = new long[SLOTS];
  private final int[] rfqClient = new int[SLOTS];
  private int nextRfqSlot;

  // timers: timerId -> markout slot, or RECHECK
  private final Long2LongHashMap timers = new Long2LongHashMap(SLOTS * 2, 0.5f, NONE);
  private final int[] markClient = new int[SLOTS];
  private final int[] markIndex = new int[SLOTS];
  private final long[] markSigned = new long[SLOTS];
  private final double[] markMid = new double[SLOTS];
  private int nextMarkSlot;

  private boolean hedgeInFlight;
  private long lastClientFillNanos;
  private long recheckAt = Long.MAX_VALUE;

  // counters
  private long quotes;
  private long sizeDeclines;
  private long clientFills;
  private long hedgesSent;
  private long waits;
  private long stagedSlices;
  private long markoutsRecorded;

  public DeskQuoter(final String name, final Features features) {
    this.name = name;
    this.f = features;
    this.ladder = features.ladder();
    this.hedgeIndex = ladder.indexOf(InstrumentSet.TY_FUTURE);
    if (hedgeIndex < 0) {
      throw new IllegalArgumentException("the ladder has no hedge instrument " + InstrumentSet.TY_FUTURE);
    }
  }

  @Override
  public String name() {
    return name;
  }

  private void refresh(final StrategyContext ctx) {
    for (int i = 0; i < ladder.size(); i++) {
      ladder.setPosition(i, ctx.position(ladder.instrumentId(i)));
    }
    ladder.recompute();
  }

  // ---------------------------------------------------------------- RFQs

  @Override
  public void onQuoteRequest(final QuoteRequestDecoder rfq, final StrategyContext ctx) {
    final int id = rfq.instrumentId();
    final int index = ladder.indexOf(id);
    if (index < 0 || !ctx.hasMid(id)) {
      return; // unknown instrument or no reference price yet: decline
    }
    final Instrument instrument = ladder.instrument(index);
    final long quantity = rfq.quantity();
    final double size = (double) quantity / InstrumentSet.MILLION;
    final boolean weBuy = rfq.side() != Side.Buy;
    refresh(ctx);
    final double tradeDv01 = ladder.tradeDv01(index, weBuy, quantity);
    if (f.sizer().maxQuantity(ladder, index, tradeDv01, quantity) < quantity) {
      sizeDeclines++;
      return;
    }
    final double extra =
        f.sizer().premiumBp(ladder, index, tradeDv01, size) + f.penalty().penaltyBp(ladder, index, weBuy, quantity);
    final int tier = f.tiering().tier(rfq.clientId());
    final double s =
        f.spread()
            .optimalHalfSpreadBp(
                instrument, size, tier, rfq.dealerCount(), ctx.calendar().inWindow(ctx.nowEpochNanos()), extra);
    final double axe = f.axes().axeBp(id, weBuy ? Side.Buy : Side.Sell);
    final double h = Math.max(0.0, s - axe);
    final double mid = ctx.mid(id);
    final double distance = instrument.bpToPrice(h);
    final long tick = instrument.tickMantissa();
    final long bid = Prices.roundToTick(Prices.toMantissa(mid - distance), tick, false);
    final long ask = Prices.roundToTick(Prices.toMantissa(mid + distance), tick, true);
    final int slot = nextRfqSlot;
    nextRfqSlot = (nextRfqSlot + 1) % SLOTS;
    rfqIndex[slot] = index;
    rfqWeBuy[slot] = weBuy;
    rfqQuantity[slot] = quantity;
    rfqClient[slot] = rfq.clientId();
    rfqSlot.put(rfq.rfqId(), slot);
    quotes++;
    ctx.quote(rfq.rfqId(), bid, ask);
  }

  @Override
  public void onQuoteResponse(final QuoteResponseDecoder response, final StrategyContext ctx) {
    final long slot = rfqSlot.remove(response.rfqId());
    if (response.status() != QuoteStatus.Done || slot == NONE) {
      return;
    }
    final int s = (int) slot;
    final int index = rfqIndex[s];
    final long now = ctx.nowEpochNanos();
    clientFills++;
    lastClientFillNanos = now;
    f.timing().onClientFill(ladder.tradeDv01(index, rfqWeBuy[s], rfqQuantity[s]), now);
    if (f.tiering().wantsMarkouts()) {
      final int m = nextMarkSlot;
      nextMarkSlot = (nextMarkSlot + 1) % SLOTS;
      markClient[m] = rfqClient[s];
      markIndex[m] = index;
      markSigned[m] = rfqWeBuy[s] ? rfqQuantity[s] : -rfqQuantity[s];
      markMid[m] = ctx.mid(ladder.instrumentId(index));
      timers.put(ctx.scheduleTimer(now + f.markoutHorizonNanos()), m);
    }
    maybeHedge(ctx);
  }

  // ---------------------------------------------------------------- hedging

  @Override
  public void onExecutionReport(final ExecutionReportDecoder report, final StrategyContext ctx) {
    final OrdStatus s = report.ordStatus();
    if (s == OrdStatus.Filled || s == OrdStatus.Rejected || s == OrdStatus.Cancelled) {
      hedgeInFlight = false;
      if (s == OrdStatus.Filled) {
        maybeHedge(ctx);
      }
    }
  }

  @Override
  public void onTimer(final long timerId, final long nowEpochNanos, final StrategyContext ctx) {
    final long payload = timers.remove(timerId);
    if (payload == RECHECK) {
      if (nowEpochNanos >= recheckAt) {
        recheckAt = Long.MAX_VALUE;
      }
      maybeHedge(ctx);
    } else if (payload != NONE) {
      final int m = (int) payload;
      final int id = ladder.instrumentId(markIndex[m]);
      final Instrument instrument = ladder.instrument(markIndex[m]);
      final double move = ctx.mid(id) - markMid[m];
      final double bp = Math.signum((double) markSigned[m]) * move / Math.abs(instrument.priceChangePerBp());
      f.tiering().onMarkout(markClient[m], bp, nowEpochNanos);
      f.tiering().review(nowEpochNanos);
      markoutsRecorded++;
    }
  }

  private void scheduleRecheck(final StrategyContext ctx, final long at) {
    final long now = ctx.nowEpochNanos();
    if (recheckAt <= at && recheckAt >= now) {
      return; // an earlier or equal recheck is already pending
    }
    recheckAt = Math.max(at, now);
    timers.put(ctx.scheduleTimer(recheckAt), RECHECK);
  }

  private void maybeHedge(final StrategyContext ctx) {
    if (hedgeInFlight || !ctx.hasMid(InstrumentSet.TY_FUTURE)) {
      return;
    }
    refresh(ctx);
    final long now = ctx.nowEpochNanos();
    final double excess = f.budget().excessDv01(ladder, now - lastClientFillNanos);
    if (!(Math.abs(excess) > 0)) {
      f.timing().reset();
      final long hold = f.budget().holdNanos();
      if (hold > 0 && now - lastClientFillNanos < hold) {
        scheduleRecheck(ctx, lastClientFillNanos + hold);
      }
      return;
    }
    switch (f.timing().decide(excess, now)) {
      case WAIT -> {
        waits++;
        scheduleRecheck(ctx, now + f.timing().waitNanos());
      }
      case STAGED -> {
        if (send(ctx, excess / f.timing().slices())) {
          stagedSlices++;
          f.timing().reset();
          scheduleRecheck(ctx, now + f.timing().sliceIntervalNanos());
        } else {
          send(ctx, excess);
        }
      }
      case IMMEDIATE -> send(ctx, excess);
    }
  }

  /** Sends a market order removing {@code dv01}; false when it rounds to zero contracts. */
  private boolean send(final StrategyContext ctx, final double dv01) {
    final Instrument future = ladder.instrument(hedgeIndex);
    final double perContract = future.dv01PerUnit() * future.lotSize();
    final long contracts = Math.round(-dv01 / perContract);
    if (contracts == 0) {
      return false;
    }
    hedgeInFlight = true;
    hedgesSent++;
    f.timing().reset();
    ctx.sendOrder(
        InstrumentSet.TY_FUTURE,
        contracts > 0 ? Side.Buy : Side.Sell,
        OrdType.Market,
        Math.abs(contracts) * future.lotSize(),
        0L,
        true);
    return true;
  }

  // ---------------------------------------------------------------- counters

  public long quotes() {
    return quotes;
  }

  public long sizeDeclines() {
    return sizeDeclines;
  }

  public long clientFills() {
    return clientFills;
  }

  public long hedgesSent() {
    return hedgesSent;
  }

  public long waits() {
    return waits;
  }

  public long stagedSlices() {
    return stagedSlices;
  }

  public long markoutsRecorded() {
    return markoutsRecorded;
  }

  public Features features() {
    return f;
  }
}
