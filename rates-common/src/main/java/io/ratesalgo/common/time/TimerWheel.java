package io.ratesalgo.common.time;

import java.util.Arrays;
import org.agrona.collections.Long2LongHashMap;

/**
 * A hashed timer wheel for the event loop: no scheduler thread, no allocation after warm-up, and
 * deterministic firing order.
 *
 * <p>Time is divided into ticks of {@code tickNanos}, numbered from {@code startNanos}. Tick
 * {@code t} covers the interval {@code (start + (t-1) * tick, start + t * tick]}. A timer is placed
 * in the slot of the tick its deadline falls in, with a remaining-rotation count when the deadline
 * is more than one wheel away. {@link #poll(long)} walks every tick that has fully elapsed since
 * the previous poll, then looks into the tick that contains {@code nowNanos}, and fires every timer
 * whose deadline is at or before {@code nowNanos}, in order of deadline, then of timer id. The
 * firing order does not depend on how often {@code poll} is called, which is what lets replay
 * reproduce a live run.
 *
 * <p>Timers live in flat arrays. The wheel grows them when full, which allocates; size {@code
 * initialCapacity} for the expected number of live timers and the hot path stays allocation-free.
 * Handlers may schedule or cancel timers while being fired; a timer scheduled from a handler fires
 * no earlier than the next poll. A handler that throws does not stop the others due in the same
 * poll: they all fire, then the first exception propagates with the rest suppressed.
 *
 * <p>Single-threaded by design: a wheel belongs to one event loop.
 */
public final class TimerWheel {

  private static final int NONE = -1;

  private final long tickNanos;
  private final int wheelMask;
  private final long startNanos;
  private final int[] slotHead;
  private final Long2LongHashMap indexById;

  private long[] deadline;
  private long[] timerId;
  private int[] remainingRotations;
  private int[] next;
  private int[] prev;
  private int[] slotOf;
  private TimerHandler[] handler;
  private int freeHead;
  private int capacity;

  private long nextTimerId = 1;
  private long lastPolledTick = -1;
  private int liveCount;

  private int[] fireList;
  private long[] fireDeadline;
  private long[] fireId;

  /**
   * @param tickNanos resolution; a deadline fires at the first poll at or after it, and the wheel
   *     never fires early
   * @param wheelSize number of slots, rounded up to a power of two
   * @param initialCapacity number of timers that can be live without allocation
   * @param startNanos the {@code nanoTime} reading that is tick zero; usually the clock's reading
   *     when the wheel is built
   */
  public TimerWheel(
      final long tickNanos, final int wheelSize, final int initialCapacity, final long startNanos) {
    if (tickNanos <= 0) {
      throw new IllegalArgumentException("tickNanos must be positive: " + tickNanos);
    }
    if (wheelSize <= 0) {
      throw new IllegalArgumentException("wheelSize must be positive: " + wheelSize);
    }
    if (initialCapacity <= 0) {
      throw new IllegalArgumentException("initialCapacity must be positive: " + initialCapacity);
    }
    this.tickNanos = tickNanos;
    final int size = wheelSize == 1 ? 1 : Integer.highestOneBit(wheelSize - 1) << 1;
    this.wheelMask = size - 1;
    this.startNanos = startNanos;
    this.slotHead = new int[size];
    Arrays.fill(slotHead, NONE);
    this.indexById = new Long2LongHashMap(initialCapacity * 2, 0.6f, NONE);
    allocateStorage(initialCapacity);
    this.fireList = new int[initialCapacity];
    this.fireDeadline = new long[initialCapacity];
    this.fireId = new long[initialCapacity];
  }

  /** Number of timers scheduled and neither fired nor cancelled. */
  public int liveCount() {
    return liveCount;
  }

  /** Tick length in nanoseconds. */
  public long tickNanos() {
    return tickNanos;
  }

  /**
   * Schedules {@code timerHandler} to fire at the first {@link #poll(long)} whose {@code nowNanos}
   * is at or after {@code deadlineNanos}.
   *
   * @return a positive timer id, unique for the life of the wheel
   */
  public long schedule(final long deadlineNanos, final TimerHandler timerHandler) {
    if (timerHandler == null) {
      throw new NullPointerException("timerHandler");
    }
    if (freeHead == NONE) {
      grow();
    }
    final int index = freeHead;
    freeHead = next[index];

    final long id = nextTimerId++;
    deadline[index] = deadlineNanos;
    timerId[index] = id;
    handler[index] = timerHandler;
    link(index, ceilTick(deadlineNanos));
    indexById.put(id, index);
    liveCount++;
    return id;
  }

  /**
   * Cancels a live timer.
   *
   * @return true if the timer was live and is now cancelled; false if it has fired, was cancelled
   *     already, or never existed
   */
  public boolean cancel(final long id) {
    final long found = indexById.remove(id);
    if (found == NONE) {
      return false;
    }
    final int index = (int) found;
    unlink(index);
    release(index);
    liveCount--;
    return true;
  }

