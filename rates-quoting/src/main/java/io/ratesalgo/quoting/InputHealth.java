package io.ratesalgo.quoting;

import io.ratesalgo.messages.sbe.CancelReason;
import java.util.Arrays;

/**
 * Health of the inputs quoting depends on: heartbeat sources, venue sessions and book-feed
 * sequence numbers. The engine pulls every quote and refuses new ones while unhealthy
 * (API_SPEC.md Section 12.6). Allocation-free; up to 16 sources and 16 venues.
 */
public final class InputHealth {

  private static final int MAX = 16;

  private final long heartbeatTimeoutNanos;
  private final int[] sourceIds = new int[MAX];
  private final long[] lastBeat = new long[MAX];
  private int sourceCount;
  private final int[] venueIds = new int[MAX];
  private final boolean[] sessionUp = new boolean[MAX];
  private final long[] lastSeq = new long[MAX];
  private final boolean[] gap = new boolean[MAX];
  private int venueCount;
  private CancelReason reason = CancelReason.NULL_VAL;

  public InputHealth(final long heartbeatTimeoutNanos) {
    this.heartbeatTimeoutNanos = heartbeatTimeoutNanos;
    Arrays.fill(lastBeat, Long.MIN_VALUE);
  }

  /** Requires heartbeats from {@code sourceId}, starting the clock at {@code nowNanos}. */
  public void requireHeartbeat(final int sourceId, final long nowNanos) {
    if (sourceCount == MAX) {
      throw new IllegalStateException("more than " + MAX + " heartbeat sources");
    }
    sourceIds[sourceCount] = sourceId;
    lastBeat[sourceCount] = nowNanos;
    sourceCount++;
  }

  /** Tracks a venue's session and book-feed sequence; the session starts up. */
  public void trackVenue(final int venueId) {
    if (venueCount == MAX) {
      throw new IllegalStateException("more than " + MAX + " venues");
    }
    venueIds[venueCount] = venueId;
    sessionUp[venueCount] = true;
    venueCount++;
  }

  public void onHeartbeat(final int sourceId, final long nowNanos) {
    for (int i = 0; i < sourceCount; i++) {
      if (sourceIds[i] == sourceId) {
        lastBeat[i] = Math.max(lastBeat[i], nowNanos);
      }
    }
  }

  /** A venue session went up or down. */
  public void onSession(final int venueId, final boolean up) {
    final int v = venue(venueId);
    if (v >= 0) {
      sessionUp[v] = up;
    }
  }

  /** A book-feed message with this venue sequence number; a jump marks a gap. */
  public void onBookSequence(final int venueId, final long seq) {
    final int v = venue(venueId);
    if (v < 0) {
      return;
    }
    if (lastSeq[v] != 0 && seq != lastSeq[v] + 1) {
      gap[v] = true;
    }
    lastSeq[v] = seq;
  }

  /** The book for this venue was rebuilt from a snapshot; clears the gap. */
  public void onFeedRecovered(final int venueId) {
    final int v = venue(venueId);
    if (v >= 0) {
      gap[v] = false;
    }
  }

  /** True when every heartbeat is recent, every session up and no feed has a gap. */
  public boolean healthy(final long nowNanos) {
    for (int i = 0; i < sourceCount; i++) {
      if (nowNanos - lastBeat[i] > heartbeatTimeoutNanos) {
        reason = CancelReason.HeartbeatLoss;
        return false;
      }
    }
    for (int v = 0; v < venueCount; v++) {
      if (!sessionUp[v]) {
        reason = CancelReason.SessionLoss;
        return false;
      }
      if (gap[v]) {
        reason = CancelReason.FeedGap;
        return false;
      }
    }
    return true;
  }

  /** Why the last {@link #healthy} returned false. */
  public CancelReason reason() {
    return reason;
  }

  private int venue(final int venueId) {
    for (int v = 0; v < venueCount; v++) {
      if (venueIds[v] == venueId) {
        return v;
      }
    }
    return -1;
  }
}
