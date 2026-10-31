package io.ratesalgo.venues.d2c;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.venues.sim.Fix;
import io.ratesalgo.venues.sim.Instrument;
import io.ratesalgo.venues.sim.Px;
import io.ratesalgo.venues.sim.VenueSimulator;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import quickfix.Group;
import quickfix.Message;

/**
 * Dealer-to-client multi-dealer RFQ venue. Engine sessions are dealers. Each RFQ goes to the
 * logged-on engine dealers and to simulated competing dealers (3 to 5 in total by default) whose
 * half spreads are lognormal around a median; the client sees every response, trades with the
 * best one when it is inside the client's tolerance, or passes, or trades elsewhere.
 *
 * <p>Disclosure (LEARN.md Section 6): the winner receives the cover price (second best on the
 * client's side) on its QuoteResponse; losers are told traded away; quoting dealers on an RFQ
 * the client did elsewhere are told done away; dealers who did not quote are told expired.
 *
 * <p>Message flow to an engine dealer (API_SPEC.md Section 10.2): QuoteRequest R; our Quote S is
 * acknowledged by QuoteStatusReport AI 297=0 or rejected with 297=5; QuoteCancel Z is
 * acknowledged with 297=6; QuoteStatusRequest a is answered with AI; the outcome is QuoteResponse
 * AJ (694=1 hit with cover price in 9502, 694=5 with 9503=TA or DA, 694=6 pass, 694=3 expired);
 * a hit is followed by ExecutionReport 8 (150=F). Cancel-on-disconnect: when a dealer session
 * drops, its live quotes are removed and it takes no part in open RFQs.
 */
public final class D2cRfqSimulator extends VenueSimulator {

  private final D2cConfig config;
  private final Map<String, Instrument> instruments = new LinkedHashMap<>();
  private final Map<String, Long> mids = new HashMap<>();
  private final Map<String, Long> midTimes = new HashMap<>();
  private final Map<Long, Rfq> rfqs = new HashMap<>();
  private final Map<String, QuoteRecord> engineQuotes = new HashMap<>();
  private final List<TradeRecord> trades = new ArrayList<>();
  private final Map<RfqOutcome, Integer> outcomes = new EnumMap<>(RfqOutcome.class);
  private final List<String> dealerCodes = List.of("DLR-A", "DLR-B", "DLR-C", "DLR-D", "DLR-E");
  private long rfqSeq;
  private boolean autoFlow;
  private long quotesRejected;

  private static final class QuoteEntry {
    final String dealer;
    final boolean engine;
    final long quoteId;
    final long price;
    final long arrival;
    boolean live = true;

    QuoteEntry(final String dealer, final boolean engine, final long quoteId, final long price, final long arrival) {
      this.dealer = dealer;
      this.engine = engine;
      this.quoteId = quoteId;
      this.price = price;
      this.arrival = arrival;
    }
  }

  private static final class QuoteRecord {
    final long rfqId;
    int status;
    String outcome = "";

    QuoteRecord(final long rfqId) {
      this.rfqId = rfqId;
    }
  }

  private final class Rfq {
    final long id;
    final Instrument instrument;
    final boolean buy;
    final long quantity;
    final int clientId;
    final int dealerCount;
    final ClientDecision decision;
    final boolean informed;
    final List<String> engines;
    final int simulatedDealers;
    final List<QuoteEntry> quotes = new ArrayList<>();
    int simulatedResponses;
    boolean closed;

    Rfq(final long id, final Instrument instrument, final boolean buy, final long quantity, final int clientId,
        final int dealerCount, final ClientDecision decision, final boolean informed, final List<String> engines,
        final int simulatedDealers) {
      this.id = id;
      this.instrument = instrument;
      this.buy = buy;
      this.quantity = quantity;
      this.clientId = clientId;
      this.dealerCount = dealerCount;
      this.decision = decision;
      this.informed = informed;
      this.engines = engines;
      this.simulatedDealers = simulatedDealers;
    }
  }

