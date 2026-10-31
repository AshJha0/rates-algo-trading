package io.ratesalgo.venues.clob;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.venues.feed.BookFeedPublisher;
import io.ratesalgo.venues.feed.FeedChannel;
import io.ratesalgo.venues.sim.Fix;
import io.ratesalgo.venues.sim.Instrument;
import io.ratesalgo.venues.sim.Px;
import io.ratesalgo.venues.sim.VenueSimulator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import quickfix.Group;
import quickfix.Message;

/**
 * Dealer-to-dealer Treasury CLOB venue. Engine sessions enter orders over FIX (NewOrderSingle D,
 * OrderCancelRequest F, OrderCancelReplaceRequest G, OrderMassStatusRequest AF) and may subscribe
 * to FIX market data (MarketDataRequest V answered by W then X). Every book change is also
 * published on the SBE book feed. Simulated participants enter orders through {@link
 * #submitInternal}. The book is anonymous: execution reports never name the counterparty.
 *
 * <p>Cancel-on-disconnect: when an engine session drops, all its orders, resting and held, are
 * cancelled and its subscriptions removed. Chaos controls (duplicate, reversed and late fills,
 * busts) exist for the chaos suite.
 */
public class ClobSimulator extends VenueSimulator implements ClobBook.Listener {

  /** Venue id used on the feed. */
  public static final int VENUE_ID = 102;

  private final ClobConfig config;
  private final Map<String, Instrument> bySecurityId = new LinkedHashMap<>();
  private final Map<Integer, Instrument> byId = new LinkedHashMap<>();
  private final Map<Integer, ClobBook> books = new LinkedHashMap<>();
  private final Map<String, Map<Long, ClobOrder>> byClOrdId = new HashMap<>();
  private final Map<Long, ClobOrder> byVenueId = new HashMap<>();
  private final Map<Long, ExecRecord> executions = new HashMap<>();
  private final Map<String, Set<Integer>> subscriptions = new HashMap<>();
  private final Map<Integer, Map<Boolean, Map<Long, long[]>>> published = new HashMap<>();
  private final Map<Integer, Long> rptSeq = new HashMap<>();
  private final BookFeedPublisher feed;
  private final List<Object[]> outbox = new ArrayList<>();
  private boolean duplicateNextFill;
  private boolean reverseNextFills;
  private boolean lateFillOnNextCancel;
  private long trades;

  private record ExecRecord(String owner, ClobOrder order, long price, long quantity, int instrumentId, long nanos) {}

  /**
   * One fill the venue sent to an engine owner, as the venue's own trade register keeps it: the
   * venue side of the daily trade reconciliation.
   *
   * @param execId ExecID
   * @param clOrdId the owner's order id
   * @param instrumentId wire id
   * @param buy the owner's side
   * @param price mantissa
   * @param quantity currency units
   * @param epochNanos time of the fill
   */
  public record Execution(
      long execId, long clOrdId, int instrumentId, boolean buy, long price, long quantity, long epochNanos) {}

  /** Fills sent to an owner and not busted, in ExecID order. */
  public synchronized List<Execution> executions(final String owner) {
    final List<Execution> out = new ArrayList<>();
    for (final Map.Entry<Long, ExecRecord> e : new java.util.TreeMap<>(executions).entrySet()) {
      final ExecRecord r = e.getValue();
      if (r.owner().equals(owner)) {
        out.add(new Execution(e.getKey(), r.order().clOrdId, r.instrumentId(), r.order().buy, r.price(),
            r.quantity(), r.nanos()));
      }
    }
    return out;
  }

  /** Creates the simulator. */
  public ClobSimulator(
      final String compId,
      final ClobConfig config,
      final List<Instrument> instruments,
      final List<String> engineCompIds,
      final FeedChannel feedChannel,
      final Clock clock,
      final long seed) {
    super(compId, engineCompIds, clock, seed);
    this.config = config;
    for (final Instrument i : instruments) {
      bySecurityId.put(i.securityId(), i);
      byId.put(i.instrumentId(), i);
      books.put(i.instrumentId(), new ClobBook(i.instrumentId(), config, this));
    }
    this.feed = new BookFeedPublisher(VENUE_ID, feedChannel, clock);
  }

  /** The feed publisher, which is also the snapshot service. */
  public BookFeedPublisher feed() {
    return feed;
  }

  /** The configuration. */
  public ClobConfig config() {
    return config;
  }

