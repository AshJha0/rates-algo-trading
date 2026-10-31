package io.ratesalgo.core;

/**
 * Outbound messages to one gateway were dropped since the last event for it. Reused by the loop;
 * one instance per loop.
 */
public final class BackpressureEvent {

  private int gatewayId;
  private long droppedCount;
  private int firstTemplateId;
  private long timestampNanos;

  void set(
      final int gatewayId,
      final long droppedCount,
      final int firstTemplateId,
      final long timestampNanos) {
    this.gatewayId = gatewayId;
    this.droppedCount = droppedCount;
    this.firstTemplateId = firstTemplateId;
    this.timestampNanos = timestampNanos;
  }

  /** The gateway whose outbound ring was full. */
  public int gatewayId() {
    return gatewayId;
  }

  /** Messages dropped for that gateway since the previous event. */
  public long droppedCount() {
    return droppedCount;
  }

  /** Template id of the first dropped message. */
  public int firstTemplateId() {
    return firstTemplateId;
  }

  /** The loop's event time when the event was raised, epoch nanoseconds. */
  public long timestampNanos() {
    return timestampNanos;
  }

  @Override
  public String toString() {
    return "BackpressureEvent[gateway="
        + gatewayId
        + ", dropped="
        + droppedCount
        + ", firstTemplateId="
        + firstTemplateId
        + ", at="
        + timestampNanos
        + "]";
  }
}
