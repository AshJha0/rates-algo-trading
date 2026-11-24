package io.ratesalgo.backtest.sim;

import io.ratesalgo.sim.BookModel;
import io.ratesalgo.sim.MarketModel;
import io.ratesalgo.sim.MarketSpec;
import io.ratesalgo.sim.RfqDecision;
import io.ratesalgo.sim.SessionSeeds;
import io.ratesalgo.sim.SimInstrument;
import io.ratesalgo.sim.SimRfq;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * One simulator v2 session as the back-tester uses it: the shared {@link MarketModel} with the
 * back-test's conventions on top (epoch time, instrument ids, quantities in minor units).
 *
 * <p>The back-test's outcome, hedge-cost and queue models in their {@code simV2} form all hold one
 * of these, so an RFQ is settled by the model's own competitors, a hedge is filled by the model's
 * own book, and a mark is the model's latent mid. There is no second set of market constants on
 * this side (docs/research/SIM_V2_DESIGN.md section 10).
 *
 * <p>Quantities: the back-tester counts in minor units (cents of notional or face); the model
 * counts in USD of notional or face for swaps and Treasuries and in contracts for futures.
 */
public final class SimDay {

  private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
  private static final long RFQ_IDS_PER_DAY = 1_000_000L;

  /**
   * How an RFQ ended.
   *
   * @param decision the model's decision
   * @param bestCompetitorPrice best live competitor price in quoted units, NaN if no competitor answered
   */
  public record Settlement(RfqDecision decision, double bestCompetitorPrice) {}

  private static final class Working {
    final int instrument;
    final int bookOrderId;
    final double totalUnits;
    double filledUnits;

    Working(final int instrument, final int bookOrderId, final double totalUnits, final double filledUnits) {
      this.instrument = instrument;
      this.bookOrderId = bookOrderId;
      this.totalUnits = totalUnits;
      this.filledUnits = filledUnits;
    }
  }

  private final MarketModel model;
  private final MarketSpec spec;
  private final LocalDate date;
  private final long openEpochNanos;
  private final Map<Integer, Integer> indexById = new HashMap<>();
  private final Map<Integer, Working> working = new HashMap<>();
  private final boolean[] bookInUse;
  private int nextHandle = 1;

  /** The session with this seed on this date; the day type follows from the seed (design section 12). */
  public static SimDay of(final MarketSpec spec, final long seed, final LocalDate date) {
    return new SimDay(SessionSeeds.session(spec, seed), date);
  }

  public SimDay(final MarketModel model, final LocalDate date) {
    this.model = model;
    this.spec = model.spec();
    this.date = date;
    final int startMinute = (int) spec.params().get(io.ratesalgo.sim.SimParams.Scalar.SESSION_START_MINUTE_NY);
    this.openEpochNanos =
        date.atTime(LocalTime.ofSecondOfDay(startMinute * 60L)).atZone(NEW_YORK).toEpochSecond() * 1_000_000_000L;
    for (int i = 0; i < spec.instruments(); i++) {
      indexById.put(spec.instrument(i).id(), i);
    }
    this.bookInUse = new boolean[spec.instruments()];
  }

  /** The session's market model. Shared: an order placed through this day moves it. */
  public MarketModel model() {
    return model;
  }

  /** The session date. */
  public LocalDate date() {
    return date;
  }

  /** Session start (07:00 New York), nanoseconds since the epoch. */
  public long openEpochNanos() {
    return openEpochNanos;
  }

  /** Session end, nanoseconds since the epoch. */
  public long closeEpochNanos() {
    return openEpochNanos + spec.sessionNanos();
  }

  /** True if the back-test instrument id belongs to the model's universe. */
  public boolean contains(final int instrumentId) {
    return indexById.containsKey(instrumentId);
  }

  /** Index in the model's universe of a back-test instrument id. */
  public int index(final int instrumentId) {
    final Integer i = indexById.get(instrumentId);
    if (i == null) {
      throw new IllegalArgumentException("instrument id " + instrumentId + " is not in the simulator's universe");
    }
    return i;
  }

  /** Journal id of an RFQ: unique across sessions, in arrival order within one. */
  public long rfqId(final SimRfq rfq) {
    return date.toEpochDay() * RFQ_IDS_PER_DAY + 1 + rfq.number();
  }

  /** The RFQ with this journal id. */
  public SimRfq rfq(final long rfqId) {
    final long number = rfqId - date.toEpochDay() * RFQ_IDS_PER_DAY - 1;
    if (number < 0 || number >= model.rfqs().size()) {
      throw new IllegalArgumentException("rfqId " + rfqId + " is not of the session of " + date);
    }
    return model.rfqs().get((int) number);
  }

  /** The unrounded latent mid of an instrument at an epoch time, quoted units. */
  public double latentMid(final int instrumentId, final long epochNanos) {
    return model.latentMid(index(instrumentId), epochNanos - openEpochNanos);
  }

  /** X1 at an epoch time: changes only when the desk's own orders move the level factor. */
  public double levelOffset(final long epochNanos) {
    return model.levelOffset(epochNanos - openEpochNanos);
  }

  /** Grid step that contains an epoch time. */
  public int step(final long epochNanos) {
    return spec.stepAt(epochNanos - openEpochNanos);
  }

  /** Quantity units of the model per minor unit of the back-test, for an instrument. */
  private double unitsPerMinor(final int instrument) {
    return 1.0 / (100.0 * spec.instrument(instrument).unitFaceUsd());
  }

  // ------------------------------------------------------------------ RFQs