  /** The book of an instrument. */
  public synchronized ClobBook book(final int instrumentId) {
    return books.get(instrumentId);
  }

  /** Trades so far. */
  public synchronized long trades() {
    return trades;
  }

  /** Live orders of an owner. */
  public synchronized List<ClobOrder> liveOrders(final String owner) {
    final List<ClobOrder> out = new ArrayList<>();
    for (final ClobOrder o : byClOrdId.getOrDefault(owner, Map.of()).values()) {
      if (!o.done) {
        out.add(o);
      }
    }
    return out;
  }

  /** Sends the next engine fill twice (same ExecID). */
  public synchronized void duplicateNextFill() {
    duplicateNextFill = true;
  }

  /** Sends the engine fills of the next match in reverse order. */
  public synchronized void reverseNextFills() {
    reverseNextFills = true;
  }

  /** On the next engine cancel, sends a fill for the cancelled order after the cancel ack. */
  public synchronized void lateFillOnNextCancel() {
    lateFillOnNextCancel = true;
  }

  /** Busts an execution: sends ExecType H to its engine owner. Returns false if unknown. */
  public synchronized boolean bust(final long execId) {
    final ExecRecord r = executions.remove(execId);
    if (r == null) {
      return false;
    }
    final Message er = executionReport(r.order(), 'H', r.order().done ? '2' : '1');
    er.setString(19, Long.toString(execId));
    er.setString(32, Long.toString(r.quantity()));
    Fix.price(er, 31, r.price());
    log("bust " + execId);
    send(r.owner(), er);
    return true;
  }

  /** ExecIDs of fills sent to an owner, oldest first. */
  public synchronized List<Long> executionIds(final String owner) {
    final List<Long> ids = new ArrayList<>();
    for (final Map.Entry<Long, ExecRecord> e : executions.entrySet()) {
      if (e.getValue().owner().equals(owner)) {
        ids.add(e.getKey());
      }
    }
    Collections.sort(ids);
    return ids;
  }

  /**
   * Enters an order for a simulated participant. Returns the venue order id, or a negative value
   * if the order breaks a rule.
   */
  public synchronized long submitInternal(
      final String owner, final int instrumentId, final boolean buy, final long price, final long quantity,
      final long display) {
    final ClobBook book = books.get(instrumentId);
    if (book == null || book.validate(price, quantity, display) != null) {
      return -1;
    }
    final ClobOrder o = new ClobOrder(nextId(), owner, 0, buy, price, quantity, display, false);
    byVenueId.put(o.venueOrderId, o);
    orderInstrument.put(o.venueOrderId, instrumentId);
    log("internal " + owner + (buy ? " buy " : " sell ") + quantity + " @ " + Px.text(price));
    book.submit(o, clock.nanoTime());
    scheduleWorkup(book);
    afterBookChange(book);
    return o.venueOrderId;
  }

  /** Cancels a simulated participant's order. */
  public synchronized boolean cancelInternal(final long venueOrderId) {
    final ClobOrder o = byVenueId.get(venueOrderId);
    if (o == null || o.done) {
      return false;
    }
    final ClobBook book = books.get(orderInstrument.get(venueOrderId));
    final boolean ok = book.cancel(o);
    afterBookChange(book);
    return ok;
  }

  private final Map<Long, Integer> orderInstrument = new HashMap<>();

  private int orderInstrument(final ClobOrder o) {
    final Integer id = orderInstrument.get(o.venueOrderId);
    return id == null ? 0 : id;
  }

  // ------------------------------------------------------------------ book listener

  @Override
  public void onFill(final ClobOrder aggressor, final ClobOrder passive, final long price, final long quantity,
      final char workup) {
    trades++;
    log("trade " + quantity + " @ " + Px.text(price) + " " + aggressor.owner + "/" + passive.owner + " " + workup);
    for (final ClobOrder o : new ClobOrder[] {aggressor, passive}) {
      if (isEngine(o.owner)) {
        final long execId = nextId();
        executions.put(execId, new ExecRecord(o.owner, o, price, quantity, orderInstrument(o), clock.epochNanos()));
        final Message er = executionReport(o, 'F', o.done ? '2' : '1');
        er.setString(17, Long.toString(execId));
        er.setString(32, Long.toString(quantity));
        Fix.price(er, 31, price);
        er.setString(9504, Character.toString(workup));
        outbox.add(new Object[] {o.owner, er, true});
      }
    }
  }

