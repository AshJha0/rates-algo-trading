package io.ratesalgo.fix.order;

import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.LongHashSet;

/**
 * Order state rebuilt from execution reports, with the rules of API_SPEC.md Section 10.4:
 *
 * <ul>
 *   <li>A fill (ExecType F) is identified by its ExecID. A repeated ExecID is a duplicate and is
 *       dropped. Cumulative quantity is the sum of distinct fills, so fills that arrive out of
 *       order still add up to the right total.
 *   <li>A status report (New, Cancelled, Rejected, Order Status) that would move a terminal order
 *       (Filled, Cancelled, Rejected) back to a live state is stale and dropped.
 *   <li>A fill on an order already cancelled is a late fill: it is applied, because the venue
 *       traded, and counted.
 *   <li>A trade cancel (ExecType H) names the busted ExecID in ExecRefID; its quantity is removed
 *       from the order and the bust is reported. An unknown ExecRefID is counted and ignored.
 * </ul>
 *
 * <p>Capacity is fixed at construction; {@link #clear()} starts a new trading day. Single
 * threaded.
 */
public final class OrderStateTracker {

  /** {@link #apply} result: emit an execution report built from the tracker's state. */
  public static final int EMIT = 0;

  /** {@link #apply} result: a duplicate ExecID, dropped. */
  public static final int DUPLICATE = 1;

  /** {@link #apply} result: a stale status report, dropped. */
  public static final int STALE = 2;

  /** {@link #apply} result: a pending state (PendingNew, PendingCancel, PendingReplace), dropped. */
  public static final int PENDING = 3;

  /** {@link #apply} result: a bust of a known fill; emit a trade bust. */
  public static final int BUST = 4;

  /** {@link #apply} result: a bust naming an unknown ExecID, ignored. */
  public static final int UNKNOWN_BUST = 5;

  /** {@link #apply} result: the tracker is full; the report is emitted as received. */
  public static final int FULL = 6;

  private static final byte NEW = '0';
  private static final byte PARTIAL = '1';
  private static final byte FILLED = '2';
  private static final byte CANCELLED = '4';
  private static final byte REJECTED = '8';

  private final int capacity;
  private final long[] orderQty;
  private final long[] cumQty;
  private final byte[] status;
  private final boolean[] cancelled;
  private final Long2LongHashMap slotByClOrdId;
  private final Long2LongHashMap execQty;
  private final Long2LongHashMap execPrice;
  private final LongHashSet statusExecIds;
  private int used;

  private byte lastStatus;
  private long lastCum;
  private long lastLeaves;
  private long lastBustQty;
  private long lastBustPrice;

  private long duplicates;
  private long stale;
  private long lateFills;
  private long busts;
  private long unknownBusts;
  private long full;

  /** Creates a tracker for up to {@code maxOrders} orders and {@code maxExecutions} fills. */
  public OrderStateTracker(final int maxOrders, final int maxExecutions) {
    this.capacity = maxOrders;
    this.orderQty = new long[maxOrders];
    this.cumQty = new long[maxOrders];
    this.status = new byte[maxOrders];
    this.cancelled = new boolean[maxOrders];
    this.slotByClOrdId = new Long2LongHashMap(maxOrders * 2, 0.6f, -1L);
    this.execQty = new Long2LongHashMap(maxExecutions * 2, 0.6f, Long.MIN_VALUE);
    this.execPrice = new Long2LongHashMap(maxExecutions * 2, 0.6f, 0L);
    this.statusExecIds = new LongHashSet(maxExecutions * 2);
  }

  /** Forgets every order and execution; capacity is kept. */
  public void clear() {
    slotByClOrdId.clear();
    execQty.clear();
    execPrice.clear();
    statusExecIds.clear();
    used = 0;
  }

  /** Records an order we sent, so its quantity is known before the first report. */
  public void onNewOrder(final long clOrdId, final long quantity) {
    final int slot = slot(clOrdId, quantity);
    if (slot >= 0) {
      orderQty[slot] = quantity;
    }
  }

  /** The quantity recorded for an order, or 0 if the tracker does not know it. */
  public long orderQuantity(final long clOrdId) {
    final long s = slotByClOrdId.get(clOrdId);
    return s < 0 ? 0L : orderQty[(int) s];
  }

  private int slot(final long clOrdId, final long quantity) {
    final long s = slotByClOrdId.get(clOrdId);
    if (s >= 0) {
      return (int) s;
    }
    if (used == capacity) {
      return -1;
    }
    final int slot = used++;
    slotByClOrdId.put(clOrdId, slot);
    orderQty[slot] = quantity;
    cumQty[slot] = 0;
    status[slot] = NEW;
    cancelled[slot] = false;
    return slot;
  }

