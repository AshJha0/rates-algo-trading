package io.ratesalgo.quoting;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.InstrumentDirectory;
import io.ratesalgo.api.KeyRateTenors;
import io.ratesalgo.api.OutboundSink;
import io.ratesalgo.common.time.Clock;
import io.ratesalgo.hedge.AxeSource;
import io.ratesalgo.hedge.HedgeCostModel;
import io.ratesalgo.hedge.HedgePlanning;
import io.ratesalgo.hedge.HedgeUniverse;
import io.ratesalgo.hedge.KeyRateCovariance;
import io.ratesalgo.messages.sbe.CancelReason;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.QuoteWithdrawEncoder;
import io.ratesalgo.messages.sbe.QuoteEncoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteResponseDecoder;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.risk.KillSwitchListener;
import io.ratesalgo.risk.RiskEngine;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * The RFQ quoting engine (API_SPEC.md Section 12; decision flow in
 * docs/diagrams/rfq_decision.mmd).
 *
 * <p>For each request: duplicate, kill-switch, input-health, instrument, expiry, client tier and
 * re-quote throttle checks; fair value with input-age check; size reduced to the risk headroom;
 * optimal half-spread from the hit-rate model against adverse selection, winner's curse and hedge
 * cost, widened for volatility and scheduled events and scaled by client tier; inventory skew,
 * size penalty and hedger axe; a bound so the quote never sits more than {@code maxImproveBp}
 * through fair; the risk engine's pre-trade check; then a firm or subject Quote with a time to
 * live, and a reservation of the quoted quantity until the quote is filled, expires or is
 * answered.
 *
 * <p>{@link #poll} expires quotes at their time to live, pulls every quote when an input becomes
 * unhealthy or a scheduled release's pull window opens, pulls any quote whose oldest input has
 * grown older than the maximum age or whose fair value has moved against it by more than {@code
 * fairMoveWithdrawBp}, and measures fill markouts for the toxicity table from the mid at the
 * trade. Every RFQ decision and every response, with the cover and our rank where the venue gives
 * one, goes to the {@link RfqRecordListener}.
 *
 * <p>Acceptance (review 2026-11-23, Q7). A Done is compared with the price we quoted: a difference
 * beyond {@code acceptPriceToleranceBp} is counted and reported to the recorder as a price
 * mismatch, and booked or not as {@code rejectMismatchedDone} says. A Done for zero quantity is
 * not a trade: nothing is booked and the hit-rate model is not taught a win.
 *
 * <p>Limits (Q9). The quoted size is also held inside the per-RFQ DV01 limit and what is left of
 * the client's DV01 limit ({@link RiskEngine#quoteHeadroom}); the engine tells the risk engine the
 * DV01 live to each client and the DV01 each client deals.
 *
 * <p>Strategy path (Round 5, docs/research/STRATEGY_V2.md). When {@link StrategyConfig} turns
 * on any pricing capability, the spread, skew and price are computed by {@link #strategyPrice}
 * instead: break-even from the client's markouts and the cost of the hedge planner's mix, one
 * capped inventory term, the expected-P&amp;L quote against the predicted cover, and the price on
 * the venue tick in the dealer's favour; see that method. With every switch off nothing on the
 * default path changes.
 *
 * <p>Single-writer; the RFQ, response and poll paths do not allocate after warm-up
 * (QuotingAllocationTest, StrategyAllocationTest).
 */
public final class QuotingEngine implements KillSwitchListener {

  private static final int K = KeyRateTenors.COUNT;
  private static final int MARKOUTS = 4096;
  /** Expired quotes remembered so a Done that raced the expiry is still booked (DR-R2R-13). */
  private static final int TOMBSTONES = 1024;
  private static final int DEFAULT_DEALERS = 3;

  private final InstrumentDirectory directory;
  private final RiskEngine risk;
  private final FairValueEngine fairValue;
  private final SpreadModel spread;
  private final HitRateModel hitRate;
  private final TierTableControl tiers;
  private final ToxicityTable toxicity;
  private final AxeSource axes;
  private final HedgeUniverse hedgeUniverse;
  private final HedgeCostModel hedgeCosts;
  private final KeyRateCovariance covariance;
  private final InputHealth health;
  private final OutboundSink gateway;
  private final Clock clock;
  private final QuotingConfig config;

  private final double[] volBp = new double[CurrencyCode.COUNT];
  private final RfqOutcome outcome = new RfqOutcome();
  private final Dv01Vector book = new Dv01Vector(CurrencyCode.USD);
  private final double[] p = new double[K];
  private final double[] sp = new double[K];
  private final double[] u = new double[K];

  // strategy path (Round 5); each part is null when its switch is off
  private final StrategyConfig strategy;
  private final boolean strategyPricing;
  private final CoverModel cover;
  private final MarkoutStore markouts;
  private final HedgePlanning quotePlanner;
  private final WorkingHedges working;
  private final TierPriors priors;
  private final double[] delta = new double[K];
  private final Dv01Vector[] tradeLadder = new Dv01Vector[CurrencyCode.COUNT];
  private int lastCell = -1;
  private double lastFill;
  private double lastBreakEven;
  private final int[] sCell;
  private final int[][] hInstrument;
  private final int[][] hClient;
  private final boolean[][] hClientBuys;
  private final boolean[][] hWeBuy;
  private final double[][] hMid;
  private final long[][] hDue;
  private final int[] hHead;
  private final int[] hCount;
  private long markoutsDropped;

  // live quotes
  private final int capacity;
  private final boolean[] used;
  private final boolean[] cancelling;
  private final long[] sQuoteId;
  private final long[] sRfqId;
  private final int[] sVenue;
  private final int[] sInstrument;
  private final int[] sClient;
  private final boolean[] sBuy;
  private final long[] sQty;
  private final long[] sValidUntil;
  private final long[] sOldest;
  private final double[] sBase;
  private final double[] sDistance;
  private final long[] sPrice;
  private final double[] sFair;
  private final double[] sClientDv01;
  private final int[] held;
  private final int[] heldPos;
  private int heldCount;
  private boolean pulledForRelease;
  private RfqRecordListener recorder = RfqRecordListener.NONE;
  private final int[] freeSlots;
  private int freeTop;
  private final Long2LongHashMap quoteSlot;
  private long nextExpiry = Long.MAX_VALUE;
  private long nextStale = Long.MAX_VALUE;
  private long nextQuoteId;
  private boolean pulledForHealth;

  // duplicates and throttles
  private final LongHashSet seen;
  private final long[] seenRing;
  private int seenHead;
  private int seenCount;
  private final Long2LongHashMap lastQuoteTime;

  // markouts
  private final int[] mInstrument = new int[MARKOUTS];
  private final int[] mClient = new int[MARKOUTS];
  private final boolean[] mBuy = new boolean[MARKOUTS];
  private final double[] mPrice = new double[MARKOUTS];
  private final long[] mDue = new long[MARKOUTS];
  private int mHead;
  private int mCount;

  // encoding
  private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final QuoteEncoder quote = new QuoteEncoder();
  private final QuoteWithdrawEncoder cancel = new QuoteWithdrawEncoder();

  // statistics
  private long rfqs;
  private long quotesSent;
  private long passes;
  private long fills;
  private long expiries;
  private long pulls;
  private long unmatchedResponses;
  private long lateFills;
  private final long[] tQuoteId = new long[TOMBSTONES];
  private final int[] tInstrument = new int[TOMBSTONES];
  private final boolean[] tBuy = new boolean[TOMBSTONES];
  private final long[] tQty = new long[TOMBSTONES];
  private final long[] tRfqId = new long[TOMBSTONES];
  private final long[] tPrice = new long[TOMBSTONES];
  private final int[] tClient = new int[TOMBSTONES];
  private int tHead;
  private double coverDistanceBp;
  private long coverObservations;
  private long coversRecorded;
  private long wonBehindCover;
  private double wonBehindCoverBp;
  private long fairMovePulls;
  private long priceMismatches;
  private long rejectedDones;
  private long emptyDones;

  // housekeeping the engine's poll drives for the quoters that share its clock (package, CLOB)
  private static final int MAX_HOOKS = 4;
  private final Housekeeping[] hooks = new Housekeeping[MAX_HOOKS];
  private int hookCount;

  // realised volatility, when a reference instrument is configured (review Q11)
  private final boolean volEstimated;
  private long nextVolSample = Long.MIN_VALUE;
  private final double[] volLast = new double[CurrencyCode.COUNT];
  private final double[] volVariance = new double[CurrencyCode.COUNT];
  private final int[] volSamples = new int[CurrencyCode.COUNT];

  /** Wires an engine; see the class comment for each collaborator's role. */
  public QuotingEngine(
      final InstrumentDirectory directory,
      final RiskEngine risk,
      final FairValueEngine fairValue,
      final SpreadModel spread,
      final TierTableControl tiers,
      final ToxicityTable toxicity,
      final AxeSource axes,
      final HedgeUniverse hedgeUniverse,
      final HedgeCostModel hedgeCosts,
      final KeyRateCovariance covariance,
      final InputHealth health,
      final OutboundSink gateway,
      final Clock clock,
      final QuotingConfig config) {
    this(directory, risk, fairValue, spread, tiers, toxicity, axes, hedgeUniverse, hedgeCosts, covariance, health, gateway,
        clock, config, null, null, null, WorkingHedges.NONE);
  }

  /**
   * Wires an engine with the strategy path's parts (Round 5): the cover model when cover pricing
   * is on, the markout store when the store is on, a planner whose mix prices the quoted hedge
   * cost, and the working hedges the reservation price adds to the ladder.
   */
  public QuotingEngine(
      final InstrumentDirectory directory,
      final RiskEngine risk,
      final FairValueEngine fairValue,
      final SpreadModel spread,
      final TierTableControl tiers,
      final ToxicityTable toxicity,
      final AxeSource axes,
      final HedgeUniverse hedgeUniverse,
      final HedgeCostModel hedgeCosts,
      final KeyRateCovariance covariance,
      final InputHealth health,
      final OutboundSink gateway,
      final Clock clock,
      final QuotingConfig config,
      final CoverModel coverModel,
      final MarkoutStore markoutStore,
      final HedgePlanning quoteHedgePlanner,
      final WorkingHedges workingHedges) {
    this.directory = directory;
    this.risk = risk;
    this.fairValue = fairValue;
    this.spread = spread;
    this.hitRate = spread.hitRate();
    this.tiers = tiers;
    this.toxicity = toxicity;
    this.axes = axes;
    this.hedgeUniverse = hedgeUniverse;
    this.hedgeCosts = hedgeCosts;
    this.covariance = covariance;
    this.health = health;
    this.gateway = gateway;
    this.clock = clock;
    this.config = config;
    Arrays.fill(volBp, config.baselineVolBp);
    capacity = config.maxLiveQuotes;
    used = new boolean[capacity];
    cancelling = new boolean[capacity];
    sQuoteId = new long[capacity];
    sRfqId = new long[capacity];
    sVenue = new int[capacity];
    sInstrument = new int[capacity];
    sClient = new int[capacity];
    sBuy = new boolean[capacity];
    sQty = new long[capacity];
    sValidUntil = new long[capacity];
    sOldest = new long[capacity];
    sBase = new double[capacity];
    sDistance = new double[capacity];
    sPrice = new long[capacity];
    sFair = new double[capacity];
    sClientDv01 = new double[capacity];
    held = new int[capacity];
    heldPos = new int[capacity];
    freeSlots = new int[capacity];
    for (int i = 0; i < capacity; i++) {
      freeSlots[i] = capacity - 1 - i;
    }
    freeTop = capacity;
    quoteSlot = new Long2LongHashMap(capacity * 4, 0.5f, -1L);
    seen = new LongHashSet(config.duplicateWindow * 4);
    seenRing = new long[config.duplicateWindow];
    lastQuoteTime = new Long2LongHashMap(Math.max(64, config.duplicateWindow * 2), 0.5f, Long.MIN_VALUE);
    volEstimated = config.volatilityEstimated();
    Arrays.fill(volLast, Double.NaN);
    strategy = config.strategy;
    if (strategy.coverPricing && coverModel == null) {
      throw new IllegalArgumentException("cover pricing is on but no cover model was given");
    }
    if (strategy.markoutStore && markoutStore == null) {
      throw new IllegalArgumentException("the markout store is on but no store was given");
    }
    strategyPricing = strategy.coverPricing || strategy.reservationPrice || strategy.markoutStore || strategy.tickRounding;
    cover = strategy.coverPricing ? coverModel : null;
    markouts = strategy.markoutStore ? markoutStore : null;
    quotePlanner = quoteHedgePlanner;
    working = workingHedges == null ? WorkingHedges.NONE : workingHedges;
    priors = strategy.tierPriorsOrCalibrated();
    for (int c = 0; c < CurrencyCode.COUNT; c++) {
      tradeLadder[c] = new Dv01Vector(CurrencyCode.ofIndex(c));
    }
    sCell = new int[capacity];
    final int rings = markouts == null ? 0 : MarkoutStore.H;
    final int ring = markouts == null ? 0 : MARKOUTS;
    hInstrument = new int[rings][ring];
    hClient = new int[rings][ring];
    hClientBuys = new boolean[rings][ring];
    hWeBuy = new boolean[rings][ring];
    hMid = new double[rings][ring];
    hDue = new long[rings][ring];
    hHead = new int[rings];
    hCount = new int[rings];
  }

  /** Work done on every {@link #poll}; must not allocate. */
  @FunctionalInterface
  public interface Housekeeping {
    void poll(long nowNanos);
  }

  /**
   * Runs {@code hook} at the end of every poll, so a quoter that holds reservations to a time to
   * live releases them on time and not only when its next request arrives.
   */
  public void attach(final Housekeeping hook) {
    if (hookCount == MAX_HOOKS) {
      throw new IllegalStateException("more than " + MAX_HOOKS + " housekeeping hooks");
    }
    hooks[hookCount++] = hook;
  }

  /**
   * Receives every RFQ decision and every response (PNL_AUDIT item 3). Called on the event loop;
   * an implementation must not allocate or block.
   */
  public interface RfqRecordListener {
    /** No recording. */
    RfqRecordListener NONE =
        new RfqRecordListener() {
          @Override
          public void onDecision(final RfqOutcome outcome) {}

          @Override
          public void onResult(
              final long rfqId, final long quoteId, final QuoteStatus status, final long ourPrice,
              final long coverPrice, final double coverGapBp, final int rank) {}
        };

    /**
     * A Done whose price differs from the one we quoted by more than the tolerance. {@code
     * againstUsBp} is positive when the venue's price is worse for us; {@code booked} says whether
     * the fill went to the risk engine. Does nothing unless overridden.
     */
    default void onPriceMismatch(
        final long rfqId, final long quoteId, final long quotedPrice, final long tradedPrice,
        final double againstUsBp, final boolean booked) {}

    /** One RFQ's decision, pass or quote; {@code outcome} is reused after the call returns. */
    void onDecision(RfqOutcome outcome);

    /**
     * The venue's outcome for one of our quotes, matched or booked late. {@code coverGapBp} is how
     * far our price sat behind the cover for the client (positive: the cover was better for the
     * client than ours; NaN without a cover). {@code rank} is our price rank as far as the cover
     * shows it: 1 when our price was at least as good as the cover, 2 when the cover was better, 1
     * for a Done without a cover, 0 when unknown.
     */
    void onResult(long rfqId, long quoteId, QuoteStatus status, long ourPrice, long coverPrice, double coverGapBp, int rank);
  }

  /** Sets the recorder of RFQ decisions and responses; {@code null} turns recording off. */
  public void recorder(final RfqRecordListener listener) {
    recorder = listener == null ? RfqRecordListener.NONE : listener;
  }

  /** Sets the daily realised volatility of a currency's rates, bp (supplied by market state). */
  public void realisedVolatility(final CurrencyCode currency, final double dailyBp) {
    volBp[currency.index()] = dailyBp;
  }

  // ---------------------------------------------------------------- RFQ path

  /** Decodes and handles a QuoteRequest; returns the decision. */
  public RfqDecision onQuoteRequest(final QuoteRequestDecoder d, final long journalSeqNo) {
    return onQuoteRequest(
        d.venueId(),
        d.rfqId(),
        d.clientId(),
        d.instrumentId(),
        d.side(),
        d.quantity(),
        d.dealerCount(),
        d.expiryNanos(),
        journalSeqNo);
  }

  /** Handles one RFQ. {@code clientSide} is the client's side. Read details from {@link #outcome}. */
  public RfqDecision onQuoteRequest(
      final int venueId,
      final long rfqId,
      final int clientId,
      final int instrumentId,
      final Side clientSide,
      final long quantity,
      final int dealerCount,
      final long expiryNanos,
      final long journalSeqNo) {
    final long now = clock.epochNanos();
    poll(now);
    rfqs++;
    outcome.reset();
    outcome.requestedQuantity = quantity;
    outcome.venueId = venueId;
    outcome.rfqId = rfqId;
    outcome.clientId = clientId;
    outcome.instrumentId = instrumentId;
    final boolean weBuy = clientSide == Side.Sell;
    outcome.ourSide = weBuy ? Side.Buy : Side.Sell;
    if (!remember(venueId, rfqId)) {
      return pass(PassReason.DUPLICATE);
    }
    if (!risk.killSwitch().isActive()) {
      return pass(PassReason.KILLED);
    }
    if (!health.healthy(now)) {
      return pass(PassReason.UNHEALTHY);
    }
    if (config.inReleasePull(now)) {
      return pass(PassReason.RELEASE_WINDOW);
    }
    final Instrument inst = directory.byId(instrumentId);
    if (inst == null || quantity <= 0 || risk.quotePerBp(instrumentId) <= 0.0) {
      return pass(PassReason.UNKNOWN_INSTRUMENT);
    }
    if (expiryNanos != 0 && expiryNanos <= now) {
      return pass(PassReason.EXPIRED_REQUEST);
    }
    final ClientTier tier = tiers.active().tierOf(clientId);
    if (tier.blocked()) {
      return pass(PassReason.CLIENT_BLOCKED);
    }
    final long throttleKey = ((long) clientId << 32) | (instrumentId & 0xFFFFFFFFL) | (weBuy ? 0L : 1L << 63);
    final long last = lastQuoteTime.get(throttleKey);
    if (last != Long.MIN_VALUE && now - last < config.requoteThrottleNanos) {
      return pass(PassReason.THROTTLED);
    }
    if (!fairValue.compute(inst, now)) {
      return pass(PassReason.NO_FAIR_VALUE);
    }
    if (now - fairValue.oldestNanos() > config.maxInputAgeNanos) {
      return pass(PassReason.STALE_INPUT);
    }
    final double fair = fairValue.fair();
    outcome.fair = fair;

    // size
    long qty = Math.min(quantity, tier.maxQuantity());
    qty = Math.min(qty, risk.quoteHeadroom(clientId, instrumentId, outcome.ourSide));
    qty -= qty % config.minQuoteQuantity;
    if (qty <= 0) {
      return pass(PassReason.NO_RISK_CAPACITY);
    }
    if (freeTop == 0) {
      return pass(PassReason.BOOK_FULL);
    }
    outcome.quantity = qty;

    // spread
    final double qpb = risk.quotePerBp(instrumentId);
    final double unitPar = Math.abs(risk.unitParallelPv01(instrumentId));
    final double qtyMajor = inst.major(qty);
    final int ccy = inst.currency().index();
    final int dealers = dealerCount > 0 ? dealerCount : DEFAULT_DEALERS;
    final double base;
    final double distance;
    final long price;
    lastCell = -1;
    if (strategyPricing) {
      base =
          hitRate.base(
              (double) qty / inst.typicalQuantity(), inst.tenorYears(), liquidSession(inst.currency(), now), dealers);
      price = strategyPrice(inst, clientId, tier, weBuy, qty, qtyMajor, unitPar, qpb, fair, dealers, now);
      if (price == NO_PRICE) {
        return pass(PassReason.NEGATIVE_EDGE);
      }
      distance = outcome.distanceBp;
      outcome.winProbability =
          cover != null ? cover.winProbability(lastCell, lastFill, distance) : hitRate.winProbability(base, distance);
    } else {
      final double volRatio = config.baselineVolBp > 0.0 ? volBp[ccy] / config.baselineVolBp : 1.0;
      final double cost =
          toxicity.adverseBp(clientId)
              + config.winnersCurseBp * Math.sqrt(2.0 * Math.log(Math.max(1, dealers))) * volRatio
              + hedgeCostBp(inst, qtyMajor * unitPar);
      base =
          hitRate.base(
              (double) qty / inst.typicalQuantity(), inst.tenorYears(), liquidSession(inst.currency(), now), dealers);
      if (cost >= config.maxHalfSpreadBp) {
        // E(s) = P(s) (s - c) is not positive for any s up to the cap: quoting would lose on average
        return pass(PassReason.NEGATIVE_EDGE);
      }
      final double optimal = spread.optimal(base, cost, config.maxHalfSpreadBp);
      final double half =
          config.widenAfterClamp
              ? widen(clamp(optimal * tier.spreadMultiplier(), config.minHalfSpreadBp, config.maxHalfSpreadBp), inst.currency(), now)
              : clamp(widen(optimal, inst.currency(), now) * tier.spreadMultiplier(), config.minHalfSpreadBp, config.maxHalfSpreadBp);

      // skew: kappa * u' Sigma p / |U|, size term 0.5 kappa q u' Sigma u / |U|
      risk.ladder(inst.currency(), book);
      book.copyTo(p);
      for (int a = 0; a < K; a++) {
        u[a] = risk.unitPv01(instrumentId, a);
        double s = 0.0;
        for (int b = 0; b < K; b++) {
          s += covariance.get(a, b) * p[b];
        }
        sp[a] = s;
      }
      double usp = 0.0;
      for (int a = 0; a < K; a++) {
        usp += u[a] * sp[a];
      }
      final double skew = clamp(config.kappa * usp / unitPar, -config.maxSkewBp, config.maxSkewBp);
      final double sizeTerm = Math.min(0.5 * config.kappa * qtyMajor * covariance.quadratic(u, u) / unitPar, config.maxSkewBp);
      final double axe = Math.min(axes.axeBp(instrumentId, outcome.ourSide), config.maxSkewBp);
      distance =
          Math.max(-config.maxImproveBp, half + (weBuy ? skew : -skew) + sizeTerm - axe);
      price = weBuy ? Decimal9.floor(fair - distance * qpb) : Decimal9.ceil(fair + distance * qpb);

      outcome.costBp = cost;
      outcome.halfSpreadBp = half;
      outcome.skewBp = skew;
      outcome.sizeTermBp = sizeTerm;
      outcome.axeBp = axe;
      outcome.distanceBp = distance;
      outcome.winProbability = hitRate.winProbability(base, distance);
      outcome.price = price;
    }

    // risk check, reserve, send
    final int r =
        weBuy
            ? risk.checkQuote(clientId, instrumentId, price, qty, 0L, 0L, journalSeqNo)
            : risk.checkQuote(clientId, instrumentId, 0L, 0L, price, qty, journalSeqNo);
    if (r != RiskEngine.OK) {
      outcome.limitRejection = r;
      return pass(PassReason.RISK_REJECT);
    }
    risk.reserve(instrumentId, weBuy ? qty : 0L, weBuy ? 0L : qty);
    final double clientDv01 = qtyMajor * unitPar;
    risk.reserveClient(clientId, clientDv01);
    long validUntil = now + timeToLive(inst.currency());
    if (expiryNanos != 0) {
      validUntil = Math.min(validUntil, expiryNanos);
    }
    final long quoteId = ++nextQuoteId;
    final boolean firm = tier.firm();
    quote
        .wrapAndApplyHeader(buffer, 0, header)
        .venueId(venueId)
        .rfqId(rfqId)
        .quoteId(quoteId)
        .instrumentId(instrumentId)
        .bidQuantity(weBuy ? qty : 0L)
        .askQuantity(weBuy ? 0L : qty)
        .referenceNanos(fairValue.newestNanos())
        .validUntilNanos(firm ? validUntil : 0L)
        .sentNanos(now);
    quote.bidPrice().mantissa(weBuy ? price : 0L);
    quote.askPrice().mantissa(weBuy ? 0L : price);
    final int slot = freeSlots[--freeTop];
    used[slot] = true;
    cancelling[slot] = false;
    sQuoteId[slot] = quoteId;
    sRfqId[slot] = rfqId;
    sVenue[slot] = venueId;
    sInstrument[slot] = instrumentId;
    sClient[slot] = clientId;
    sBuy[slot] = weBuy;
    sQty[slot] = qty;
    sValidUntil[slot] = validUntil;
    sOldest[slot] = fairValue.oldestNanos();
    sBase[slot] = base;
    sDistance[slot] = distance;
    sPrice[slot] = price;
    sFair[slot] = fair;
    sClientDv01[slot] = clientDv01;
    sCell[slot] = lastCell;
    heldPos[slot] = heldCount;
    held[heldCount++] = slot;
    quoteSlot.put(quoteId, slot);
    nextExpiry = Math.min(nextExpiry, validUntil);
    nextStale = Math.min(nextStale, sOldest[slot] + config.maxInputAgeNanos);
    lastQuoteTime.put(throttleKey, now);
    gateway.send(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + quote.encodedLength());
    quotesSent++;
    outcome.quoteId = quoteId;
    outcome.validUntilNanos = validUntil;
    outcome.decision = firm ? RfqDecision.FIRM : RfqDecision.SUBJECT;
    recorder.onDecision(outcome);
    return outcome.decision;
  }

  /** Decodes and handles a QuoteResponse. */
  public void onQuoteResponse(final QuoteResponseDecoder d) {
    onQuoteResponse(d.quoteId(), d.status(), d.tradedPrice().mantissa(), d.tradedQuantity(), d.coverPrice().mantissa());
  }

  /**
   * The venue's outcome for one of our quotes. Done books the fill (our side from the quote) and
   * teaches the hit-rate model one win; a disclosed cover worse for the client than our price adds
   * its distance to the cover statistics, but is not a second observation (one RFQ, one label;
   * DR-R2R-08). TradedAway teaches a loss. Every outcome releases the quote's reservation and frees
   * it, and goes to the recorder with the cover gap and our rank.
   *
   * <p>A Done is checked before it is booked: for zero quantity it is counted and nothing else
   * happens, and at a price other than the quoted one it is flagged (see the class comment).
   */
  public void onQuoteResponse(
      final long quoteId, final QuoteStatus status, final long tradedPrice, final long tradedQuantity, final long coverPrice) {
    final int slot = (int) quoteSlot.get(quoteId);
    if (slot < 0) {
      if (status == QuoteStatus.Done && bookLateFill(quoteId, tradedPrice, tradedQuantity)) {
        return;
      }
      unmatchedResponses++;
      return;
    }
    final int instrumentId = sInstrument[slot];
    record(sRfqId[slot], quoteId, status, sBuy[slot], sPrice[slot], coverPrice, instrumentId);
    if (status == QuoteStatus.Done) {
      final long qty = Math.min(Math.max(tradedQuantity, 0L), sQty[slot]);
      release(slot);
      if (qty == 0L) {
        // nothing traded: not a fill, and not a win the hit-rate model should learn from
        emptyDones++;
      } else if (accept(sRfqId[slot], quoteId, sBuy[slot], sPrice[slot], tradedPrice, instrumentId)) {
        fills++;
        risk.onFill(instrumentId, sBuy[slot] ? qty : -qty, tradedPrice);
        risk.onClientFill(sClient[slot], sClientDv01[slot] * ((double) qty / sQty[slot]));
        hitRate.update(sBase[slot], sDistance[slot], true);
        if (coverPrice != 0L) {
          // the cover is the client's next best price: below our bid when we buy, above our offer
          // when we sell; one on the wrong side of the trade is inconsistent and not recorded
          final double signed =
              Decimal9.toDouble(sBuy[slot] ? tradedPrice - coverPrice : coverPrice - tradedPrice)
                  / risk.quotePerBp(instrumentId);
          if (signed > 0.0) {
            coverDistanceBp += signed;
            coverObservations++;
            if (config.coverLearning) {
              spread.observeCoverGap(signed);
            }
            if (cover != null && sCell[slot] >= 0) {
              // we were best, so the cover is the best competing quote: observed exactly
              cover.observeExact(sCell[slot], sDistance[slot] + signed);
            }
          }
        } else if (cover != null && sCell[slot] >= 0) {
          // no cover shown: the best competing quote was further from fair than ours
          cover.observeAbove(sCell[slot], sDistance[slot]);
        }
        scheduleMarkout(slot);
        if (markouts != null) {
          scheduleMarkouts(slot);
        }
      }
    } else {
      release(slot);
      if (status == QuoteStatus.TradedAway) {
        hitRate.update(sBase[slot], sDistance[slot], false);
        if (cover != null && sCell[slot] >= 0) {
          learnFromLoss(slot, coverPrice);
        }
      }
    }
    free(slot);
  }

  /**
   * Housekeeping at {@code nowNanos}: expire quotes whose time to live has passed, pull every quote
   * on unhealthy input, pull quotes priced from inputs now older than the maximum age, and measure
   * due markouts.
   */
  public void poll(final long nowNanos) {
    if (config.inReleasePull(nowNanos)) {
      if (!pulledForRelease) {
        pullAll(CancelReason.AdverseSignal, nowNanos);
        pulledForRelease = true;
      }
    } else {
      pulledForRelease = false;
    }
    if (config.fairMoveWithdrawBp > 0.0 || config.fairMoveWithdrawFraction > 0.0) {
      pullOnFairMove(nowNanos);
    }
    if (nowNanos >= nextExpiry || nowNanos >= nextStale) {
      long expiry = Long.MAX_VALUE;
      long stale = Long.MAX_VALUE;
      for (int s = 0; s < capacity; s++) {
        if (!used[s]) {
          continue;
        }
        if (sValidUntil[s] <= nowNanos) {
          expiries++;
          release(s);
          remember(s);
          free(s);
          continue;
        }
        if (!cancelling[s] && nowNanos - sOldest[s] > config.maxInputAgeNanos) {
          pull(s, CancelReason.StaleInput, nowNanos);
        }
        expiry = Math.min(expiry, sValidUntil[s]);
        if (!cancelling[s]) {
          stale = Math.min(stale, sOldest[s] + config.maxInputAgeNanos);
        }
      }
      nextExpiry = expiry;
      nextStale = stale;
    }
    if (health.healthy(nowNanos)) {
      pulledForHealth = false;
    } else if (!pulledForHealth) {
      pullAll(health.reason(), nowNanos);
      pulledForHealth = true;
    }
    while (mCount > 0 && mDue[(mHead - mCount + MARKOUTS) % MARKOUTS] <= nowNanos) {
      measureMarkout((mHead - mCount + MARKOUTS) % MARKOUTS, nowNanos);
      mCount--;
    }
    if (markouts != null) {
      pollMarkouts(nowNanos);
    }
    if (volEstimated && nowNanos >= nextVolSample) {
      sampleVolatility(nowNanos);
    }
    for (int h = 0; h < hookCount; h++) {
      hooks[h].poll(nowNanos);
    }
  }

  @Override
  public void onKill(final KillReason reason, final long epochNanos) {
    pullAll(CancelReason.KillSwitch, epochNanos);
  }

  /** Withdraws every live quote with a QuoteWithdraw. */
  public void pullAll(final CancelReason reason, final long nowNanos) {
    for (int s = 0; s < capacity; s++) {
      if (used[s] && !cancelling[s]) {
        pull(s, reason, nowNanos);
      }
    }
  }

  // ---------------------------------------------------------------- reads

  /** The last RFQ's outcome (reused object). */
  public RfqOutcome outcome() {
    return outcome;
  }

  /** Quotes held: live plus withdrawn but not yet expired or answered. */
  public int heldQuotes() {
    return capacity - freeTop;
  }

  /** Quotes live and not withdrawn. */
  public int liveQuotes() {
    int n = 0;
    for (int s = 0; s < capacity; s++) {
      if (used[s] && !cancelling[s]) {
        n++;
      }
    }
    return n;
  }

  /**
   * Visits every live (not withdrawn) quote; for the chaos test's invariant checks. Allocates
   * nothing but is not for the hot path.
   */
  public void forEachLive(final LiveQuoteVisitor visitor) {
    for (int s = 0; s < capacity; s++) {
      if (used[s] && !cancelling[s]) {
        visitor.visit(sQuoteId[s], sInstrument[s], sBuy[s], sQty[s], sValidUntil[s], sOldest[s]);
      }
    }
  }

  /** Visits every held quote including withdrawn ones; the reservations they hold. */
  public void forEachHeld(final LiveQuoteVisitor visitor) {
    for (int s = 0; s < capacity; s++) {
      if (used[s]) {
        visitor.visit(sQuoteId[s], sInstrument[s], sBuy[s], sQty[s], sValidUntil[s], sOldest[s]);
      }
    }
  }

  /** Callback for {@link #forEachLive}. */
  @FunctionalInterface
  public interface LiveQuoteVisitor {
    void visit(long quoteId, int instrumentId, boolean weBuy, long quantity, long validUntilNanos, long oldestInputNanos);
  }

  public long rfqs() {
    return rfqs;
  }

  public long quotesSent() {
    return quotesSent;
  }

  public long passes() {
    return passes;
  }

  public long fills() {
    return fills;
  }

  public long expiries() {
    return expiries;
  }

  public long pulls() {
    return pulls;
  }

  /** Done responses booked for quotes that had already expired here. */
  public long lateFills() {
    return lateFills;
  }

  /** Quotes withdrawn because fair value moved against them by more than the threshold. */
  public long fairMovePulls() {
    return fairMovePulls;
  }

  /** Done responses whose price differed from the quoted one by more than the tolerance. */
  public long priceMismatches() {
    return priceMismatches;
  }

  /** Mismatched Done responses that were not booked ({@code rejectMismatchedDone}). */
  public long rejectedDones() {
    return rejectedDones;
  }

  /** Done responses for zero quantity: not booked and not taught to the hit-rate model. */
  public long emptyDones() {
    return emptyDones;
  }

  /** Daily realised volatility in use for a currency, bp. */
  public double realisedVolatility(final CurrencyCode currency) {
    return volBp[currency.index()];
  }

  /** Responses that carried a cover price. */
  public long coversRecorded() {
    return coversRecorded;
  }

  /** Done responses whose cover was better for the client than our price. */
  public long wonBehindCover() {
    return wonBehindCover;
  }

  /** Mean distance our price sat behind the cover on the Dones that won from behind it, bp. */
  public double meanWonBehindCoverBp() {
    return wonBehindCover == 0 ? 0.0 : wonBehindCoverBp / wonBehindCover;
  }

  /** Responses for quotes the engine no longer holds. */
  public long unmatchedResponses() {
    return unmatchedResponses;
  }

  /** Mean distance between our winning price and the disclosed cover, bp. */
  public double meanCoverDistanceBp() {
    return coverObservations == 0 ? 0.0 : coverDistanceBp / coverObservations;
  }

  public HitRateModel hitRate() {
    return hitRate;
  }

  /** The cover model of the strategy path, or null when cover pricing is off. */
  public CoverModel cover() {
    return cover;
  }

  /** Strategy-path markouts not scheduled because their horizon's ring was full. */
  public long markoutsDropped() {
    return markoutsDropped;
  }

  /** The client markout store of the strategy path, or null when it is off. */
  public MarkoutStore markouts() {
    return markouts;
  }

  public ToxicityTable toxicity() {
    return toxicity;
  }

  public QuotingConfig config() {
    return config;
  }

  public SpreadModel spread() {
    return spread;
  }

  public Clock clock() {
    return clock;
  }

  /**
   * Hedge cost of a trade with parallel PV01 {@code pv01} (absolute), in bp: the cheapest single
   * hedge instrument in the trade's currency, at its linear and impact cost.
   */
  public double hedgeCostBp(final Instrument inst, final double pv01) {
    if (hedgeUniverse == null || hedgeCosts == null || pv01 <= 0.0) {
      return 0.0;
    }
    double best = Double.MAX_VALUE;
    for (int j = 0; j < hedgeUniverse.size(); j++) {
      final int id = hedgeUniverse.instrumentId(j);
      final Instrument h = directory.byId(id);
      if (h == null || h.currency() != inst.currency()) {
        continue;
      }
      final double lotPv01 = Math.abs(risk.unitParallelPv01(id)) * h.major(hedgeUniverse.lotMinor(j));
      if (lotPv01 <= 0.0) {
        continue;
      }
      best = Math.min(best, hedgeCosts.cost(id, pv01 / lotPv01));
    }
    return best == Double.MAX_VALUE ? 0.0 : best / pv01;
  }

  /**
   * Half-spread after the event and release multiplier and the volatility widening; shared with
   * the package quoter.
   */
  double widen(final double optimal, final CurrencyCode currency, final long nowNanos) {
    return optimal * config.eventMultiplier(nowNanos)
        + Math.max(0.0, volBp[currency.index()] - config.baselineVolBp) * config.volWideningPerBp;
  }

  InputHealth health() {
    return health;
  }

  // ---------------------------------------------------------------- internals

  private RfqDecision pass(final PassReason reason) {
    passes++;
    outcome.decision = RfqDecision.PASS;
    outcome.reason = reason;
    recorder.onDecision(outcome);
    return RfqDecision.PASS;
  }

  private void record(
      final long rfqId, final long quoteId, final QuoteStatus status, final boolean weBuy, final long ourPrice,
      final long coverPrice, final int instrumentId) {
    double gap = Double.NaN;
    int rank = status == QuoteStatus.Done ? 1 : 0;
    if (coverPrice != 0L) {
      // client-worse distance of ours from the cover: a higher bid or a lower offer is better for the client
      gap = Decimal9.toDouble(weBuy ? coverPrice - ourPrice : ourPrice - coverPrice) / risk.quotePerBp(instrumentId);
      rank = gap > 0.0 ? 2 : 1;
      coversRecorded++;
      if (status == QuoteStatus.Done && gap > 0.0) {
        wonBehindCover++;
        wonBehindCoverBp += gap;
      }
    }
    recorder.onResult(rfqId, quoteId, status, ourPrice, coverPrice, gap, rank);
  }

  /** Withdraws every live quote whose fair value has moved against it by more than the threshold. */
  private void pullOnFairMove(final long nowNanos) {
    for (int k = 0; k < heldCount; k++) {
      final int s = held[k];
      if (cancelling[s] || sValidUntil[s] <= nowNanos) {
        continue;
      }
      final Instrument inst = directory.byId(sInstrument[s]);
      if (inst == null || !fairValue.compute(inst, nowNanos)) {
        continue;
      }
      // against us: fair falls under our bid's fair or rises over our offer's, so the client gains by trading
      final double move =
          (sBuy[s] ? sFair[s] - fairValue.fair() : fairValue.fair() - sFair[s]) / risk.quotePerBp(sInstrument[s]);
      final double threshold =
          config.fairMoveWithdrawFraction > 0.0
              ? config.fairMoveWithdrawFraction * Math.max(sDistance[s], config.minHalfSpreadBp)
              : config.fairMoveWithdrawBp;
      if (move > threshold) {
        fairMovePulls++;
        pull(s, CancelReason.AdverseSignal, nowNanos);
      }
    }
  }

  private boolean remember(final int venueId, final long rfqId) {
    final long key = ((long) venueId << 48) ^ rfqId;
    if (seen.contains(key)) {
      return false;
    }
    if (seenCount == seenRing.length) {
      seen.remove(seenRing[seenHead]);
    } else {
      seenCount++;
    }
    seenRing[seenHead] = key;
    seenHead = (seenHead + 1) % seenRing.length;
    seen.add(key);
    return true;
  }

  private void pull(final int s, final CancelReason reason, final long nowNanos) {
    cancelling[s] = true;
    pulls++;
    cancel
        .wrapAndApplyHeader(buffer, 0, header)
        .venueId(sVenue[s])
        .rfqId(sRfqId[s])
        .quoteId(sQuoteId[s])
        .instrumentId(sInstrument[s])
        .reason(reason)
        .sentNanos(nowNanos);
    risk.countExemptMessage();
    gateway.send(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + cancel.encodedLength());
  }

  private void release(final int s) {
    risk.release(sInstrument[s], sBuy[s] ? sQty[s] : 0L, sBuy[s] ? 0L : sQty[s]);
    risk.releaseClient(sClient[s], sClientDv01[s]);
  }

  /**
   * The price check at acceptance: true when the Done is to be booked. A venue trades an RFQ at
   * the price we quoted; a Done at another price is a venue or mapping error, and is never booked
   * without being counted and reported.
   */
  private boolean accept(
      final long rfqId, final long quoteId, final boolean weBuy, final long quoted, final long traded,
      final int instrumentId) {
    if (traded == quoted) {
      return true;
    }
    final double againstUsBp =
        Decimal9.toDouble(weBuy ? traded - quoted : quoted - traded) / risk.quotePerBp(instrumentId);
    if (Math.abs(againstUsBp) <= config.acceptPriceToleranceBp) {
      return true;
    }
    priceMismatches++;
    final boolean booked = !config.rejectMismatchedDone;
    if (!booked) {
      rejectedDones++;
    }
    recorder.onPriceMismatch(rfqId, quoteId, quoted, traded, againstUsBp, booked);
    return booked;
  }

  /**
   * One sample of each configured reference instrument: the squared move of its fair value since
   * the last sample, in bp, goes into an exponentially weighted variance, which is scaled to a
   * trading day. A reference with no fresh fair value skips the sample and restarts the series.
   */
  private void sampleVolatility(final long nowNanos) {
    nextVolSample = nowNanos + config.volSampleNanos;
    for (int c = 0; c < CurrencyCode.COUNT; c++) {
      final int id = config.volReference[c];
      final Instrument inst = id == 0 ? null : directory.byId(id);
      if (inst == null) {
        continue;
      }
      if (!fairValue.compute(inst, nowNanos) || nowNanos - fairValue.oldestNanos() > config.maxInputAgeNanos) {
        volLast[c] = Double.NaN;
        continue;
      }
      final double fair = fairValue.fair();
      if (!Double.isNaN(volLast[c])) {
        final double bp = (fair - volLast[c]) / risk.quotePerBp(id);
        volVariance[c] =
            volSamples[c] == 0 ? bp * bp : config.volDecay * volVariance[c] + (1.0 - config.volDecay) * bp * bp;
        volSamples[c]++;
        if (volSamples[c] >= config.volWarmupSamples) {
          final double daily = Math.sqrt(volVariance[c] * ((double) config.volDayNanos / config.volSampleNanos));
          // floored at the baseline: realised volatility widens a quote, it never tightens one
          volBp[c] = Math.max(config.baselineVolBp, daily);
        }
      }
      volLast[c] = fair;
    }
  }

  private void remember(final int s) {
    tQuoteId[tHead] = sQuoteId[s];
    tInstrument[tHead] = sInstrument[s];
    tBuy[tHead] = sBuy[s];
    tQty[tHead] = sQty[s];
    tRfqId[tHead] = sRfqId[s];
    tPrice[tHead] = sPrice[s];
    tClient[tHead] = sClient[s];
    tHead = (tHead + 1) % TOMBSTONES;
  }

  /**
   * A Done for a quote that expired here first: the venue accepted the client's lift before our
   * expiry reached it, so the trade stands and is booked (its reservation was already released).
   * The hit-rate model is not taught, the quote's features being gone. Returns false if the quote
   * is not among the last {@code TOMBSTONES} expiries.
   */
  private boolean bookLateFill(final long quoteId, final long tradedPrice, final long tradedQuantity) {
    for (int k = 0; k < TOMBSTONES; k++) {
      if (tQuoteId[k] == quoteId && tQty[k] > 0) {
        final long qty = Math.min(Math.max(tradedQuantity, 0L), tQty[k]);
        tQty[k] = 0;
        record(tRfqId[k], quoteId, QuoteStatus.Done, tBuy[k], tPrice[k], 0L, tInstrument[k]);
        if (qty == 0L) {
          emptyDones++;
        } else if (accept(tRfqId[k], quoteId, tBuy[k], tPrice[k], tradedPrice, tInstrument[k])) {
          lateFills++;
          fills++;
          risk.onFill(tInstrument[k], tBuy[k] ? qty : -qty, tradedPrice);
          risk.onClientFill(tClient[k], risk.dv01(tInstrument[k], qty));
        }
        return true;
      }
    }
    return false;
  }

  private void free(final int s) {
    final int pos = heldPos[s];
    final int last = held[--heldCount];
    held[pos] = last;
    heldPos[last] = pos;
    quoteSlot.remove(sQuoteId[s]);
    used[s] = false;
    cancelling[s] = false;
    freeSlots[freeTop++] = s;
  }

  /**
   * Schedules a markout from the mid at the trade (PNL_AUDIT item 4): measuring from the traded
   * price would net the captured half-spread against the adverse move and hide it. Falls back to
   * the fair value the quote was priced from when none can be computed now.
   */
  private void scheduleMarkout(final int s) {
    final int i = mHead;
    final long now = clock.epochNanos();
    final Instrument inst = directory.byId(sInstrument[s]);
    mInstrument[i] = sInstrument[s];
    mClient[i] = sClient[s];
    mBuy[i] = sBuy[s];
    mPrice[i] = inst != null && fairValue.compute(inst, now) ? fairValue.fair() : sFair[s];
    mDue[i] = now + config.markoutHorizonNanos;
    mHead = (mHead + 1) % MARKOUTS;
    mCount = Math.min(mCount + 1, MARKOUTS);
  }

  private void measureMarkout(final int i, final long nowNanos) {
    final Instrument inst = directory.byId(mInstrument[i]);
    if (!fairValue.compute(inst, nowNanos)) {
      return;
    }
    final double move = (fairValue.fair() - mPrice[i]) * (mBuy[i] ? 1.0 : -1.0);
    toxicity.record(mClient[i], move / risk.quotePerBp(mInstrument[i]));
  }

  /** {@link #strategyPrice} found no price; never a valid Decimal9 price. */
  private static final long NO_PRICE = Long.MIN_VALUE;

  /**
   * The strategy path's price (Round 5; derivation in docs/research/STRATEGY_V2.md Sections 2 to
   * 4). All quantities in bp of the instrument's quote per unit of client DV01 unless named USD.
   *
   * <pre>
   *   adverse   = client markout at the adverse horizon, shrunk to the tier prior   (markout store)
   *             | toxicity table + winner's curse                                 (otherwise)
   *   H         = cost of the planner's hedge of this trade alone, USD            (or the cheapest single hedge)
   *   inventory = clamp( kappa (delta' Sigma p + delta' Sigma delta / 2), -H, +H ) / DV01   (reservation price)
   *             | legacy skew + size term - axe                                  (otherwise; H then added to cost)
   *   C         = adverse + inventory (+ H / DV01 without the reservation price)
   *   d         = argmax over d &gt;= C of P(win | d - cover) (d - C)            (cover pricing)
   *             | legacy optimiser and clamp, plus the inventory term            (otherwise)
   *   price     = fair -/+ d, on the venue tick in the dealer's favour            (tick rounding)
   * </pre>
   *
   * p is the risk engine's ladder plus the working hedges and delta the trade's own ladder, both
   * USD per bp. Fills the outcome's spread fields and returns the price, or {@link #NO_PRICE} when
   * the default optimiser would pass (cover pricing never passes on cost: it widens).
   */
  private long strategyPrice(
      final Instrument inst, final int clientId, final ClientTier tier, final boolean weBuy, final long qty,
      final double qtyMajor, final double unitPar, final double qpb, final double fair, final int dealers,
      final long now) {
    final int instrumentId = inst.id();
    final int ccy = inst.currency().index();
    final double dv01 = qtyMajor * unitPar;
    final double sign = weBuy ? 1.0 : -1.0;
    final double volRatio = config.baselineVolBp > 0.0 ? volBp[ccy] / config.baselineVolBp : 1.0;
    double adverse =
        markouts != null
            ? markouts.adverseBp(clientId, !weBuy, tier.tier(), strategy.adverseHorizon)
            : toxicity.adverseBp(clientId)
                + config.winnersCurseBp * Math.sqrt(2.0 * Math.log(Math.max(1, dealers))) * volRatio;
    if (cover != null) {
      // around releases and in fast markets informed flow costs more; the default path widens the
      // half-spread for the same reason
      adverse =
          adverse * config.eventMultiplier(now)
              + Math.max(0.0, volBp[ccy] - config.baselineVolBp) * config.volWideningPerBp;
    }
    for (int k = 0; k < K; k++) {
      u[k] = risk.unitPv01(instrumentId, k);
      delta[k] = sign * qtyMajor * u[k];
    }
    final double hedgeUsd = hedgeCostUsd(inst, dv01);
    final double hedgeBp = hedgeUsd / dv01;
    risk.ladder(inst.currency(), book);
    working.addTo(inst.currency(), book);
    book.copyTo(p);
    double usp = 0.0;
    for (int a = 0; a < K; a++) {
      double s = 0.0;
      for (int b = 0; b < K; b++) {
        s += covariance.get(a, b) * p[b];
      }
      sp[a] = s;
      usp += u[a] * s;
    }
    final double skew;
    final double sizeTerm;
    final double axe;
    final double inventory;
    final double breakEven;
    if (strategy.reservationPrice) {
      final double kappa = strategy.riskAversionPerUsd;
      final double skewRaw = kappa * usp / unitPar;
      final double sizeRaw = 0.5 * kappa * qtyMajor * covariance.quadratic(u, u) / unitPar;
      // holding costs at most what hedging costs: past that the desk would hedge instead
      inventory = clamp(sign * skewRaw + sizeRaw, -hedgeBp, hedgeBp);
      skew = clamp(skewRaw, -hedgeBp, hedgeBp);
      sizeTerm = Math.min(sizeRaw, hedgeBp);
      axe = 0.0;
      breakEven = adverse + inventory;
    } else {
      skew = clamp(config.kappa * usp / unitPar, -config.maxSkewBp, config.maxSkewBp);
      sizeTerm = Math.min(0.5 * config.kappa * qtyMajor * covariance.quadratic(u, u) / unitPar, config.maxSkewBp);
      axe = Math.min(axes.axeBp(instrumentId, outcome.ourSide), config.maxSkewBp);
      inventory = (weBuy ? skew : -skew) + sizeTerm - axe;
      breakEven = adverse + hedgeBp + inventory;
    }
    double distance;
    double half = 0.0;
    if (cover != null) {
      lastCell = CoverModel.cell(inst, (double) qty / inst.typicalQuantity(), dealers);
      lastFill = priors.fillProbability(tier.tier());
      distance = cover.optimal(lastCell, lastFill, breakEven);
      outcome.predictedCoverBp = cover.predictedCoverBp(lastCell);
    } else {
      final double costBp = breakEven - inventory;
      if (costBp >= config.maxHalfSpreadBp) {
        outcome.costBp = costBp;
        return NO_PRICE;
      }
      final double base =
          hitRate.base((double) qty / inst.typicalQuantity(), inst.tenorYears(), liquidSession(inst.currency(), now), dealers);
      final double optimal = spread.optimal(base, costBp, config.maxHalfSpreadBp);
      half =
          config.widenAfterClamp
              ? widen(clamp(optimal * tier.spreadMultiplier(), config.minHalfSpreadBp, config.maxHalfSpreadBp), inst.currency(), now)
              : clamp(widen(optimal, inst.currency(), now) * tier.spreadMultiplier(), config.minHalfSpreadBp, config.maxHalfSpreadBp);
      distance = Math.max(-config.maxImproveBp, half + inventory);
    }
    long price = weBuy ? Decimal9.floor(fair - distance * qpb) : Decimal9.ceil(fair + distance * qpb);
    final long tick = strategy.tickRounding ? strategy.ticks.tick(inst) : 0L;
    if (tick > 0L) {
      price = VenueTickTable.roundForDealer(price, tick, weBuy);
      if (cover != null) {
        // the grid point one tick nearer the client, if it is still above break-even and pays more
        final long nearer = weBuy ? price + tick : price - tick;
        final double dNearer = distanceOf(nearer, fair, qpb, weBuy);
        if (dNearer >= breakEven
            && cover.expected(lastCell, lastFill, breakEven, dNearer)
                > cover.expected(lastCell, lastFill, breakEven, distanceOf(price, fair, qpb, weBuy))) {
          price = nearer;
        }
      }
      outcome.distanceTicks = (weBuy ? fair - Decimal9.toDouble(price) : Decimal9.toDouble(price) - fair) / Decimal9.toDouble(tick);
    }
    distance = distanceOf(price, fair, qpb, weBuy);
    if (cover != null) {
      // the margin over break-even, of the price actually quoted
      half = distance - breakEven;
    }
    lastBreakEven = breakEven;
    outcome.costBp = breakEven - inventory;
    outcome.breakEvenBp = breakEven;
    outcome.halfSpreadBp = half;
    outcome.skewBp = skew;
    outcome.sizeTermBp = sizeTerm;
    outcome.axeBp = axe;
    outcome.distanceBp = distance;
    outcome.price = price;
    return price;
  }

  /** Distance of {@code price} from fair on our side, bp. */
  private static double distanceOf(final long price, final double fair, final double qpb, final boolean weBuy) {
    return (weBuy ? fair - Decimal9.toDouble(price) : Decimal9.toDouble(price) - fair) / qpb;
  }

  /**
   * Cost in USD of hedging the trade ladder {@link #delta} on its own: the planner's mix when a
   * quoting planner is wired (factor hedging), otherwise the cheapest single instrument.
   */
  private double hedgeCostUsd(final Instrument inst, final double dv01) {
    if (quotePlanner == null) {
      return hedgeCostBp(inst, dv01) * dv01;
    }
    final Dv01Vector ladder = tradeLadder[inst.currency().index()];
    for (int k = 0; k < K; k++) {
      ladder.set(k, delta[k]);
    }
    quotePlanner.plan(ladder);
    final double cost = quotePlanner.plannedCost();
    return Double.isFinite(cost) ? cost : 0.0;
  }

  /**
   * Time to live of a firm quote. With {@code volatilityTtl} the free option a firm quote gives
   * the client is held constant: its value grows with sigma sqrt(T), so T scales with (baseline /
   * sigma)^2, between {@code minTtlNanos} and the configured time to live.
   */
  private long timeToLive(final CurrencyCode currency) {
    if (!strategy.volatilityTtl) {
      return config.firmTtlNanos;
    }
    final double vol = volBp[currency.index()];
    final double ratio = vol > 0.0 && config.baselineVolBp > 0.0 ? Math.min(1.0, config.baselineVolBp / vol) : 1.0;
    return Math.max(strategy.minTtlNanos, Math.min(config.firmTtlNanos, (long) (config.firmTtlNanos * ratio * ratio)));
  }

  /** A TradedAway teaches the cover model: the cover beat us (exactly, when shown). */
  private void learnFromLoss(final int slot, final long coverPrice) {
    if (coverPrice == 0L) {
      cover.observeBelow(sCell[slot], sDistance[slot]);
      return;
    }
    final double better =
        Decimal9.toDouble(sBuy[slot] ? coverPrice - sPrice[slot] : sPrice[slot] - coverPrice) / risk.quotePerBp(sInstrument[slot]);
    if (better > 0.0) {
      cover.observeExact(sCell[slot], sDistance[slot] - better);
    }
  }

  /** Schedules the fill's markouts at every horizon of the store, from the mid at the trade. */
  private void scheduleMarkouts(final int s) {
    final long now = clock.epochNanos();
    final Instrument inst = directory.byId(sInstrument[s]);
    final double mid = inst != null && fairValue.compute(inst, now) ? fairValue.fair() : sFair[s];
    for (int h = 0; h < MarkoutStore.H; h++) {
      if (hCount[h] == MARKOUTS) {
        // full: drop the new one, not the oldest, or a busy desk would overwrite every markout of
        // a long horizon before it came due and never measure one
        markoutsDropped++;
        continue;
      }
      final int i = hHead[h];
      hInstrument[h][i] = sInstrument[s];
      hClient[h][i] = sClient[s];
      hClientBuys[h][i] = !sBuy[s];
      hWeBuy[h][i] = sBuy[s];
      hMid[h][i] = mid;
      hDue[h][i] = now + MarkoutStore.horizonNanos(h);
      hHead[h] = (i + 1) % MARKOUTS;
      hCount[h]++;
    }
  }

  /** Measures due markouts: the move in the client's favour since the trade, bp, into the store. */
  private void pollMarkouts(final long nowNanos) {
    for (int h = 0; h < MarkoutStore.H; h++) {
      while (hCount[h] > 0) {
        final int i = (hHead[h] - hCount[h] + MARKOUTS) % MARKOUTS;
        if (hDue[h][i] > nowNanos) {
          break;
        }
        hCount[h]--;
        final Instrument inst = directory.byId(hInstrument[h][i]);
        if (inst == null || !fairValue.compute(inst, nowNanos)) {
          continue;
        }
        final double ours = (fairValue.fair() - hMid[h][i]) * (hWeBuy[h][i] ? 1.0 : -1.0);
        markouts.record(hClient[h][i], hClientBuys[h][i], h, -ours / risk.quotePerBp(hInstrument[h][i]));
      }
    }
  }

  /** The last strategy-path quote's break-even distance, bp (for tests). */
  double lastBreakEvenBp() {
    return lastBreakEven;
  }

  static double liquidSession(final CurrencyCode ccy, final long epochNanos) {
    final long sod = Math.floorMod(epochNanos / 1_000_000_000L, 86_400L);
    if (ccy == CurrencyCode.USD) {
      return sod >= 43_200L && sod < 75_600L ? 1.0 : 0.0;
    }
    return sod >= 25_200L && sod < 59_400L ? 1.0 : 0.0;
  }

  private static double clamp(final double v, final double lo, final double hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }
}
