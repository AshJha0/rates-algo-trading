package io.ratesalgo.core;

import org.agrona.DirectBuffer;

/**
 * Where the loop appends a message. {@code JournalWriter::append} and {@code
 * SegmentedJournal::append} from {@code rates-journal} fit directly; replay uses a sink that
 * hashes instead of writing.
 */
@FunctionalInterface
public interface JournalSink {

  /** Discards everything; for loops that do not journal (benchmarks of the loop alone). */
  JournalSink NONE = (templateId, schemaVersion, buffer, offset, length) -> 0L;

  /**
   * Appends one message.
   *
   * @return the sequence the sink assigned, or 0 if it assigns none
   */
  long append(int templateId, int schemaVersion, DirectBuffer buffer, int offset, int length);
}