  /**
   * Fires every timer whose deadline is at or before {@code nowNanos}, in deadline then id order.
   *
   * @return the number of timers fired
   */
  public int poll(final long nowNanos) {
    final long floorTick = floorTick(nowNanos);
    int count = 0;

    // Every tick that has fully elapsed: a timer there with no rotations left is due by
    // construction (its deadline is at or before the end of its tick).
    while (lastPolledTick < floorTick) {
      final long from = lastPolledTick + 1;
      final long to = Math.min(floorTick, from + wheelMask);
      for (long tick = from; tick <= to; tick++) {
        int index = slotHead[(int) (tick & wheelMask)];
        while (index != NONE) {
          final int following = next[index];
          if (remainingRotations[index] > 0) {
            remainingRotations[index]--;
          } else if (deadline[index] <= nowNanos) {
            unlink(index);
            count = collect(count, index);
          }
          index = following;
        }
      }
      lastPolledTick = to;
    }

    // The tick that contains nowNanos: fire what is due inside it, without touching rotation
    // counts, since the tick will be walked in full on a later poll.
    int index = slotHead[(int) ((floorTick + 1) & wheelMask)];
    while (index != NONE) {
      final int following = next[index];
      if (remainingRotations[index] == 0 && deadline[index] <= nowNanos) {
        unlink(index);
        count = collect(count, index);
      }
      index = following;
    }

    if (count == 0) {
      return 0;
    }
    sortFireList(count);
    RuntimeException first = null;
    for (int i = 0; i < count; i++) {
      final int fired = fireList[i];
      final TimerHandler h = handler[fired];
      final long id = timerId[fired];
      indexById.remove(id);
      release(fired);
      liveCount--;
      try {
        h.onTimer(id, nowNanos);
      } catch (final RuntimeException e) {
        // every timer due in this poll still fires; the first failure is reported afterwards
        if (first == null) {
          first = e;
        } else {
          first.addSuppressed(e);
        }
      }
    }
    if (first != null) {
      throw first;
    }
    return count;
  }

  private long ceilTick(final long nanos) {
    final long elapsed = nanos - startNanos;
    if (elapsed <= 0) {
      return 0;
    }
    return (elapsed + tickNanos - 1) / tickNanos;
  }

  private long floorTick(final long nanos) {
    final long elapsed = nanos - startNanos;
    if (elapsed < 0) {
      return -1;
    }
    return elapsed / tickNanos;
  }

  private void link(final int index, final long tick) {
    final long nextTick = lastPolledTick + 1;
    final long base = Math.max(tick, nextTick);
    remainingRotations[index] = (int) ((base - nextTick) / (wheelMask + 1L));
    final int slot = (int) (base & wheelMask);
    slotOf[index] = slot;
    prev[index] = NONE;
    next[index] = slotHead[slot];
    if (slotHead[slot] != NONE) {
      prev[slotHead[slot]] = index;
    }
    slotHead[slot] = index;
  }

  private void unlink(final int index) {
    final int slot = slotOf[index];
    final int p = prev[index];
    final int n = next[index];
    if (p == NONE) {
      slotHead[slot] = n;
    } else {
      next[p] = n;
    }
    if (n != NONE) {
      prev[n] = p;
    }
    slotOf[index] = NONE;
  }

  private void release(final int index) {
    handler[index] = null;
    timerId[index] = 0;
    next[index] = freeHead;
    freeHead = index;
  }

  private int collect(final int count, final int index) {
    if (count == fireList.length) {
      fireList = Arrays.copyOf(fireList, count * 2);
      fireDeadline = Arrays.copyOf(fireDeadline, count * 2);
      fireId = Arrays.copyOf(fireId, count * 2);
    }
    fireList[count] = index;
    fireDeadline[count] = deadline[index];
    fireId[count] = timerId[index];
    return count + 1;
  }

  /** Insertion sort by (deadline, id); fire lists are short and usually nearly ordered. */
  private void sortFireList(final int count) {
    for (int i = 1; i < count; i++) {
      final int idx = fireList[i];
      final long d = fireDeadline[i];
      final long id = fireId[i];
      int j = i - 1;
      while (j >= 0 && (fireDeadline[j] > d || (fireDeadline[j] == d && fireId[j] > id))) {
        fireList[j + 1] = fireList[j];
        fireDeadline[j + 1] = fireDeadline[j];
        fireId[j + 1] = fireId[j];
        j--;
      }
      fireList[j + 1] = idx;
      fireDeadline[j + 1] = d;
      fireId[j + 1] = id;
    }
  }

  private void allocateStorage(final int newCapacity) {
    deadline = new long[newCapacity];
    timerId = new long[newCapacity];
    remainingRotations = new int[newCapacity];
    next = new int[newCapacity];
    prev = new int[newCapacity];
    slotOf = new int[newCapacity];
    handler = new TimerHandler[newCapacity];
    for (int i = 0; i < newCapacity; i++) {
      next[i] = i + 1;
      slotOf[i] = NONE;
    }
    next[newCapacity - 1] = NONE;
    freeHead = 0;
    capacity = newCapacity;
  }

  private void grow() {
    final int oldCapacity = capacity;
    final int newCapacity = oldCapacity * 2;
    deadline = Arrays.copyOf(deadline, newCapacity);
    timerId = Arrays.copyOf(timerId, newCapacity);
    remainingRotations = Arrays.copyOf(remainingRotations, newCapacity);
    next = Arrays.copyOf(next, newCapacity);
    prev = Arrays.copyOf(prev, newCapacity);
    slotOf = Arrays.copyOf(slotOf, newCapacity);
    handler = Arrays.copyOf(handler, newCapacity);
    for (int i = oldCapacity; i < newCapacity; i++) {
      next[i] = i + 1;
      slotOf[i] = NONE;
    }
    next[newCapacity - 1] = NONE;
    freeHead = oldCapacity;
    capacity = newCapacity;
  }
}