  /** Creates the simulator. */
  public D2cRfqSimulator(
      final D2cConfig config,
      final List<Instrument> instrumentList,
      final List<String> engineDealers,
      final Clock clock,
      final long seed) {
    super("D2C", engineDealers, clock, seed);
    this.config = config;
    for (final Instrument i : instrumentList) {
      instruments.put(i.securityId(), i);
      mids.put(i.securityId(), i.initialMid());
      midTimes.put(i.securityId(), clock.nanoTime());
    }
  }

  /** Configuration in use. */
  public D2cConfig config() {
    return config;
  }

  /** Every trade so far with its markout. */
  public synchronized List<TradeRecord> trades() {
    return List.copyOf(trades);
  }

  /** Outcome counts. */
  public synchronized Map<RfqOutcome, Integer> outcomes() {
    return Map.copyOf(outcomes);
  }

  /** RFQs created. */
  public synchronized long rfqCount() {
    return rfqSeq;
  }

  /** Engine quotes rejected (late, invalid, unknown RFQ). */
  public synchronized long quotesRejected() {
    return quotesRejected;
  }

  /** Current mid of an instrument (advances the random walk to now). */
  public synchronized long mid(final String securityId) {
    return midNow(instruments.get(securityId));
  }

  private long halfSpreadMedian(final Instrument i) {
    return i.isBond() ? config.bondHalfSpreadMedian() : config.swapHalfSpreadMedian();
  }

  private long midNow(final Instrument i) {
    final long now = clock.nanoTime();
    final long last = midTimes.get(i.securityId());
    long mid = mids.get(i.securityId());
    if (now > last) {
      final double hours = (now - last) / 3.6e12;
      final long vol = i.isBond() ? config.bondVolPerSqrtHour() : config.swapVolPerSqrtHour();
      mid += round(vol * Math.sqrt(hours) * rng.gaussian(), i.tick());
      mids.put(i.securityId(), mid);
      midTimes.put(i.securityId(), now);
    }
    return mid;
  }

  private static long round(final double value, final long tick) {
    return Math.round(value / tick) * tick;
  }

  // ------------------------------------------------------------------ RFQ creation

  /** Starts generating RFQs from the arrival model. */
  public synchronized void startAutoFlow() {
    if (!autoFlow) {
      autoFlow = true;
      scheduleNextArrival();
    }
  }

  private void scheduleNextArrival() {
    final double maxPerNano = config.maxIntensityPerHour() / 3.6e12;
    final long gap = Math.max(1, (long) rng.exponential(1.0 / maxPerNano));
    schedule(clock.nanoTime() + gap, this::onArrivalCandidate);
  }

  private void onArrivalCandidate() {
    final LocalTime local = LocalTime.ofInstant(Instant.ofEpochSecond(0, clock.epochNanos()), config.zone());
    if (rng.uniform() * config.maxIntensityPerHour() < config.intensityPerHour(local)) {
      final List<Instrument> list = new ArrayList<>(instruments.values());
      final Instrument inst = list.get(rng.between(0, list.size() - 1));
      final boolean informed = rng.chance(config.informedFraction());
      final boolean buy = rng.chance(0.5);
      final long size = (inst.isBond() ? config.bondSizes() : config.swapSizes()).draw(rng);
      final int clientId = 2001 + rng.between(0, 4);
      createRfq(inst, buy, size, clientId, rng.between(config.minDealers(), config.maxDealers()), null,
          ClientDecision.MODEL, informed);
    }
    if (autoFlow) {
      scheduleNextArrival();
    }
  }

  /** Injects a scripted RFQ; returns its id. */
  public synchronized long injectRfq(final RfqSpec spec) {
    final Instrument inst = instruments.get(spec.securityId());
    if (inst == null) {
      throw new IllegalArgumentException("unknown instrument " + spec.securityId());
    }
    return createRfq(inst, spec.buy(), spec.quantity(), spec.clientId(), spec.dealerCount(),
        spec.competitorPrices(), spec.decision(), spec.informed());
  }