  @Override
  public void onBookCancel(final ClobOrder order, final String reason) {
    log("book cancel " + order.venueOrderId + ": " + reason);
    if (isEngine(order.owner)) {
      final Message er = executionReport(order, '4', '4');
      er.setString(58, reason);
      outbox.add(new Object[] {order.owner, er, false});
    }
  }

  private boolean isEngine(final String owner) {
    return byClOrdId.containsKey(owner);
  }

  private void flush() {
    if (reverseNextFills) {
      final List<Object[]> fills = new ArrayList<>();
      for (final Object[] e : outbox) {
        if ((Boolean) e[2]) {
          fills.add(e);
        }
      }
      if (fills.size() > 1) {
        reverseNextFills = false;
        Collections.reverse(fills);
        int k = 0;
        for (int i = 0; i < outbox.size(); i++) {
          if ((Boolean) outbox.get(i)[2]) {
            outbox.set(i, fills.get(k++));
          }
        }
      }
    }
    for (final Object[] e : outbox) {
      send((String) e[0], (Message) e[1]);
      if ((Boolean) e[2] && duplicateNextFill) {
        duplicateNextFill = false;
        send((String) e[0], (Message) e[1]);
      }
    }
    outbox.clear();
  }

  // ------------------------------------------------------------------ market data

  private void afterBookChange(final ClobBook book) {
    flush();
    final int id = book.instrumentId();
    final Map<Boolean, Map<Long, long[]>> prev =
        published.computeIfAbsent(id, k -> new HashMap<>(Map.of(true, new LinkedHashMap<>(), false, new LinkedHashMap<>())));
    final List<long[]> changes = new ArrayList<>();
    for (final boolean bid : new boolean[] {true, false}) {
      final Map<Long, long[]> before = prev.get(bid);
      final Map<Long, long[]> after = new LinkedHashMap<>();
      int rank = 0;
      for (final long[] level : book.levels(bid, config.depth())) {
        after.put(level[0], new long[] {level[1], level[2], ++rank});
      }
      for (final Map.Entry<Long, long[]> e : before.entrySet()) {
        if (!after.containsKey(e.getKey())) {
          changes.add(new long[] {bid ? 1 : 0, 2, e.getKey(), 0, 0, 0});
          feed.level(id, bid ? Side.Buy : Side.Sell, e.getKey(), 0, 0);
        }
      }
      for (final Map.Entry<Long, long[]> e : after.entrySet()) {
        final long[] old = before.get(e.getKey());
        final long[] now = e.getValue();
        if (old == null || old[0] != now[0] || old[1] != now[1]) {
          changes.add(new long[] {bid ? 1 : 0, old == null ? 0 : 1, e.getKey(), now[0], now[1], now[2]});
          feed.level(id, bid ? Side.Buy : Side.Sell, e.getKey(), now[0], (int) now[1]);
        }
      }
      prev.put(bid, after);
    }
    if (changes.isEmpty()) {
      return;
    }
    for (final Map.Entry<String, Set<Integer>> sub : subscriptions.entrySet()) {
      if (sub.getValue().contains(id)) {
        send(sub.getKey(), incremental(byId.get(id), changes));
      }
    }
  }

  private Message incremental(final Instrument inst, final List<long[]> changes) {
    final Message x = Fix.message("X");
    x.setString(262, "1");
    for (final long[] c : changes) {
      final Group g = new Group(268, 279, new int[] {279, 269, 55, 48, 22, 83, 270, 271, 346, 1023, 0});
      g.setString(279, Long.toString(c[1]));
      g.setString(269, c[0] == 1 ? "0" : "1");
      g.setString(55, inst.securityId());
      g.setString(48, inst.securityId());
      g.setString(22, "4");
      g.setString(83, Long.toString(rptSeq.merge(inst.instrumentId(), 1L, Long::sum)));
      Fix.price(g, 270, c[2]);
      g.setString(271, Long.toString(c[3]));
      if (c[1] != 2) {
        g.setString(346, Long.toString(c[4]));
        g.setString(1023, Long.toString(c[5]));
      }
      x.addGroup(g);
    }
    return x;
  }

