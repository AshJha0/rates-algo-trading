package io.ratesalgo.fix.net;

import org.agrona.DirectBuffer;

/** Test and chaos hook: decides whether a framed inbound message reaches the session. */
@FunctionalInterface
public interface InboundFilter {

  /** Passes every message. */
  InboundFilter PASS = (buffer, offset, length) -> true;

  /** True to deliver the message, false to drop it as if lost on the wire. */
  boolean deliver(DirectBuffer buffer, int offset, int length);
}
