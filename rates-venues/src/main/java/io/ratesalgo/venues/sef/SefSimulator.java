package io.ratesalgo.venues.sef;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.venues.clob.ClobOrder;
import io.ratesalgo.venues.clob.ClobSimulator;
import io.ratesalgo.venues.feed.FeedChannel;
import io.ratesalgo.venues.sim.Fix;
import io.ratesalgo.venues.sim.Instrument;
import io.ratesalgo.venues.sim.Px;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import quickfix.Group;
import quickfix.Message;

/**
 * Swap execution facility: an order book (inherited from the CLOB, without workup) plus RFQ.
 *
 * <p>Execution rules (LEARN.md Section 6, API_SPEC.md Section 11.2): a Required Transaction is a
 * swap in a made-available-to-trade tenor; below the block threshold it must be executed by RFQ
 * to at least {@code requiredMinDealers} dealers or on the order book, so an RFQ to fewer dealers
 * is refused before any dealer sees it. A Permitted Transaction (not MAT) or a block may use any
 * method, including RFQ to one. Every execution, by RFQ acceptance or order entry, is screened
 * against the FCM limit hub first; a trade without clearing certainty is rejected.
 *
 * <p>Flags on the QuoteRequest: 9505=R (RFQ), 9506=Q required or P permitted, 9507=Y block.
 */
public final class SefSimulator extends ClobSimulator {

  /** Outcome of a SEF RFQ. */
  public enum Outcome {
    /** Refused at submission: Required Transaction sent to too few dealers. */
    REFUSED,
    /** Traded with a dealer. */
    DONE,
    /** Client passed. */
    PASSED,
    /** Nobody quoted. */
    EXPIRED,
    /** The best dealer or the client failed the pre-execution credit check. */
    CREDIT_REJECTED
  }

  private final SefConfig sefConfig;
  private final FcmLimitHub hub;
  private final Map<String, Instrument> swaps = new LinkedHashMap<>();
  private final Map<Long, Rfq> rfqs = new HashMap<>();
  private final Map<String, int[]> quoteStatus = new HashMap<>();
  private final Map<Outcome, Integer> outcomes = new java.util.EnumMap<>(Outcome.class);
  private long rfqSeq;

  private static final class Rfq {
    final long id;
    final Instrument instrument;
    final SefRfqSpec spec;
    final List<String> engines;
    final List<long[]> simQuotes = new ArrayList<>();
    final Map<String, long[]> engineQuotes = new LinkedHashMap<>();
    boolean closed;

    Rfq(final long id, final Instrument instrument, final SefRfqSpec spec, final List<String> engines) {
      this.id = id;
      this.instrument = instrument;
      this.spec = spec;
      this.engines = engines;
    }
  }

  /** Creates the SEF. */
  public SefSimulator(
      final SefConfig config,
      final List<Instrument> swapList,
      final List<String> engineCompIds,
      final FeedChannel feed,
      final Clock clock,
      final long seed) {
    super("SEF", config.book(), swapList, engineCompIds, feed, clock, seed);
    this.sefConfig = config;
    this.hub = new FcmLimitHub(config.fcmLimits(), config.partyFcm());
    for (final Instrument i : swapList) {
      swaps.put(i.securityId(), i);
    }
  }

  /** The credit hub. */
  public FcmLimitHub hub() {
    return hub;
  }

  /** Outcome counts. */
  public synchronized Map<Outcome, Integer> outcomes() {
    return Map.copyOf(outcomes);
  }

  @Override
  protected String preTradeCheck(final String owner, final Instrument inst, final long quantity) {
    return hub.reserve(owner, quantity);
  }

  @Override
  protected void onOrderFinished(final String owner, final ClobOrder order) {
    hub.release(owner, order.quantity() - order.cumQty());
  }

  /** True if a trade of this size in this swap is a Required Transaction below block size. */
  public boolean requiresRfqTo3(final String securityId, final long quantity) {
    final Instrument i = swaps.get(securityId);
    return i.madeAvailableToTrade() && quantity < i.blockThreshold();
  }

