package io.ratesalgo.router;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.messages.sbe.ChildOrderTransitionEncoder;
import io.ratesalgo.messages.sbe.ChildState;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TransitionCause;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Every child order the router has sent, with its state machine (API_SPEC.md Section 15.4).
 *
 * <p>Quantities obey {@code cum + leaves + cancelled = ordered} at every step, where {@code
 * cancelled} is quantity the venue has confirmed it will never fill and {@code leaves} is quantity
 * that may still fill (working on the venue, or filled there with the report not yet received).
 * Fills are counted once per {@code (venueId, execId)}, so duplicated and reordered reports cannot
 * change the cumulative quantity; a fill that would take {@code cum} above {@code ordered} is
 * refused and counted as an anomaly. Status reports that would move the state backwards are
 * stale and ignored. When a cancel is confirmed, {@code cancelled = ordered - max(cum,
 * venueCum)}, where {@code venueCum} is the largest cumulative quantity any report has carried,
 * so fills the venue made before the cancel but whose reports arrive late still count and are
 * never re-routed. Every state change and every applied fill is written as a
 * ChildOrderTransition (template 82) to the audit sink.
 *
 * <p>Storage is flat arrays sized at construction; nothing allocates after warm-up as long as
 * the live child count stays within capacity and the day's fills within the execution-id set's
 * initial capacity. Single-threaded.
 */
public final class ChildOrderBook {

  /** The report was applied as a fill. */
  public static final int FILL = 1;
  /** The report changed the state. */
  public static final int STATE = 2;
  /** The report carried a fill already counted. */
  public static final int DUPLICATE = 4;
  /** The report would have moved the state backwards and was ignored. */
  public static final int STALE = 8;
  /** The report named an unknown order. */
  public static final int UNKNOWN = 16;
  /** The report contradicted the book (overfill, wrong venue, bad ids). */
  public static final int ANOMALY = 32;

  private static final long NONE = -1L;
  private static final long MAX_EXEC_ID = 1L << 43;
  private static final int MAX_VENUE_ID = 1 << 20;

  private final Clock clock;
  private final FrameSink audit;
  private final int capacity;

  private final long[] clOrdId;
  private final long[] parentId;
  private final int[] venueId;
  private final Side[] side;
  private final long[] price;
  private final long[] ordered;
  private final long[] cum;
  private final long[] venueCum;
  private final long[] cancelled;
  private final double[] notional;
  private final ChildState[] state;
  private final ChildState[] beforeCancel;
  private final boolean[] replaceIntent;
  private final boolean[] passive;
  private final boolean[] inUse;
  private final int[] freeStack;
  private int freeTop;
  private int highWater;

  private final Long2LongHashMap slotById;
  private final LongHashSet execSeen;

  private final UnsafeBuffer auditBuffer = new UnsafeBuffer(new byte[128]);
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final ChildOrderTransitionEncoder transition = new ChildOrderTransitionEncoder();

  private int lastSlot = -1;
  private long lastFill;
  private long lastFillPrice;
  private long lastFreed;

  private long duplicates;
  private long stale;
  private long unknown;
  private long anomalies;

  /**
   * @param capacity most child orders alive at once
   * @param expectedFills fills per day the execution-id set holds without growing
   * @param clock source of audit timestamps
   * @param audit where ChildOrderTransition records go
   */
  public ChildOrderBook(
      final int capacity, final int expectedFills, final Clock clock, final FrameSink audit) {
    if (capacity < 1 || expectedFills < 1) {
      throw new IllegalArgumentException(
          "capacity and expectedFills must be >= 1: " + capacity + ", " + expectedFills);
    }
    this.clock = clock;
    this.audit = audit;
    this.capacity = capacity;
    clOrdId = new long[capacity];
    parentId = new long[capacity];
    venueId = new int[capacity];
    side = new Side[capacity];
    price = new long[capacity];
    ordered = new long[capacity];
    cum = new long[capacity];
    venueCum = new long[capacity];
    cancelled = new long[capacity];
    notional = new double[capacity];
    state = new ChildState[capacity];
    beforeCancel = new ChildState[capacity];
    replaceIntent = new boolean[capacity];
    passive = new boolean[capacity];
    inUse = new boolean[capacity];
    freeStack = new int[capacity];
    for (int i = 0; i < capacity; i++) {
      freeStack[i] = capacity - 1 - i;
    }
    freeTop = capacity;
    slotById = new Long2LongHashMap(capacity * 2, 0.65f, NONE);
    execSeen = new LongHashSet(expectedFills * 2);
  }