  private Message snapshot(final Instrument inst, final String requestId) {
    final Message w = Fix.message("W");
    w.setString(262, requestId);
    w.setString(55, inst.securityId());
    w.setString(48, inst.securityId());
    w.setString(22, "4");
    w.setString(83, Long.toString(rptSeq.getOrDefault(inst.instrumentId(), 0L)));
    final ClobBook book = books.get(inst.instrumentId());
    for (final boolean bid : new boolean[] {true, false}) {
      int rank = 0;
      for (final long[] level : book.levels(bid, config.depth())) {
        final Group g = new Group(268, 269, new int[] {269, 270, 271, 346, 1023, 0});
        g.setString(269, bid ? "0" : "1");
        Fix.price(g, 270, level[0]);
        g.setString(271, Long.toString(level[1]));
        g.setString(346, Long.toString(level[2]));
        g.setString(1023, Integer.toString(++rank));
        w.addGroup(g);
      }
    }
    if (!w.isSetField(268)) {
      w.setString(268, "0");
    }
    return w;
  }

  // ------------------------------------------------------------------ engine messages

  @Override
  protected void onCounterpartyLogon(final String owner) {
    byClOrdId.computeIfAbsent(owner, k -> new LinkedHashMap<>());
  }

  @Override
  protected void onCounterpartyLogout(final String owner) {
    subscriptions.remove(owner);
    final Set<ClobBook> touched = new LinkedHashSet<>();
    for (final ClobOrder o : byClOrdId.getOrDefault(owner, Map.of()).values()) {
      if (!o.done) {
        final ClobBook book = books.get(orderInstrument.get(o.venueOrderId));
        if (book.cancel(o)) {
          touched.add(book);
          log("cancel-on-disconnect " + owner + " " + o.clOrdId);
        }
      }
    }
    for (final ClobBook b : touched) {
      afterBookChange(b);
    }
  }

  @Override
  protected void onMessage(final String owner, final Message m) {
    final String type = Fix.type(m);
    switch (type) {
      case "D" -> onNewOrder(owner, m);
      case "F" -> onCancel(owner, m);
      case "G" -> onReplace(owner, m);
      case "AF" -> onMassStatus(owner, m);
      case "V" -> onMarketDataRequest(owner, m);
      default -> onOther(owner, m, type);
    }
  }

  /** Message types this venue does not handle get a BusinessMessageReject; subclasses extend. */
  protected void onOther(final String owner, final Message m, final String type) {
    final Message reject = Fix.message("j");
    reject.setString(45, Fix.get(m.getHeader(), 34));
    reject.setString(372, type);
    reject.setString(380, "3");
    reject.setString(58, "message type not supported by " + compId());
    send(owner, reject);
  }

  /** Instrument named by a message's SecurityID or Symbol, or null. */
  protected final Instrument instrumentOf(final Message m) {
    String id = Fix.get(m, 48);
    if (id == null) {
      id = Fix.get(m, 55);
    }
    return id == null ? null : bySecurityId.get(id);
  }

  /** Hook for venues that check credit before an order reaches the book; null means accepted. */
  protected String preTradeCheck(final String owner, final Instrument inst, final long quantity) {
    return null;
  }

  /** Hook called when an engine order is finished, so a credit reservation can be released. */
  protected void onOrderFinished(final String owner, final ClobOrder order) {}

  private void reject(final String owner, final Message m, final String reason, final int code) {
    final Message er = Fix.message("8");
    er.setString(37, "0");
    er.setString(11, Fix.req(m, 11));
    er.setString(17, Long.toString(nextId()));
    er.setString(150, "8");
    er.setString(39, "8");
    final String sec = Fix.get(m, 48);
    if (sec != null) {
      er.setString(55, sec);
      er.setString(48, sec);
      er.setString(22, "4");
    }
    er.setString(54, Fix.req(m, 54));
    er.setString(151, "0");
    er.setString(14, "0");
    er.setString(6, "0");
    er.setString(103, "99");
    er.setString(9510, Integer.toString(code));
    er.setString(58, reason);
    log("reject " + owner + " " + Fix.req(m, 11) + ": " + reason);
    send(owner, er);
  }

