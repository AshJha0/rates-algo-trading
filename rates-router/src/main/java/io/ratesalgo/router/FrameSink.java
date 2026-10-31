package io.ratesalgo.router;

import org.agrona.DirectBuffer;

/**
 * Where the router writes its SBE records (RouteDecision, RouteDecisionVenue,
 * ChildOrderTransition). Bind to {@code JournalWriter::append}; the payload includes the SBE
 * message header. Must not allocate.
 */
@FunctionalInterface
public interface FrameSink {

  /** Appends one encoded message. */
  void append(int templateId, int schemaVersion, DirectBuffer buffer, int offset, int length);

  /** A sink that drops everything. */
  FrameSink NONE = (templateId, schemaVersion, buffer, offset, length) -> {};
}