  /** Records a child order as sent; returns its slot. */
  public int open(
      final long id,
      final long parent,
      final int venue,
      final Side orderSide,
      final long limitPrice,
      final long quantity,
      final boolean isPassive) {
    if (quantity <= 0) {
      throw new IllegalArgumentException("child quantity must be > 0: " + quantity);
    }
    if (slotById.get(id) != NONE) {
      throw new IllegalArgumentException("duplicate clOrdId " + id);
    }
    if (freeTop == 0) {
      throw new IllegalStateException("child order capacity " + capacity + " exhausted");
    }
    final int s = freeStack[--freeTop];
    highWater = Math.max(highWater, s + 1);
    inUse[s] = true;
    clOrdId[s] = id;
    parentId[s] = parent;
    venueId[s] = venue;
    side[s] = orderSide;
    price[s] = limitPrice;
    ordered[s] = quantity;
    cum[s] = 0;
    venueCum[s] = 0;
    cancelled[s] = 0;
    notional[s] = 0.0;
    state[s] = ChildState.New;
    beforeCancel[s] = ChildState.New;
    replaceIntent[s] = false;
    passive[s] = isPassive;
    slotById.put(id, s);
    emit(s, ChildState.New, TransitionCause.Sent, 0);
    return s;
  }

  /**
   * Marks a live child as cancel requested. {@code replace} records that the quantity will be
   * re-routed, which makes the terminal state Replaced instead of Cancelled.
   *
   * @return false if the child is unknown or not live
   */
  public boolean requestCancel(final long id, final boolean replace) {
    final int s = (int) slotById.get(id);
    if (s < 0 || !isLive(state[s])) {
      return false;
    }
    final ChildState from = state[s];
    beforeCancel[s] = from;
    replaceIntent[s] = replace;
    state[s] = ChildState.CancelRequested;
    emit(s, from, TransitionCause.CancelSent, 0);
    return true;
  }

  /**
   * Applies one ExecutionReport.
   *
   * @return a combination of {@link #FILL}, {@link #STATE}, {@link #DUPLICATE}, {@link #STALE},
   *     {@link #UNKNOWN}, {@link #ANOMALY}; {@link #lastFill()} and {@link #lastFreed()} describe
   *     the effect
   */
  public int onExecutionReport(
      final int venue,
      final long id,
      final long execId,
      final OrdStatus status,
      final long lastPrice,
      final long lastQuantity,
      final long cumQuantity) {
    lastFill = 0;
    lastFreed = 0;
    lastSlot = -1;
    final int s = (int) slotById.get(id);
    if (s < 0) {
      unknown++;
      return UNKNOWN;
    }
    lastSlot = s;
    if (venue != venueId[s] || status == null || status == OrdStatus.NULL_VAL) {
      anomalies++;
      return ANOMALY;
    }
    int flags = 0;
    if (lastQuantity > 0) {
      flags |= fill(s, venue, execId, lastPrice, lastQuantity);
    }
    if (cumQuantity > venueCum[s]) {
      venueCum[s] = Math.min(cumQuantity, ordered[s]);
    }
    switch (status) {
      case New -> {
        if (state[s] == ChildState.New) {
          move(s, ChildState.Acknowledged, TransitionCause.Ack, execId);
          flags |= STATE;
        } else if ((flags & FILL) == 0) {
          flags |= STALE;
        }
      }
      case PartiallyFilled -> {
        if (state[s] == ChildState.New) {
          move(s, ChildState.Acknowledged, TransitionCause.Ack, execId);
          flags |= STATE;
        } else if ((flags & FILL) == 0 && (flags & DUPLICATE) == 0) {
          flags |= STALE;
        }
      }
      case Filled -> {
        venueCum[s] = ordered[s];
        if ((flags & (FILL | DUPLICATE)) == 0 && isTerminal(state[s])) {
          flags |= STALE;
        }
      }
      case Cancelled -> {
        if (isLive(state[s]) || state[s] == ChildState.CancelRequested) {
          flags |= finish(s, cancelState(s), TransitionCause.CancelConfirmed, execId);
        } else if ((flags & FILL) == 0) {
          flags |= STALE;
        }
      }
      case Rejected -> {
        if (state[s] == ChildState.New || state[s] == ChildState.Acknowledged) {
          flags |= finish(s, ChildState.Rejected, TransitionCause.Rejected, execId);
        } else {
          flags |= STALE;
        }
      }
      default -> {
        anomalies++;
        flags |= ANOMALY;
      }
    }
    if ((flags & DUPLICATE) != 0) {
      duplicates++;
    }
    if ((flags & STALE) != 0 && (flags & (FILL | STATE)) == 0) {
      stale++;
    }
    return flags;
  }