  private void onNewOrder(final String owner, final Message m) {
    final Instrument inst = instrumentOf(m);
    if (inst == null) {
      reject(owner, m, "unknown instrument", 1);
      return;
    }
    final long clOrdId = Long.parseLong(Fix.req(m, 11));
    final Map<Long, ClobOrder> mine = byClOrdId.computeIfAbsent(owner, k -> new LinkedHashMap<>());
    if (mine.containsKey(clOrdId)) {
      reject(owner, m, "duplicate ClOrdID", 2);
      return;
    }
    if (!"2".equals(Fix.get(m, 40))) {
      reject(owner, m, "only limit orders are accepted", 3);
      return;
    }
    final String tif = Fix.get(m, 59);
    if (tif != null && !"0".equals(tif) && !"3".equals(tif)) {
      reject(owner, m, "time in force not supported", 4);
      return;
    }
    final long price = Px.price(Fix.req(m, 44));
    final long qty = Px.qty(Fix.req(m, 38));
    final String displayText = Fix.get(m, 1138);
    final long display = displayText == null ? 0 : Px.qty(displayText);
    final ClobBook book = books.get(inst.instrumentId());
    final String invalid = book.validate(price, qty, display);
    if (invalid != null) {
      reject(owner, m, invalid, 5);
      return;
    }
    final String credit = preTradeCheck(owner, inst, qty);
    if (credit != null) {
      reject(owner, m, credit, 6);
      return;
    }
    final ClobOrder o =
        new ClobOrder(nextId(), owner, clOrdId, "1".equals(Fix.req(m, 54)), price, qty, display, "3".equals(tif));
    mine.put(clOrdId, o);
    byVenueId.put(o.venueOrderId, o);
    orderInstrument.put(o.venueOrderId, inst.instrumentId());
    log("order " + owner + " " + clOrdId + (o.buy ? " buy " : " sell ") + qty + " @ " + Px.text(price)
        + (display > 0 ? " display " + display : ""));
    send(owner, executionReport(o, '0', '0'));
    book.submit(o, clock.nanoTime());
    scheduleWorkup(book);
    afterBookChange(book);
    if (o.done) {
      onOrderFinished(owner, o);
    }
  }

  private void scheduleWorkup(final ClobBook book) {
    if (book.inWorkup()) {
      final long end = book.workupEndNanos();
      schedule(end, () -> {
        book.onTime(clock.nanoTime());
        afterBookChange(book);
      });
      final long exclusive = end - config.workupNanos() + config.exclusiveNanos();
      if (exclusive > clock.nanoTime()) {
        schedule(exclusive, () -> {
          book.onTime(clock.nanoTime());
          afterBookChange(book);
        });
      }
    }
  }

  private void cancelReject(final String owner, final Message m, final String responseTo, final String reason) {
    final Message r = Fix.message("9");
    r.setString(37, "NONE");
    r.setString(11, Fix.req(m, 11));
    r.setString(41, Fix.req(m, 41));
    r.setString(39, "8");
    r.setString(434, responseTo);
    r.setString(102, "1");
    r.setString(58, reason);
    send(owner, r);
  }

  private ClobOrder find(final String owner, final Message m) {
    final String orig = Fix.get(m, 41);
    if (orig == null) {
      return null;
    }
    final ClobOrder o = byClOrdId.getOrDefault(owner, Map.of()).get(Long.parseLong(orig));
    return o == null || o.done ? null : o;
  }

  private void onCancel(final String owner, final Message m) {
    final ClobOrder o = find(owner, m);
    if (o == null) {
      cancelReject(owner, m, "1", "unknown or finished order");
      return;
    }
    final ClobBook book = books.get(orderInstrument.get(o.venueOrderId));
    final long origClOrdId = o.clOrdId;
    book.cancel(o);
    final long newClOrdId = Long.parseLong(Fix.req(m, 11));
    final Message ack = executionReport(o, '4', '4');
    ack.setString(11, Long.toString(newClOrdId));
    ack.setString(41, Long.toString(origClOrdId));
    log("cancel " + owner + " " + origClOrdId);
    send(owner, ack);
    if (lateFillOnNextCancel) {
      lateFillOnNextCancel = false;
      final long qty = config.minQty();
      final long execId = nextId();
      o.cumQty += qty;
      executions.put(execId, new ExecRecord(owner, o, o.price, qty, orderInstrument(o), clock.epochNanos()));
      final Message late = executionReport(o, 'F', '4');
      late.setString(17, Long.toString(execId));
      late.setString(32, Long.toString(qty));
      Fix.price(late, 31, o.price);
      late.setString(9504, "N");
      log("late fill " + owner + " " + origClOrdId);
      send(owner, late);
    }
    afterBookChange(book);
    onOrderFinished(owner, o);
  }