  /**
   * Applies one execution report.
   *
   * @param execType FIX ExecType (150)
   * @param clOrdId order id the report is for
   * @param origClOrdId order being replaced, 0 if none
   * @param execId ExecID (17), or ExecRefID (19) for a trade cancel
   * @param ordStatus FIX OrdStatus (39)
   * @param lastQty fill quantity, 0 for status reports
   * @param lastPrice fill price mantissa
   * @param quantity order quantity carried by the report, 0 if absent
   * @return one of the result constants
   */
  public int apply(
      final byte execType,
      final long clOrdId,
      final long origClOrdId,
      final long execId,
      final byte ordStatus,
      final long lastQty,
      final long lastPrice,
      final long quantity) {
    if (execType == 'A' || execType == '6' || execType == 'E') {
      return PENDING;
    }
    if (origClOrdId != 0 && (execType == '5' || execType == '4')) {
      // A replace moves the order to its new ClOrdID; a cancel ack carries the cancel request's
      // ClOrdID, which becomes another name for the same order so later reports on either apply.
      final long old = execType == '5' ? slotByClOrdId.remove(origClOrdId) : slotByClOrdId.get(origClOrdId);
      if (old >= 0 && !slotByClOrdId.containsKey(clOrdId)) {
        slotByClOrdId.put(clOrdId, old);
      }
    }
    final int slot = slot(clOrdId, quantity != 0 ? quantity : lastQty);
    if (slot < 0) {
      full++;
      return FULL;
    }
    if (quantity != 0 && execType != 'F') {
      orderQty[slot] = quantity;
    }
    if (execType == 'H') {
      final long q = execQty.remove(execId);
      if (q == Long.MIN_VALUE) {
        unknownBusts++;
        return UNKNOWN_BUST;
      }
      lastBustPrice = execPrice.remove(execId);
      lastBustQty = q;
      cumQty[slot] -= q;
      if (!cancelled[slot]) {
        status[slot] = cumQty[slot] == 0 ? NEW : PARTIAL;
      }
      busts++;
      snapshot(slot);
      return BUST;
    }
    if (execType == 'F') {
      if (execQty.containsKey(execId)) {
        duplicates++;
        return DUPLICATE;
      }
      execQty.put(execId, lastQty);
      execPrice.put(execId, lastPrice);
      cumQty[slot] += lastQty;
      if (cancelled[slot]) {
        lateFills++;
      }
      if (orderQty[slot] > 0 && cumQty[slot] >= orderQty[slot]) {
        status[slot] = FILLED;
      } else if (!cancelled[slot]) {
        status[slot] = PARTIAL;
      }
      snapshot(slot);
      return EMIT;
    }
    if (execId != 0 && !statusExecIds.add(execId)) {
      duplicates++;
      return DUPLICATE;
    }
    final boolean terminal =
        status[slot] == FILLED || status[slot] == CANCELLED || status[slot] == REJECTED;
    final boolean reportTerminal =
        ordStatus == FILLED || ordStatus == CANCELLED || ordStatus == REJECTED || ordStatus == 'C';
    if (terminal && !reportTerminal) {
      stale++;
      return STALE;
    }
    if (ordStatus == CANCELLED || ordStatus == 'C') {
      cancelled[slot] = true;
      status[slot] = CANCELLED;
    } else if (ordStatus == REJECTED) {
      status[slot] = REJECTED;
    } else if (status[slot] != FILLED) {
      status[slot] = cumQty[slot] == 0 ? NEW : PARTIAL;
    }
    snapshot(slot);
    return EMIT;
  }

  private void snapshot(final int slot) {
    lastStatus = status[slot];
    lastCum = cumQty[slot];
    lastLeaves =
        status[slot] == CANCELLED || status[slot] == REJECTED || status[slot] == FILLED
            ? 0
            : Math.max(0, orderQty[slot] - cumQty[slot]);
  }

  /** FIX OrdStatus after the last {@code EMIT} or {@code BUST}. */
  public byte lastStatus() {
    return lastStatus;
  }

  /** Cumulative quantity after the last {@code EMIT} or {@code BUST}. */
  public long lastCum() {
    return lastCum;
  }

  /** Leaves quantity after the last {@code EMIT} or {@code BUST}. */
  public long lastLeaves() {
    return lastLeaves;
  }

  /** Quantity of the fill removed by the last {@code BUST}. */
  public long lastBustQty() {
    return lastBustQty;
  }

  /** Price of the fill removed by the last {@code BUST}. */
  public long lastBustPrice() {
    return lastBustPrice;
  }

  /** Duplicate ExecIDs dropped. */
  public long duplicates() {
    return duplicates;
  }

  /** Stale status reports dropped. */
  public long stale() {
    return stale;
  }

  /** Fills applied to orders already cancelled. */
  public long lateFills() {
    return lateFills;
  }

  /** Busts applied. */
  public long busts() {
    return busts;
  }

  /** Busts naming an unknown ExecID. */
  public long unknownBusts() {
    return unknownBusts;
  }

  /** Reports seen while the tracker was full. */
  public long full() {
    return full;
  }

  /** Orders tracked. */
  public int size() {
    return used;
  }
}