  /** Submits a scripted client RFQ. Returns its id, or -1 if the SEF refused it. */
  public synchronized long injectRfq(final SefRfqSpec spec) {
    final Instrument inst = swaps.get(spec.securityId());
    if (inst == null) {
      throw new IllegalArgumentException("unknown swap " + spec.securityId());
    }
    if (requiresRfqTo3(spec.securityId(), spec.quantity()) && spec.dealerCount() < sefConfig.requiredMinDealers()) {
      outcomes.merge(Outcome.REFUSED, 1, Integer::sum);
      log("rfq refused: required transaction to " + spec.dealerCount() + " dealers");
      return -1;
    }
    final long id = ++rfqSeq;
    final Rfq rfq = new Rfq(id, inst, spec, new ArrayList<>(loggedOn()));
    rfqs.put(id, rfq);
    for (int i = 0; i < spec.competitorRates().size(); i++) {
      rfq.simQuotes.add(new long[] {spec.competitorRates().get(i), i});
    }
    final boolean block = spec.quantity() >= inst.blockThreshold();
    log("rfq " + id + " " + inst.securityId() + (spec.buy() ? " pay " : " receive ") + spec.quantity()
        + (inst.madeAvailableToTrade() ? " required" : " permitted") + (block ? " block" : ""));
    for (final String engine : rfq.engines) {
      final Message r = Fix.message("R");
      r.setString(131, Long.toString(id));
      final Group sym = new Group(146, 55, new int[] {55, 48, 22, 54, 38, 15, 126, 0});
      sym.setString(55, inst.securityId());
      sym.setString(48, inst.securityId());
      sym.setString(22, "8");
      sym.setString(54, spec.buy() ? "1" : "2");
      sym.setString(38, Long.toString(spec.quantity()));
      sym.setString(15, inst.currency());
      Fix.time(sym, 126, clock.epochNanos() + sefConfig.responseWindowNanos());
      r.addGroup(sym);
      final Group party = new Group(453, 448, new int[] {448, 447, 452, 0});
      party.setString(448, Integer.toString(spec.clientId()));
      party.setString(447, "D");
      party.setString(452, "3");
      r.addGroup(party);
      r.setString(9501, Integer.toString(spec.dealerCount()));
      r.setString(9505, "R");
      r.setString(9506, inst.madeAvailableToTrade() ? "Q" : "P");
      r.setString(9507, block ? "Y" : "N");
      send(engine, r);
    }
    schedule(clock.nanoTime() + sefConfig.responseWindowNanos(), () -> decide(rfq));
    return id;
  }

  /** Makes the client decide now. */
  public synchronized void decide(final long rfqId) {
    final Rfq rfq = rfqs.get(rfqId);
    if (rfq != null) {
      decide(rfq);
    }
  }

  @Override
  protected void onOther(final String owner, final Message m, final String type) {
    switch (type) {
      case "S" -> onQuote(owner, m);
      case "Z" -> onQuoteCancel(owner, m);
      case "a" -> onQuoteStatusRequest(owner, m);
      default -> super.onOther(owner, m, type);
    }
  }

  private void ack(final String owner, final String rfqId, final String quoteId, final int status, final int code,
      final String text) {
    final Message ai = Fix.message("AI");
    if (rfqId != null) {
      ai.setString(131, rfqId);
    }
    ai.setString(117, quoteId);
    ai.setString(297, Integer.toString(status));
    if (code != 0) {
      ai.setString(9510, Integer.toString(code));
    }
    if (text != null) {
      ai.setString(58, text);
    }
    send(owner, ai);
  }

  private void onQuote(final String owner, final Message m) {
    final String rfqText = Fix.get(m, 131);
    final String quoteText = Fix.req(m, 117);
    final Rfq rfq = rfqText == null ? null : rfqs.get(Long.parseLong(rfqText));
    final String side = rfq == null ? null : Fix.get(m, rfq.spec.buy() ? 133 : 132);
    if (rfq == null || rfq.closed || !rfq.engines.contains(owner) || side == null) {
      ack(owner, rfqText, quoteText, 5, 8, rfq == null ? "unknown QuoteReqID" : "quote not accepted");
      return;
    }
    rfq.engineQuotes.put(owner, new long[] {Px.price(side), Long.parseLong(quoteText), clock.nanoTime()});
    quoteStatus.put(owner + ":" + quoteText, new int[] {0});
    log("quote " + owner + " rfq " + rfq.id + " " + side);
    ack(owner, rfqText, quoteText, 0, 0, null);
  }

  private void onQuoteCancel(final String owner, final Message m) {
    final String quoteText = Fix.req(m, 117);
    final int[] st = quoteStatus.get(owner + ":" + quoteText);
    if (st == null || st[0] != 0) {
      ack(owner, Fix.get(m, 131), quoteText, st == null ? 9 : st[0], 0, "quote not live");
      return;
    }
    st[0] = 6;
    for (final Rfq rfq : rfqs.values()) {
      final long[] q = rfq.engineQuotes.get(owner);
      if (q != null && q[1] == Long.parseLong(quoteText)) {
        rfq.engineQuotes.remove(owner);
      }
    }
    ack(owner, Fix.get(m, 131), quoteText, 6, 0, null);
  }