  private long createRfq(final Instrument inst, final boolean buy, final long quantity, final int clientId,
      final int dealerCount, final List<Long> scripted, final ClientDecision decision, final boolean informed) {
    final long id = ++rfqSeq;
    final List<String> engines = new ArrayList<>(loggedOn());
    final int simulated = scripted != null ? scripted.size() : Math.max(0, dealerCount - engines.size());
    final Rfq rfq = new Rfq(id, inst, buy, quantity, clientId, Math.max(dealerCount, engines.size()),
        decision, informed, engines, simulated);
    rfqs.put(id, rfq);
    log("rfq " + id + " " + inst.securityId() + (buy ? " buy " : " sell ") + quantity + " dealers " + rfq.dealerCount
        + (informed ? " informed" : ""));
    final long now = clock.nanoTime();
    final long expire = clock.epochNanos() + config.responseWindowNanos();
    for (final String engine : engines) {
      send(engine, quoteRequest(rfq, expire));
    }
    if (scripted != null) {
      for (int i = 0; i < scripted.size(); i++) {
        rfq.quotes.add(new QuoteEntry(dealerCodes.get(i % dealerCodes.size()), false, 0, scripted.get(i), now + i));
        rfq.simulatedResponses++;
      }
    } else {
      final long mid = midNow(inst);
      for (int i = 0; i < simulated; i++) {
        final String dealer = dealerCodes.get(i % dealerCodes.size());
        if (!rng.chance(config.dealerResponseProbability())) {
          rfq.simulatedResponses++;
          continue;
        }
        final long half = round(rng.lognormal(halfSpreadMedian(inst), config.halfSpreadSigma()), inst.tick());
        final long price = buy ? mid + Math.max(half, inst.tick()) : mid - Math.max(half, inst.tick());
        final long delay = (long) rng.lognormal(config.dealerDelayMedianNanos(), config.dealerDelaySigma());
        if (delay >= config.responseWindowNanos()) {
          rfq.simulatedResponses++;
          continue;
        }
        schedule(now + delay, () -> {
          if (!rfq.closed) {
            rfq.quotes.add(new QuoteEntry(dealer, false, 0, price, clock.nanoTime()));
            rfq.simulatedResponses++;
            maybeDecideEarly(rfq);
          }
        });
      }
    }
    schedule(now + config.responseWindowNanos(), () -> onWindowClosed(rfq));
    return id;
  }

  private Message quoteRequest(final Rfq rfq, final long expireEpochNanos) {
    final Message m = Fix.message("R");
    m.setString(131, Long.toString(rfq.id));
    final Group sym = new Group(146, 55, new int[] {55, 48, 22, 54, 38, 15, 126, 0});
    sym.setString(55, rfq.instrument.securityId());
    sym.setString(48, rfq.instrument.securityId());
    sym.setString(22, "4");
    sym.setString(54, rfq.buy ? "1" : "2");
    sym.setString(38, Long.toString(rfq.quantity));
    sym.setString(15, rfq.instrument.currency());
    Fix.time(sym, 126, expireEpochNanos);
    m.addGroup(sym);
    final Group party = new Group(453, 448, new int[] {448, 447, 452, 0});
    party.setString(448, Integer.toString(rfq.clientId));
    party.setString(447, "D");
    party.setString(452, "3");
    m.addGroup(party);
    m.setString(9501, Integer.toString(rfq.dealerCount));
    return m;
  }

  // ------------------------------------------------------------------ engine messages

  @Override
  protected void onMessage(final String dealer, final Message m) {
    final String type = Fix.type(m);
    switch (type) {
      case "S" -> onQuote(dealer, m);
      case "Z" -> onQuoteCancel(dealer, m);
      case "a" -> onQuoteStatusRequest(dealer, m);
      default -> {
        final Message reject = Fix.message("j");
        reject.setString(45, Fix.get(m.getHeader(), 34));
        reject.setString(372, type);
        reject.setString(380, "3");
        reject.setString(58, "message type not supported by D2C venue");
        send(dealer, reject);
      }
    }
  }

  private void statusReport(final String dealer, final String rfqId, final String quoteId, final int status,
      final String outcome, final String text) {
    final Message ai = Fix.message("AI");
    if (rfqId != null) {
      ai.setString(131, rfqId);
    }
    ai.setString(117, quoteId);
    ai.setString(297, Integer.toString(status));
    if (!outcome.isEmpty()) {
      ai.setString(9503, outcome);
    }
    if (text != null) {
      ai.setString(58, text);
    }
    send(dealer, ai);
  }

