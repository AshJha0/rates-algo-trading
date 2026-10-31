package io.ratesalgo.api;

import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import org.agrona.DirectBuffer;

/** Allocation-free stub sink that counts messages by template id (ids below 256). */
public final class CountingOutboundSink implements OutboundSink {

  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final long[] counts = new long[256];
  private long total;

  @Override
  public void send(final DirectBuffer buffer, final int offset, final int length) {
    header.wrap(buffer, offset);
    counts[header.templateId() & 0xFF]++;
    total++;
  }

  /** Messages seen with this template id. */
  public long count(final int templateId) {
    return counts[templateId & 0xFF];
  }

  /** All messages seen. */
  public long total() {
    return total;
  }
}