  private void onQuoteStatusRequest(final String owner, final Message m) {
    final String quoteText = Fix.req(m, 117);
    final int[] st = quoteStatus.get(owner + ":" + quoteText);
    final Message ai = Fix.message("AI");
    ai.setString(649, Fix.req(m, 649));
    ai.setString(117, quoteText);
    ai.setString(297, Integer.toString(st == null ? 9 : st[0]));
    send(owner, ai);
  }

  @Override
  protected void onCounterpartyLogout(final String owner) {
    super.onCounterpartyLogout(owner);
    for (final Rfq rfq : rfqs.values()) {
      if (!rfq.closed) {
        final long[] q = rfq.engineQuotes.remove(owner);
        if (q != null) {
          quoteStatus.get(owner + ":" + q[1])[0] = 6;
          log("cancel-on-disconnect quote " + owner + " " + q[1]);
        }
        rfq.engines.remove(owner);
      }
    }
  }

  private void decide(final Rfq rfq) {
    if (rfq.closed) {
      return;
    }
    rfq.closed = true;
    final boolean buy = rfq.spec.buy();
    String bestEngine = null;
    long best = 0;
    long bestTime = Long.MAX_VALUE;
    boolean any = false;
    final List<Long> prices = new ArrayList<>();
    for (final long[] q : rfq.simQuotes) {
      prices.add(q[0]);
      if (!any || (buy ? q[0] < best : q[0] > best)) {
        best = q[0];
        bestEngine = null;
        bestTime = q[1];
        any = true;
      }
    }
    for (final Map.Entry<String, long[]> e : rfq.engineQuotes.entrySet()) {
      final long[] q = e.getValue();
      prices.add(q[0]);
      if (!any || (buy ? q[0] < best : q[0] > best) || (q[0] == best && q[2] < bestTime && bestEngine != null)) {
        best = q[0];
        bestEngine = e.getKey();
        bestTime = q[2];
        any = true;
      }
    }
    Outcome outcome;
    String creditReason = null;
    if (!any) {
      outcome = Outcome.EXPIRED;
    } else if (!rfq.spec.accept()) {
      outcome = Outcome.PASSED;
    } else {
      creditReason = hub.reserve(Integer.toString(rfq.spec.clientId()), rfq.spec.quantity());
      if (creditReason == null && bestEngine != null) {
        creditReason = hub.reserve(bestEngine, rfq.spec.quantity());
        if (creditReason != null) {
          hub.release(Integer.toString(rfq.spec.clientId()), rfq.spec.quantity());
        }
      }
      outcome = creditReason == null ? Outcome.DONE : Outcome.CREDIT_REJECTED;
    }
    outcomes.merge(outcome, 1, Integer::sum);
    prices.sort(buy ? java.util.Comparator.naturalOrder() : java.util.Comparator.reverseOrder());
    final long cover = prices.size() > 1 ? prices.get(1) : 0;
    log("rfq " + rfq.id + " " + outcome + (bestEngine != null ? " best " + bestEngine : "")
        + (creditReason != null ? " (" + creditReason + ")" : ""));
    for (final String engine : rfq.engines) {
      final long[] mine = rfq.engineQuotes.get(engine);
      if (mine == null) {
        send(engine, response(rfq, 0, 3, null, 0, 0));
        continue;
      }
      final int[] st = quoteStatus.get(engine + ":" + mine[1]);
      st[0] = 6;
      if (outcome == Outcome.CREDIT_REJECTED && engine.equals(bestEngine)) {
        ack(engine, Long.toString(rfq.id), Long.toString(mine[1]), 5, 7, creditReason);
      } else if (outcome == Outcome.DONE && engine.equals(bestEngine)) {
        send(engine, response(rfq, mine[1], 1, null, best, cover));
        send(engine, fill(rfq, mine[1], best));
      } else if (outcome == Outcome.DONE) {
        send(engine, response(rfq, mine[1], 5, "TA", 0, 0));
      } else if (outcome == Outcome.PASSED) {
        send(engine, response(rfq, mine[1], 6, null, 0, 0));
      } else {
        send(engine, response(rfq, mine[1], 3, null, 0, 0));
      }
    }
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
    aj.setString(22, "8");
    aj.setString(54, rfq.spec.buy() ? "1" : "2");
    if (type == 1) {
      Fix.price(aj, 44, price);
      aj.setString(38, Long.toString(rfq.spec.quantity()));
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
    er.setString(22, "8");
    er.setString(54, rfq.spec.buy() ? "1" : "2");
    er.setString(38, Long.toString(rfq.spec.quantity()));
    er.setString(32, Long.toString(rfq.spec.quantity()));
    Fix.price(er, 31, price);
    er.setString(151, "0");
    er.setString(14, Long.toString(rfq.spec.quantity()));
    Fix.price(er, 6, price);
    er.setString(9505, "R");
    Fix.time(er, 60, clock.epochNanos());
    return er;
  }
}