  private void onQuote(final String dealer, final Message m) {
    final String rfqText = Fix.get(m, 131);
    final String quoteText = Fix.req(m, 117);
    final Rfq rfq = rfqText == null ? null : rfqs.get(Long.parseLong(rfqText));
    final String reason;
    if (rfq == null) {
      reason = "unknown QuoteReqID";
    } else if (rfq.closed) {
      reason = "RFQ closed";
    } else if (!rfq.engines.contains(dealer)) {
      reason = "dealer not in competition";
    } else {
      reason = null;
    }
    final String bidText = Fix.get(m, 132);
    final String askText = Fix.get(m, 133);
    String invalid = reason;
    if (invalid == null) {
      final String side = rfq.buy ? askText : bidText;
      if (side == null) {
        invalid = "no price on the client's side";
      } else if (bidText != null && askText != null && Px.price(bidText) >= Px.price(askText)) {
        invalid = "bid not below offer";
      }
    }
    if (invalid != null) {
      quotesRejected++;
      log("quote rejected " + dealer + " " + quoteText + ": " + invalid);
      statusReport(dealer, rfqText, quoteText, 5, "", invalid);
      return;
    }
    final long quoteId = Long.parseLong(quoteText);
    for (final QuoteEntry q : rfq.quotes) {
      if (q.engine && q.dealer.equals(dealer) && q.live) {
        q.live = false;
        final QuoteRecord old = engineQuotes.get(dealer + ":" + q.quoteId);
        if (old != null) {
          old.status = 6;
        }
      }
    }
    final long price = Px.price(rfq.buy ? askText : bidText);
    rfq.quotes.add(new QuoteEntry(dealer, true, quoteId, price, clock.nanoTime()));
    final QuoteRecord record = new QuoteRecord(rfq.id);
    engineQuotes.put(dealer + ":" + quoteId, record);
    log("quote " + dealer + " rfq " + rfq.id + " quote " + quoteId + " " + Px.text(price));
    statusReport(dealer, rfqText, quoteText, 0, "", null);
    maybeDecideEarly(rfq);
  }

  private void onQuoteCancel(final String dealer, final Message m) {
    final String quoteText = Fix.req(m, 117);
    final QuoteRecord rec = engineQuotes.get(dealer + ":" + quoteText);
    if (rec == null || rec.status != 0) {
      statusReport(dealer, Fix.get(m, 131), quoteText, rec == null ? 9 : rec.status, rec == null ? "" : rec.outcome,
          "quote not live");
      return;
    }
    rec.status = 6;
    rec.outcome = "CX";
    removeEngineQuote(rfqs.get(rec.rfqId), dealer, Long.parseLong(quoteText));
    log("quote cancelled " + dealer + " " + quoteText);
    statusReport(dealer, Long.toString(rec.rfqId), quoteText, 6, "CX", null);
  }

  private static void removeEngineQuote(final Rfq rfq, final String dealer, final long quoteId) {
    if (rfq == null) {
      return;
    }
    for (final QuoteEntry q : rfq.quotes) {
      if (q.engine && q.dealer.equals(dealer) && q.quoteId == quoteId) {
        q.live = false;
      }
    }
  }

  private void onQuoteStatusRequest(final String dealer, final Message m) {
    final String quoteText = Fix.req(m, 117);
    final QuoteRecord rec = engineQuotes.get(dealer + ":" + quoteText);
    final Message ai = Fix.message("AI");
    ai.setString(649, Fix.req(m, 649));
    ai.setString(117, quoteText);
    if (rec == null) {
      ai.setString(297, "9");
    } else {
      ai.setString(131, Long.toString(rec.rfqId));
      ai.setString(297, Integer.toString(rec.status));
      if (!rec.outcome.isEmpty()) {
        ai.setString(9503, rec.outcome);
      }
    }
    send(dealer, ai);
  }

