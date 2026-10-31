package io.ratesalgo.fix.gateway;

import org.agrona.DirectBuffer;

/**
 * Consumer of SBE-encoded messages (8-byte SBE header plus body), the shape carried on the
 * event-loop ring buffers. Implementations copy what they need; the buffer is reused after the
 * call returns.
 */
@FunctionalInterface
public interface MessageSink {

  /** Offers one message; returns false if it could not be accepted (back-pressure or unknown). */
  boolean offer(DirectBuffer buffer, int offset, int length);
}
