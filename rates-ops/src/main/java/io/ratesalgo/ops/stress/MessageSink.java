package io.ratesalgo.ops.stress;

import org.agrona.DirectBuffer;

/**
 * Where the stress harness sends messages. The coordinator binds the event loop's inbound ring
 * buffer here at integration (a {@code ManyToOneRingBuffer.write} wrapper); a false return is
 * back-pressure and counts as a rejection.
 */
@FunctionalInterface
public interface MessageSink {

  /** Offers one SBE message; true if accepted. */
  boolean offer(int templateId, DirectBuffer buffer, int offset, int length);
}