  @Override
  protected void onCounterpartyLogout(final String dealer) {
    for (final Map.Entry<String, QuoteRecord> e : engineQuotes.entrySet()) {
      final QuoteRecord rec = e.getValue();
      if (e.getKey().startsWith(dealer + ":") && rec.status == 0) {
        rec.status = 6;
        rec.outcome = "CD";
        removeEngineQuote(rfqs.get(rec.rfqId), dealer, Long.parseLong(e.getKey().substring(dealer.length() + 1)));
        log("cancel-on-disconnect " + e.getKey());
      }
    }
    for (final Rfq rfq : rfqs.values()) {
      if (!rfq.closed) {
        rfq.engines.remove(dealer);
        maybeDecideEarly(rfq);
      }
    }
  }

  // ------------------------------------------------------------------ decision

  private void maybeDecideEarly(final Rfq rfq) {
    if (rfq.closed || rfq.decision == ClientDecision.NONE) {
      return;
    }
    if (rfq.simulatedResponses < rfq.simulatedDealers) {
      return;
    }
    for (final String engine : rfq.engines) {
      boolean quoted = false;
      for (final QuoteEntry q : rfq.quotes) {
        if (q.engine && q.dealer.equals(engine) && q.live) {
          quoted = true;
        }
      }
      if (!quoted) {
        return;
      }
    }
    if (rfq.decision == ClientDecision.MODEL) {
      decide(rfq, rfq.decision);
    }
  }

  private void onWindowClosed(final Rfq rfq) {
    if (!rfq.closed) {
      decide(rfq, rfq.decision);
    }
  }

  /** Makes a scripted RFQ's client decide now with its declared decision. */
  public synchronized void decide(final long rfqId) {
    final Rfq rfq = rfqs.get(rfqId);
    if (rfq != null && !rfq.closed) {
      decide(rfq, rfq.decision == ClientDecision.NONE ? ClientDecision.MODEL : rfq.decision);
    }
  }

  private void decide(final Rfq rfq, final ClientDecision decision) {
    rfq.closed = true;
    final List<QuoteEntry> live = new ArrayList<>();
    for (final QuoteEntry q : rfq.quotes) {
      if (q.live) {
        live.add(q);
      }
    }
    live.sort((a, b) -> {
      final int c = rfq.buy ? Long.compare(a.price, b.price) : Long.compare(b.price, a.price);
      return c != 0 ? c : Long.compare(a.arrival, b.arrival);
    });
    final long mid = midNow(rfq.instrument);
    RfqOutcome outcome;
    if (live.isEmpty() || decision == ClientDecision.NONE) {
      outcome = RfqOutcome.EXPIRED;
    } else if (decision == ClientDecision.PASS) {
      outcome = RfqOutcome.PASSED;
    } else if (decision == ClientDecision.DONE_AWAY) {
      outcome = RfqOutcome.DONE_AWAY;
    } else if (decision == ClientDecision.BEST) {
      outcome = RfqOutcome.DONE;
    } else if (rng.chance(config.doneAwayProbability())) {
      outcome = RfqOutcome.DONE_AWAY;
    } else {
      final double tolerance =
          rng.lognormal(config.toleranceMedian(), config.toleranceSigma()) * halfSpreadMedian(rfq.instrument);
      final long distance = rfq.buy ? live.get(0).price - mid : mid - live.get(0).price;
      outcome = distance <= tolerance ? RfqOutcome.DONE : RfqOutcome.PASSED;
    }
    outcomes.merge(outcome, 1, Integer::sum);
    final QuoteEntry best = outcome == RfqOutcome.DONE ? live.get(0) : null;
    final long cover = best != null && live.size() > 1 ? live.get(1).price : 0;
    log("rfq " + rfq.id + " " + outcome + (best != null ? " winner " + best.dealer + " at " + Px.text(best.price) : "")
        + (cover != 0 ? " cover " + Px.text(cover) : ""));
    for (final String engine : rfq.engines) {
      QuoteEntry mine = null;
      for (final QuoteEntry q : live) {
        if (q.engine && q.dealer.equals(engine)) {
          mine = q;
        }
      }
      if (mine == null) {
        send(engine, response(rfq, 0, 3, null, 0, 0));
        continue;
      }
      final QuoteRecord rec = engineQuotes.get(engine + ":" + mine.quoteId);
      if (mine == best) {
        rec.status = 6;
        rec.outcome = "WN";
        send(engine, response(rfq, mine.quoteId, 1, null, best.price, cover));
        send(engine, fill(rfq, mine.quoteId, best.price));
      } else if (outcome == RfqOutcome.DONE) {
        rec.status = 6;
        rec.outcome = "TA";
        send(engine, response(rfq, mine.quoteId, 5, "TA", 0, 0));
      } else if (outcome == RfqOutcome.DONE_AWAY) {
        rec.status = 6;
        rec.outcome = "DA";
        send(engine, response(rfq, mine.quoteId, 5, "DA", 0, 0));
      } else if (outcome == RfqOutcome.PASSED) {
        rec.status = 6;
        rec.outcome = "PS";
        send(engine, response(rfq, mine.quoteId, 6, null, 0, 0));
      } else {
        rec.status = 7;
        rec.outcome = "EX";
        send(engine, response(rfq, mine.quoteId, 3, null, 0, 0));
      }
    }
    if (best != null) {
      recordTrade(rfq, best, mid);
    }
  }