  /**
   * Settles an RFQ with the model's best-price rule.
   *
   * @param deskPrice the desk's price on the client's side, quoted units; NaN if it did not quote
   * @param deskDelayNanos when the desk's quote reached the client, nanoseconds after the RFQ arrived
   */
  public Settlement outcome(final long rfqId, final double deskPrice, final long deskDelayNanos) {
    final SimRfq r = rfq(rfqId);
    double best = Double.NaN;
    for (int d = 0; d < r.competitors(); d++) {
      if (r.answers(d)) {
        final double q = model.competitorQuote(r, d);
        if (Double.isNaN(best) || (r.clientBuys() ? q < best : q > best)) {
          best = q;
        }
      }
    }
    return new Settlement(model.decide(r, deskPrice, deskDelayNanos), best);
  }

  // ------------------------------------------------------------------ orders

  private BookModel book(final int instrument) {
    bookInUse[instrument] = true;
    return model.book(instrument);
  }

  /**
   * Fills a market order against the model's book and returns the average price in quoted units.
   * What the five displayed levels cannot fill is filled one tick beyond the last of them: a
   * declared penalty for an order larger than the book, which keeps the back-tester's
   * one-order-one-fill accounting.
   */
  public double market(final int instrumentId, final boolean buy, final long quantityMinor, final long epochNanos) {
    final int i = index(instrumentId);
    final BookModel b = book(i);
    final long t = epochNanos - openEpochNanos;
    final double units = quantityMinor * unitsPerMinor(i);
    b.advanceTo(t);
    final int last = spec.params().bookLevels() - 1;
    final double beyond = b.levelPrice(!buy, last) + (buy ? 1.0 : -1.0) * spec.instrument(i).tick();
    final BookModel.Execution e = b.submit(t, buy, units, Double.NaN, true);
    double notional = e.notional();
    final double rest = units - e.filledUnits();
    if (rest > 0.0) {
      notional += rest * beyond;
      b.ownMarketable(t, buy, rest);
    }
    collect(i, b);
    return notional / units;
  }

  /**
   * Places a limit order on the model's book and returns a handle. Any part that trades at once
   * is counted as filled; the order is complete when {@link #filledMinor} reaches its quantity.
   */
  public int rest(
      final int instrumentId, final boolean buy, final long quantityMinor, final double limitPrice, final long epochNanos) {
    final int i = index(instrumentId);
    final BookModel b = book(i);
    final double units = quantityMinor * unitsPerMinor(i);
    final BookModel.Execution e = b.submit(epochNanos - openEpochNanos, buy, units, limitPrice, false);
    final int handle = nextHandle++;
    working.put(handle, new Working(i, e.restingId(), units, e.filledUnits()));
    collect(i, b);
    return handle;
  }

  /** Quantity of a resting order filled so far, minor units. */
  public double filledMinor(final int handle) {
    final Working w = working.get(handle);
    return w == null ? 0.0 : w.filledUnits / unitsPerMinor(w.instrument);
  }

  /** True once a resting order has traded its whole quantity. */
  public boolean complete(final int handle) {
    final Working w = working.get(handle);
    return w != null && w.filledUnits >= w.totalUnits * (1.0 - 1e-12);
  }

  /** Cancels what is left of a resting order and returns the quantity it had filled, minor units. */
  public double cancel(final int handle) {
    final Working w = working.remove(handle);
    if (w == null) {
      return 0.0;
    }
    if (w.bookOrderId != 0) {
      model.book(w.instrument).cancel(w.bookOrderId);
    }
    return w.filledUnits / unitsPerMinor(w.instrument);
  }

  /** Brings every book the desk has used to an epoch time and collects the fills of resting orders. */
  public void advance(final long epochNanos) {
    final long t = epochNanos - openEpochNanos;
    for (int i = 0; i < bookInUse.length; i++) {
      if (bookInUse[i]) {
        final BookModel b = model.book(i);
        b.advanceTo(t);
        collect(i, b);
      }
    }
  }

  private void collect(final int instrument, final BookModel b) {
    for (final BookModel.PassiveFill f : b.drainPassiveFills()) {
      for (final Working w : working.values()) {
        if (w.instrument == instrument && w.bookOrderId == f.orderId()) {
          w.filledUnits += f.quantityUnits();
        }
      }
    }
  }

  /**
   * The live top of an instrument's book at an epoch time, as a venue would show it: on each side
   * the best level that displays at least the minimum quantity, its price in quoted units and its
   * displayed quantity in whole lots, minor units. Unlike the journal's book (written with no desk
   * in the market) this includes the desk's own impact and the depth it has taken.
   *
   * @return {bid price, bid quantity, ask price, ask quantity}
   */
  public double[] top(final int instrumentId, final long epochNanos) {
    final int i = index(instrumentId);
    final BookModel b = book(i);
    b.advanceTo(epochNanos - openEpochNanos);
    collect(i, b);
    final SimInstrument inst = spec.instrument(i);
    final double minorPerUnit = inst.unitFaceUsd() * 100.0;
    final double[] out = new double[4];
    for (int side = 0; side < 2; side++) {
      final boolean bid = side == 0;
      int level = 0;
      while (level < spec.params().bookLevels() - 1 && b.displayedUnits(bid, level) < inst.minQuantity()) {
        level++;
      }
      final double lots = Math.floor(b.displayedUnits(bid, level) / inst.minQuantity());
      out[2 * side] = b.levelPrice(bid, level);
      out[2 * side + 1] = Math.round(lots * inst.minQuantity() * minorPerUnit);
    }
    return out;
  }

  /** The instrument a back-test instrument id maps to. */
  public SimInstrument instrument(final int instrumentId) {
    return spec.instrument(index(instrumentId));
  }
}
