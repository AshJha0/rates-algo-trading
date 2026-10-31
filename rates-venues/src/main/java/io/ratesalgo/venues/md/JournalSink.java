package io.ratesalgo.venues.md;

import io.ratesalgo.fix.gateway.MessageSink;
import io.ratesalgo.journal.JournalWriter;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import org.agrona.DirectBuffer;

/** A {@link MessageSink} that appends each SBE message to a journal with its template id. */
public final class JournalSink implements MessageSink {

  private final JournalWriter writer;
  private final MessageHeaderDecoder header = new MessageHeaderDecoder();

  /** Creates a sink writing to the journal. */
  public JournalSink(final JournalWriter writer) {
    this.writer = writer;
  }

  @Override
  public boolean offer(final DirectBuffer buffer, final int offset, final int length) {
    header.wrap(buffer, offset);
    writer.append(header.templateId(), header.version(), buffer, offset, length);
    return true;
  }
}
