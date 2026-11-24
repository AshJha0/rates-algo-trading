package io.ratesalgo.core;

import java.nio.ByteBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Counters written by the loop thread and readable from any thread.
 *
 * <p>The loop increments with a release store (a plain store on x86), and readers use a volatile
 * load, so a monitoring thread sees a value no older than the last store it observed. Each counter
 * sits on its own 64-byte line so a reader does not slow the writer.
 */
public final class LoopCounters {

  /** Inbound messages accepted from rings (live) or frames dispatched (replay). */
  public static final int INBOUND = 0;

  /** Outbound messages produced by the loop, delivered or not. */
  public static final int OUTBOUND = 1;

  /** Outbound messages dropped because a gateway ring was full. */
  public static final int OUTBOUND_DROPPED = 2;

  /** Backpressure events raised. */
  public static final int BACKPRESSURE_EVENTS = 3;

  /** Inbound messages with no registered handler (journaled, not dispatched). */
  public static final int UNHANDLED = 4;

  /** Duty cycles in which at least one timer fired (each journals a clock tick). */
  public static final int TIMER_TICKS = 5;

  /** Errors recorded by the runner. */
  public static final int ERRORS = 6;

  /** Duty cycles run: a heartbeat a supervisor on another thread reads to see the loop is alive. */
  public static final int DUTY_CYCLES = 7;

  /** Must-deliver outbound messages (cancels, withdrawals) held for retry after a full ring. */
  public static final int OUTBOUND_RETRY_QUEUED = 8;

  /** Held messages delivered to their gateway ring on a later duty cycle. */
  public static final int OUTBOUND_RETRIED = 9;

  /** Must-deliver messages lost because the retry buffer was full as well. */
  public static final int OUTBOUND_RETRY_LOST = 10;

  /** Conflatable inputs replaced by a newer one with the same key before dispatch (never journaled). */
  public static final int CONFLATED = 11;

  /** Number of counters. */
  public static final int COUNT = 12;

  private static final int STRIDE = 64;

  private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(COUNT * STRIDE));

  void increment(final int id) {
    final int offset = id * STRIDE;
    buffer.putLongRelease(offset, buffer.getLong(offset) + 1);
  }

  /** The current value of counter {@code id}; safe from any thread. */
  public long get(final int id) {
    return buffer.getLongVolatile(id * STRIDE);
  }

  /** A short name for counter {@code id}, for reports. */
  public static String name(final int id) {
    return switch (id) {
      case INBOUND -> "inbound";
      case OUTBOUND -> "outbound";
      case OUTBOUND_DROPPED -> "outboundDropped";
      case BACKPRESSURE_EVENTS -> "backpressureEvents";
      case UNHANDLED -> "unhandled";
      case TIMER_TICKS -> "timerTicks";
      case ERRORS -> "errors";
      case DUTY_CYCLES -> "dutyCycles";
      case OUTBOUND_RETRY_QUEUED -> "outboundRetryQueued";
      case OUTBOUND_RETRIED -> "outboundRetried";
      case OUTBOUND_RETRY_LOST -> "outboundRetryLost";
      case CONFLATED -> "conflated";
      default -> throw new IllegalArgumentException("no counter " + id);
    };
  }
}
