package io.ratesalgo.venues.feed;

import io.ratesalgo.fix.gateway.MessageSink;

/** The recovery side of the feed (a TCP snapshot service in production). */
@FunctionalInterface
public interface SnapshotService {

  /**
   * Delivers a full snapshot to the sink: for each instrument a {@code Clear} then one {@code Add}
   * per level, every frame carrying the sequence number of the last incremental already
   * published. Returns that sequence number.
   */
  long snapshot(MessageSink sink);
}