  /**
   * The venue refused a cancel: the child returns to the state it had before the request.
   *
   * @return true if the child was cancel requested
   */
  public boolean onCancelReject(final int venue, final long id) {
    final int s = (int) slotById.get(id);
    if (s < 0 || venue != venueId[s] || state[s] != ChildState.CancelRequested) {
      return false;
    }
    ChildState back = beforeCancel[s];
    if (cum[s] > 0 && back != ChildState.PartiallyFilled) {
      back = ChildState.PartiallyFilled;
    }
    replaceIntent[s] = false;
    move(s, back, TransitionCause.CancelRejected, 0);
    return true;
  }

  /**
   * Mass-status reconciliation of one child after a reconnect.
   *
   * @param venueKnows false if the venue has no record of the order
   * @param status the venue's status when it knows the order
   * @param venueCumQuantity the venue's cumulative quantity when it knows the order
   * @return the quantity this reconciliation released for re-routing
   */
  public long reconcile(
      final long id, final boolean venueKnows, final OrdStatus status, final long venueCumQuantity) {
    lastFreed = 0;
    lastFill = 0;
    final int s = (int) slotById.get(id);
    if (s < 0 || isTerminal(state[s])) {
      return 0;
    }
    if (!venueKnows) {
      finish(s, cancelState(s), TransitionCause.Reconciled, 0);
      return lastFreed;
    }
    if (venueCumQuantity > venueCum[s]) {
      venueCum[s] = Math.min(venueCumQuantity, ordered[s]);
    }
    if (status == OrdStatus.Cancelled || status == OrdStatus.Rejected) {
      finish(
          s,
          status == OrdStatus.Rejected ? ChildState.Rejected : cancelState(s),
          TransitionCause.Reconciled,
          0);
    } else if (status == OrdStatus.Filled) {
      venueCum[s] = ordered[s];
    } else if (state[s] == ChildState.New) {
      move(s, ChildState.Acknowledged, TransitionCause.Reconciled, 0);
    }
    return lastFreed;
  }

  /** Frees the slot of a finished child; returns false if it may still fill. */
  public boolean release(final int s) {
    if (!inUse[s] || !isTerminal(state[s]) || leaves(s) != 0) {
      return false;
    }
    slotById.remove(clOrdId[s]);
    inUse[s] = false;
    freeStack[freeTop++] = s;
    return true;
  }

  /** Forgets the day's execution ids. Call between days, with no child live. */
  public void resetDay() {
    execSeen.clear();
  }

  private int fill(
      final int s, final int venue, final long execId, final long lastPrice, final long qty) {
    if (execId < 0 || execId >= MAX_EXEC_ID || venue < 0 || venue >= MAX_VENUE_ID) {
      anomalies++;
      return ANOMALY;
    }
    final long key = ((long) venue << 43) | execId;
    if (execSeen.contains(key)) {
      return DUPLICATE;
    }
    if (cum[s] + qty > ordered[s]) {
      anomalies++;
      return ANOMALY;
    }
    execSeen.add(key);
    cum[s] += qty;
    notional[s] += (double) lastPrice * qty;
    lastFill = qty;
    lastFillPrice = lastPrice;
    if (cancelled[s] > ordered[s] - cum[s]) {
      // A venue reported a cancel with a cumulative quantity below what it had filled.
      anomalies++;
      cancelled[s] = ordered[s] - cum[s];
    }
    final ChildState from = state[s];
    if (cum[s] == ordered[s]) {
      cancelled[s] = 0;
      state[s] = ChildState.Filled;
    } else if (from == ChildState.New
        || from == ChildState.Acknowledged
        || from == ChildState.PartiallyFilled) {
      state[s] = ChildState.PartiallyFilled;
    }
    emit(s, from, TransitionCause.Fill, execId);
    return FILL | (from != state[s] ? STATE : 0);
  }

  private int finish(final int s, final ChildState to, final TransitionCause cause, final long ex) {
    final long settled = Math.max(cum[s], venueCum[s]);
    final long free = ordered[s] - settled;
    if (free <= 0) {
      // Everything traded on the venue; the outstanding reports will complete the fill.
      return 0;
    }
    cancelled[s] = free;
    lastFreed = free;
    move(s, to, cause, ex);
    return STATE;
  }

