package io.ratesalgo.venues.feed;

import io.ratesalgo.fix.gateway.MessageSink;
import io.ratesalgo.fix.gateway.MessageSource;
import java.util.ArrayDeque;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * A feed channel held in memory with fault injection: drop, duplicate or swap the next frames.
 * Used by tests, the cookbook and the chaos suite to produce gaps, duplicates and reordering on
 * demand.
 */
public final class InProcessFeedChannel implements FeedChannel, MessageSource {

  private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
  private int dropNext;
  private int duplicateNext;
  private boolean swapNext;
  private byte[] held;
  private long published;
  private long dropped;

  /** Drops the next {@code n} frames. */
  public synchronized void dropNext(final int n) {
    dropNext += n;
  }

  /** Delivers the next {@code n} frames twice. */
  public synchronized void duplicateNext(final int n) {
    duplicateNext += n;
  }

  /** Delivers the next two frames in reverse order. */
  public synchronized void swapNext() {
    swapNext = true;
  }

  /** Frames published, including dropped ones. */
  public synchronized long published() {
    return published;
  }

  /** Frames dropped by fault injection. */
  public synchronized long dropped() {
    return dropped;
  }

  /** Frames waiting to be polled. */
  public synchronized int pending() {
    return queue.size();
  }

  @Override
  public synchronized void publish(final DirectBuffer buffer, final int offset, final int length) {
    published++;
    final byte[] frame = new byte[length];
    buffer.getBytes(offset, frame);
    if (dropNext > 0) {
      dropNext--;
      dropped++;
      return;
    }
    if (swapNext && held == null) {
      held = frame;
      return;
    }
    queue.add(frame);
    if (held != null) {
      queue.add(held);
      held = null;
      swapNext = false;
    }
    if (duplicateNext > 0) {
      duplicateNext--;
      queue.add(frame.clone());
    }
  }

  @Override
  public int poll(final MessageSink sink, final int limit) {
    int n = 0;
    while (n < limit) {
      final byte[] frame;
      synchronized (this) {
        frame = queue.poll();
      }
      if (frame == null) {
        break;
      }
      sink.offer(new UnsafeBuffer(frame), 0, frame.length);
      n++;
    }
    return n;
  }
}
