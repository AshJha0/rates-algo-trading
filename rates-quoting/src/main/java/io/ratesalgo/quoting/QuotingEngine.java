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
import io.ratesalgo.hedge.HedgeUniverse;
import io.ratesalgo.hedge.KeyRateCovariance;
import io.ratesalgo.messages.sbe.CancelReason;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.QuoteCancelEncoder;
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
 * unhealthy and any quote whose oldest input has grown older than the maximum age, and measures
 * fill markouts for the toxicity table. Single-writer; the RFQ, response and poll paths do not
 * allocate after warm-up (QuotingAllocationTest).
 */
public final class QuotingEngine implements KillSwitchListener {

  private static final int K = KeyRateTenors.COUNT;
  private static final int MARKOUTS = 4096;
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
  private final QuoteCancelEncoder cancel = new QuoteCancelEncoder();

  // statistics
  private long rfqs;
  private long quotesSent;
  private long passes;
  private long fills;
  private long expiries;
  private long pulls;
  private long unmatchedResponses;
  private double coverDistanceBp;
  private long coverObservations;

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
    freeSlots = new int[capacity];
    for (int i = 0; i < capacity; i++) {
      freeSlots[i] = capacity - 1 - i;
    }
    freeTop = capacity;
    quoteSlot = new Long2LongHashMap(capacity * 4, 0.5f, -1L);
    seen = new LongHashSet(config.duplicateWindow * 4);
    seenRing = new long[config.duplicateWindow];
    lastQuoteTime = new Long2LongHashMap(Math.max(64, config.duplicateWindow * 2), 0.5f, Long.MIN_VALUE);
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
    qty = Math.min(qty, risk.headroom(instrumentId, outcome.ourSide));
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
    final double volRatio = config.baselineVolBp > 0.0 ? volBp[ccy] / config.baselineVolBp : 1.0;
    final double cost =
        toxicity.adverseBp(clientId)
            + config.winnersCurseBp * Math.sqrt(2.0 * Math.log(Math.max(1, dealers))) * volRatio
            + hedgeCostBp(inst, qtyMajor * unitPar);
    final double base =
        hitRate.base(
            (double) qty / inst.typicalQuantity(), inst.tenorYears(), liquidSession(inst.currency(), now), dealers);
    final double optimal = spread.optimal(base, cost, config.maxHalfSpreadBp);
    final double widened =
        optimal * config.eventMultiplier(now)
            + Math.max(0.0, volBp[ccy] - config.baselineVolBp) * config.volWideningPerBp;
    final double half = clamp(widened * tier.spreadMultiplier(), config.minHalfSpreadBp, config.maxHalfSpreadBp);

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
    final double distance =
        Math.max(-config.maxImproveBp, half + (weBuy ? skew : -skew) + sizeTerm - axe);
    final long price = weBuy ? Decimal9.floor(fair - distance * qpb) : Decimal9.ceil(fair + distance * qpb);

    outcome.costBp = cost;
    outcome.halfSpreadBp = half;
    outcome.skewBp = skew;
    outcome.sizeTermBp = sizeTerm;
    outcome.axeBp = axe;
    outcome.distanceBp = distance;
    outcome.winProbability = hitRate.winProbability(base, distance);
    outcome.price = price;

    // risk check, reserve, send
    final int r =
        weBuy
            ? risk.checkQuote(instrumentId, price, qty, 0L, 0L, journalSeqNo)
            : risk.checkQuote(instrumentId, 0L, 0L, price, qty, journalSeqNo);
    if (r != RiskEngine.OK) {
      outcome.limitRejection = r;
      return pass(PassReason.RISK_REJECT);
    }
    risk.reserve(instrumentId, weBuy ? qty : 0L, weBuy ? 0L : qty);
    long validUntil = now + config.firmTtlNanos;
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
    quoteSlot.put(quoteId, slot);
    nextExpiry = Math.min(nextExpiry, validUntil);
    nextStale = Math.min(nextStale, sOldest[slot] + config.maxInputAgeNanos);
    lastQuoteTime.put(throttleKey, now);
    gateway.send(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + quote.encodedLength());
    quotesSent++;
    outcome.quoteId = quoteId;
    outcome.validUntilNanos = validUntil;
    outcome.decision = firm ? RfqDecision.FIRM : RfqDecision.SUBJECT;
    return outcome.decision;
  }

  /** Decodes and handles a QuoteResponse. */
  public void onQuoteResponse(final QuoteResponseDecoder d) {
    onQuoteResponse(d.quoteId(), d.status(), d.tradedPrice().mantissa(), d.tradedQuantity(), d.coverPrice().mantissa());
  }

  /**
   * The venue's outcome for one of our quotes. Done books the fill (our side from the quote),
   * teaches the hit-rate model a win, and with a disclosed cover price also teaches it that the
   * quote would still have won at the cover. TradedAway teaches a loss. Every outcome releases the
   * quote's reservation and frees it.
   */
  public void onQuoteResponse(
      final long quoteId, final QuoteStatus status, final long tradedPrice, final long tradedQuantity, final long coverPrice) {
    final int slot = (int) quoteSlot.get(quoteId);
    if (slot < 0) {
      unmatchedResponses++;
      return;
    }
    final int instrumentId = sInstrument[slot];
    if (status == QuoteStatus.Done) {
      final long qty = Math.min(Math.max(tradedQuantity, 0L), sQty[slot]);
      fills++;
      release(slot);
      risk.onFill(instrumentId, sBuy[slot] ? qty : -qty, tradedPrice);
      hitRate.update(sBase[slot], sDistance[slot], true);
      if (coverPrice != 0L && coverPrice != tradedPrice) {
        final double gap = Math.abs(Decimal9.toDouble(tradedPrice) - Decimal9.toDouble(coverPrice)) / risk.quotePerBp(instrumentId);
        coverDistanceBp += gap;
        coverObservations++;
        hitRate.update(sBase[slot], sDistance[slot] + gap, true);
      }
      scheduleMarkout(slot, tradedPrice);
    } else {
      release(slot);
      if (status == QuoteStatus.TradedAway) {
        hitRate.update(sBase[slot], sDistance[slot], false);
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
  }

  @Override
  public void onKill(final KillReason reason, final long epochNanos) {
    pullAll(CancelReason.KillSwitch, epochNanos);
  }

  /** Withdraws every live quote with a QuoteCancel. */
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

  // ---------------------------------------------------------------- internals

  private RfqDecision pass(final PassReason reason) {
    passes++;
    outcome.decision = RfqDecision.PASS;
    outcome.reason = reason;
    return RfqDecision.PASS;
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
  }

  private void free(final int s) {
    quoteSlot.remove(sQuoteId[s]);
    used[s] = false;
    cancelling[s] = false;
    freeSlots[freeTop++] = s;
  }

  private void scheduleMarkout(final int s, final long tradedPrice) {
    final int i = mHead;
    mInstrument[i] = sInstrument[s];
    mClient[i] = sClient[s];
    mBuy[i] = sBuy[s];
    mPrice[i] = Decimal9.toDouble(tradedPrice);
    mDue[i] = clock.epochNanos() + config.markoutHorizonNanos;
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

  private static double liquidSession(final CurrencyCode ccy, final long epochNanos) {
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