  private ChildState cancelState(final int s) {
    return replaceIntent[s] ? ChildState.Replaced : ChildState.Cancelled;
  }

  private void move(final int s, final ChildState to, final TransitionCause cause, final long ex) {
    final ChildState from = state[s];
    state[s] = to;
    emit(s, from, cause, ex);
  }

  private void emit(final int s, final ChildState from, final TransitionCause cause, final long ex) {
    transition
        .wrapAndApplyHeader(auditBuffer, 0, header)
        .clOrdId(clOrdId[s])
        .parentId(parentId[s])
        .venueId(venueId[s])
        .fromState(from)
        .toState(state[s])
        .cause(cause)
        .execId(ex)
        .orderedQuantity(ordered[s])
        .cumQuantity(cum[s])
        .leavesQuantity(leaves(s))
        .cancelledQuantity(cancelled[s])
        .timestampNanos(clock.epochNanos());
    audit.append(
        ChildOrderTransitionEncoder.TEMPLATE_ID,
        ChildOrderTransitionEncoder.SCHEMA_VERSION,
        auditBuffer,
        0,
        MessageHeaderEncoder.ENCODED_LENGTH + ChildOrderTransitionEncoder.BLOCK_LENGTH);
  }

  /** True for New, Acknowledged and PartiallyFilled. */
  public static boolean isLive(final ChildState st) {
    return st == ChildState.New
        || st == ChildState.Acknowledged
        || st == ChildState.PartiallyFilled;
  }

  /** True for Filled, Cancelled, Replaced and Rejected. */
  public static boolean isTerminal(final ChildState st) {
    return st == ChildState.Filled
        || st == ChildState.Cancelled
        || st == ChildState.Replaced
        || st == ChildState.Rejected;
  }

  /** Slot of a child, or -1. */
  public int slotOf(final long id) {
    return (int) slotById.get(id);
  }

  /** Slots below this have been used; iterate {@code [0, highWater())} with {@link #inUse}. */
  public int highWater() {
    return highWater;
  }

  /** Whether a slot holds a child. */
  public boolean inUse(final int s) {
    return inUse[s];
  }

  /** Slot the last report touched, or -1. */
  public int lastSlot() {
    return lastSlot;
  }

  /** Quantity the last report filled. */
  public long lastFill() {
    return lastFill;
  }

  /** Price of the last applied fill. */
  public long lastFillPrice() {
    return lastFillPrice;
  }

  /** Quantity the last report or reconciliation released (confirmed never to fill). */
  public long lastFreed() {
    return lastFreed;
  }

  /** Child's clOrdId. */
  public long clOrdId(final int s) {
    return clOrdId[s];
  }

  /** Child's parent. */
  public long parentId(final int s) {
    return parentId[s];
  }

  /** Child's venue. */
  public int venueId(final int s) {
    return venueId[s];
  }

  /** Child's side. */
  public Side side(final int s) {
    return side[s];
  }

  /** Child's limit price. */
  public long price(final int s) {
    return price[s];
  }

  /** Child's ordered quantity. */
  public long ordered(final int s) {
    return ordered[s];
  }

  /** Child's cumulative filled quantity, counted once per execution id. */
  public long cum(final int s) {
    return cum[s];
  }

  /** Quantity confirmed never to fill. */
  public long cancelled(final int s) {
    return cancelled[s];
  }

  /** Quantity that may still fill: {@code ordered - cum - cancelled}. */
  public long leaves(final int s) {
    return ordered[s] - cum[s] - cancelled[s];
  }

  /** Sum of fill price times quantity, for the average price. */
  public double notional(final int s) {
    return notional[s];
  }

  /** Child's state. */
  public ChildState state(final int s) {
    return state[s];
  }

  /** Whether the child rests passively. */
  public boolean passive(final int s) {
    return passive[s];
  }

  /** Whether a cancel was requested in order to re-route. */
  public boolean replaceIntent(final int s) {
    return replaceIntent[s];
  }

  /** Reports whose fill had already been counted. */
  public long duplicates() {
    return duplicates;
  }

  /** Status reports ignored because they would move the state backwards. */
  public long stale() {
    return stale;
  }

  /** Reports for unknown orders. */
  public long unknown() {
    return unknown;
  }

  /** Reports that contradicted the book. */
  public long anomalies() {
    return anomalies;
  }
}
