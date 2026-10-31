package io.ratesalgo.quoting;

import io.ratesalgo.messages.sbe.BookUpdateDecoder;
import io.ratesalgo.messages.sbe.ExecutionReportDecoder;
import io.ratesalgo.messages.sbe.HeartbeatDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteResponseDecoder;
import org.agrona.DirectBuffer;

/**
 * The P3 handler P2's event loop calls with each inbound SBE message (by template id) and its
 * journal sequence number. QuoteRequest and QuoteResponse go to the quoting engine; Heartbeat and
 * BookUpdate feed input health and the CLOB quoter; ExecutionReport for CLOB orders goes to the
 * CLOB quoter. Hedge fills arrive from the router (P4) as parent fills and are passed to {@code
 * Hedger.onHedgeFill} by the coordinator's binding. A header with the wrong schema id or version
 * throws (API_SPEC.md Section 4). Allocation-free.
 */
public final class QuotingEventHandler {

  private final QuotingEngine engine;
  private final ClobQuoter clob;
  private final InputHealth health;
  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final QuoteRequestDecoder request = new QuoteRequestDecoder();
  private final QuoteResponseDecoder response = new QuoteResponseDecoder();
  private final HeartbeatDecoder heartbeat = new HeartbeatDecoder();
  private final BookUpdateDecoder bookUpdate = new BookUpdateDecoder();
  private final ExecutionReportDecoder execution = new ExecutionReportDecoder();

  public QuotingEventHandler(final QuotingEngine engine, final ClobQuoter clob, final InputHealth health) {
    this.engine = engine;
    this.clob = clob;
    this.health = health;
  }

  /** Handles one message; returns false for template ids P3 does not consume. */
  public boolean onMessage(final DirectBuffer buffer, final int offset, final long journalSeqNo) {
    header.wrap(buffer, offset);
    if (header.schemaId() != MessageHeaderDecoder.SCHEMA_ID || header.version() > MessageHeaderDecoder.SCHEMA_VERSION) {
      throw new IllegalStateException(
          "schema " + header.schemaId() + " version " + header.version() + ", expected "
              + MessageHeaderDecoder.SCHEMA_ID + " version " + MessageHeaderDecoder.SCHEMA_VERSION);
    }
    final int body = offset + MessageHeaderDecoder.ENCODED_LENGTH;
    final int blockLength = header.blockLength();
    final int version = header.version();
    switch (header.templateId()) {
      case QuoteRequestDecoder.TEMPLATE_ID -> {
        request.wrap(buffer, body, blockLength, version);
        engine.onQuoteRequest(request, journalSeqNo);
      }
      case QuoteResponseDecoder.TEMPLATE_ID -> {
        response.wrap(buffer, body, blockLength, version);
        engine.onQuoteResponse(response);
      }
      case HeartbeatDecoder.TEMPLATE_ID -> {
        heartbeat.wrap(buffer, body, blockLength, version);
        health.onHeartbeat(heartbeat.sourceId(), heartbeat.timestampNanos());
        engine.poll(engine.clock().epochNanos());
      }
      case BookUpdateDecoder.TEMPLATE_ID -> {
        bookUpdate.wrap(buffer, body, blockLength, version);
        health.onBookSequence(bookUpdate.venueId(), bookUpdate.venueSeqNo());
        if (clob != null) {
          clob.onBook(bookUpdate.instrumentId());
          clob.update(bookUpdate.instrumentId(), engine.clock().epochNanos());
        }
      }
      case ExecutionReportDecoder.TEMPLATE_ID -> {
        execution.wrap(buffer, body, blockLength, version);
        if (clob != null) {
          clob.onExecution(
              execution.clOrdId(), execution.lastQuantity(), execution.lastPrice().mantissa(), execution.ordStatus());
        }
      }
      default -> {
        return false;
      }
    }
    return true;
  }
}