  private void recordTrade(final Rfq rfq, final QuoteEntry best, final long mid) {
    final int index = trades.size();
    trades.add(new TradeRecord(rfq.id, best.dealer, rfq.buy, best.price, rfq.quantity, rfq.informed, mid, 0));
    final Instrument inst = rfq.instrument;
    if (rfq.informed) {
      final long jump = round(config.informedJumpMultiple() * halfSpreadMedian(inst), inst.tick());
      schedule(clock.nanoTime() + config.informedLagNanos(), () -> {
        final long m = midNow(inst);
        mids.put(inst.securityId(), rfq.buy ? m + jump : m - jump);
      });
    }
    schedule(clock.nanoTime() + config.markoutHorizonNanos(), () -> {
      final TradeRecord t = trades.get(index);
      trades.set(index, new TradeRecord(t.rfqId(), t.winner(), t.clientBuys(), t.price(), t.quantity(), t.informed(),
          t.midAtTrade(), midNow(inst)));
    });
  }

  private Message response(final Rfq rfq, final long quoteId, final int type, final String outcome, final long price,
      final long cover) {
    final Message aj = Fix.message("AJ");
    aj.setString(693, Long.toString(nextId()));
    aj.setString(131, Long.toString(rfq.id));
    if (quoteId != 0) {
      aj.setString(117, Long.toString(quoteId));
    }
    aj.setString(694, Integer.toString(type));
    aj.setString(55, rfq.instrument.securityId());
    aj.setString(48, rfq.instrument.securityId());
    aj.setString(22, "4");
    aj.setString(54, rfq.buy ? "1" : "2");
    if (type == 1) {
      Fix.price(aj, 44, price);
      aj.setString(38, Long.toString(rfq.quantity));
      if (cover != 0) {
        Fix.price(aj, 9502, cover);
      }
    }
    if (outcome != null) {
      aj.setString(9503, outcome);
    }
    Fix.time(aj, 60, clock.epochNanos());
    return aj;
  }

  private Message fill(final Rfq rfq, final long quoteId, final long price) {
    final Message er = Fix.message("8");
    er.setString(37, Long.toString(nextId()));
    er.setString(17, Long.toString(nextId()));
    er.setString(150, "F");
    er.setString(39, "2");
    er.setString(117, Long.toString(quoteId));
    er.setString(131, Long.toString(rfq.id));
    er.setString(55, rfq.instrument.securityId());
    er.setString(48, rfq.instrument.securityId());
    er.setString(22, "4");
    er.setString(54, rfq.buy ? "1" : "2");
    er.setString(38, Long.toString(rfq.quantity));
    er.setString(32, Long.toString(rfq.quantity));
    Fix.price(er, 31, price);
    er.setString(151, "0");
    er.setString(14, Long.toString(rfq.quantity));
    Fix.price(er, 6, price);
    Fix.time(er, 60, clock.epochNanos());
    return er;
  }
}