  private void onReplace(final String owner, final Message m) {
    final ClobOrder o = find(owner, m);
    if (o == null) {
      cancelReject(owner, m, "2", "unknown or finished order");
      return;
    }
    final ClobBook book = books.get(orderInstrument.get(o.venueOrderId));
    final long price = Px.price(Fix.req(m, 44));
    final long qty = Px.qty(Fix.req(m, 38));
    final String invalid = book.validate(price, qty, o.display);
    if (invalid != null) {
      cancelReject(owner, m, "2", invalid);
      return;
    }
    final long orig = o.clOrdId;
    final long newClOrdId = Long.parseLong(Fix.req(m, 11));
    final Map<Long, ClobOrder> mine = byClOrdId.get(owner);
    mine.remove(orig);
    o.clOrdId = newClOrdId;
    mine.put(newClOrdId, o);
    final Message ack = executionReport(o, '5', '0');
    ack.setString(41, Long.toString(orig));
    ack.setString(38, Long.toString(qty));
    Fix.price(ack, 44, price);
    ack.setString(151, Long.toString(Math.max(0, qty - o.cumQty)));
    log("replace " + owner + " " + orig + " -> " + newClOrdId + " " + qty + " @ " + Px.text(price));
    send(owner, ack);
    book.replace(o, price, qty, clock.nanoTime());
    scheduleWorkup(book);
    afterBookChange(book);
  }

  private void onMassStatus(final String owner, final Message m) {
    final List<ClobOrder> live = liveOrders(owner);
    final String reqId = Fix.req(m, 584);
    if (live.isEmpty()) {
      final Message er = Fix.message("8");
      er.setString(37, "0");
      er.setString(17, Long.toString(nextId()));
      er.setString(150, "I");
      er.setString(39, "4");
      er.setString(584, reqId);
      er.setString(911, "0");
      er.setString(54, "1");
      er.setString(151, "0");
      er.setString(14, "0");
      er.setString(6, "0");
      send(owner, er);
      return;
    }
    for (final ClobOrder o : live) {
      final Message er = executionReport(o, 'I', o.cumQty > 0 ? '1' : '0');
      er.setString(584, reqId);
      er.setString(911, Integer.toString(live.size()));
      send(owner, er);
    }
  }

  private void onMarketDataRequest(final String owner, final Message m) {
    final String reqId = Fix.req(m, 262);
    final Instrument inst = instrumentOf(m) != null ? instrumentOf(m) : symbolFromGroup(m);
    if (inst == null) {
      final Message y = Fix.message("Y");
      y.setString(262, reqId);
      y.setString(281, "0");
      y.setString(58, "unknown instrument");
      send(owner, y);
      return;
    }
    if ("2".equals(Fix.get(m, 263))) {
      subscriptions.getOrDefault(owner, new LinkedHashSet<>()).remove(inst.instrumentId());
      return;
    }
    subscriptions.computeIfAbsent(owner, k -> new LinkedHashSet<>()).add(inst.instrumentId());
    send(owner, snapshot(inst, reqId));
  }

  private Instrument symbolFromGroup(final Message m) {
    final String id = Fix.bodyOrGroup(m, 48, 146, 55);
    return id == null ? null : bySecurityId.get(id);
  }

  /** Execution report skeleton for an order. */
  protected final Message executionReport(final ClobOrder o, final char execType, final char ordStatus) {
    final Instrument inst = byId.get(orderInstrument.get(o.venueOrderId));
    final Message er = Fix.message("8");
    er.setString(37, Long.toString(o.venueOrderId));
    er.setString(11, Long.toString(o.clOrdId));
    er.setString(17, Long.toString(nextId()));
    er.setString(150, Character.toString(execType));
    er.setString(39, Character.toString(ordStatus));
    er.setString(55, inst.securityId());
    er.setString(48, inst.securityId());
    er.setString(22, "4");
    er.setString(54, o.buy ? "1" : "2");
    er.setString(38, Long.toString(o.quantity));
    er.setString(40, "2");
    Fix.price(er, 44, o.price);
    er.setString(151, Long.toString(o.remaining));
    er.setString(14, Long.toString(o.cumQty));
    Fix.price(er, 6, o.cumQty > 0 ? o.price : 0);
    if (o.display > 0) {
      er.setString(1138, Long.toString(o.display));
    }
    Fix.time(er, 60, clock.epochNanos());
    return er;
  }
}
