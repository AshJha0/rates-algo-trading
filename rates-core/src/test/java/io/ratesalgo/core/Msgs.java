package io.ratesalgo.core;

import io.ratesalgo.messages.sbe.HeartbeatEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.QuoteRequestEncoder;
import io.ratesalgo.messages.sbe.Side;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/** Encoders for test messages. */
final class Msgs {

  static final int QUOTE_REQUEST_LENGTH =
      MessageHeaderEncoder.ENCODED_LENGTH + QuoteRequestEncoder.BLOCK_LENGTH;

  private static final MessageHeaderEncoder HEADER = new MessageHeaderEncoder();
  private static final QuoteRequestEncoder REQUEST = new QuoteRequestEncoder();
  private static final HeartbeatEncoder HEARTBEAT = new HeartbeatEncoder();

  private Msgs() {}

  static int quoteRequest(final MutableDirectBuffer b, final int offset, final long rfqId) {
    REQUEST
        .wrapAndApplyHeader(b, offset, HEADER)
        .venueId(7)
        .rfqId(rfqId)
        .clientId(3)
        .instrumentId(1000 + (int) (rfqId % 5))
        .side(rfqId % 2 == 0 ? Side.Buy : Side.Sell)
        .quantity(10_000_000_000L)
        .currency("USD")
        .dealerCount((short) 4)
        .receivedNanos(1_700_000_000_000_000_000L + rfqId)
        .expiryNanos(1_700_000_030_000_000_000L + rfqId);
    return REQUEST.limit() - offset;
  }

  static int heartbeat(final MutableDirectBuffer b, final int offset, final int sourceId) {
    HEARTBEAT.wrapAndApplyHeader(b, offset, HEADER).sourceId(sourceId).timestampNanos(1L);
    return HEARTBEAT.limit() - offset;
  }

  /** rfqId of a QuoteRequest at {@code offset}. */
  static long rfqId(final DirectBuffer b, final int offset) {
    return b.getLong(offset + MessageHeaderEncoder.ENCODED_LENGTH + 4, java.nio.ByteOrder.LITTLE_ENDIAN);
  }
}
