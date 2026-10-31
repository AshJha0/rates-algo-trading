package io.ratesalgo.venues.feed;

import org.agrona.DirectBuffer;

/** Where a publisher writes incremental frames (multicast in production; in-process or UDP here). */
@FunctionalInterface
public interface FeedChannel {

  /** Publishes one SBE frame. */
  void publish(DirectBuffer buffer, int offset, int length);
}
