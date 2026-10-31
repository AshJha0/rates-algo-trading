package io.ratesalgo.venues.md;

/** Per-source counters: updates, rejections, stale transitions, ages. */
public final class SourceMetrics {

  long updates;
  long rejects;
  long staleTransitions;
  long lastUpdateNanos;
  long maxAgeNanos;
  boolean stale;
  long lastSeq;

  /** Accepted updates. */
  public long updates() {
    return updates;
  }

  /** Rejected updates. */
  public long rejects() {
    return rejects;
  }

  /** Times the source went stale. */
  public long staleTransitions() {
    return staleTransitions;
  }

  /** Monotonic time of the last accepted update. */
  public long lastUpdateNanos() {
    return lastUpdateNanos;
  }

  /** Largest age observed at a staleness check. */
  public long maxAgeNanos() {
    return maxAgeNanos;
  }

  /** True while stale. */
  public boolean isStale() {
    return stale;
  }
}
