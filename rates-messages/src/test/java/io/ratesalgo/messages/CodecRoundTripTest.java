package io.ratesalgo.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.KillSwitchDecoder;
import io.ratesalgo.messages.sbe.KillSwitchEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.QuoteDecoder;
import io.ratesalgo.messages.sbe.QuoteEncoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteRequestEncoder;
import io.ratesalgo.messages.sbe.Side;
import java.nio.ByteBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

/** The generated codecs encode and decode every field we rely on, with the pinned ids. */
class CodecRoundTripTest {

  private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
  private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
  private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();

  @Test
  void schemaIdentityIsPinned() {
    assertEquals(1, QuoteRequestEncoder.SCHEMA_ID);
    assertEquals(0, QuoteRequestEncoder.SCHEMA_VERSION);
    assertEquals(10, QuoteRequestEncoder.TEMPLATE_ID);
    assertEquals(11, QuoteEncoder.TEMPLATE_ID);
    assertEquals(31, KillSwitchEncoder.TEMPLATE_ID);
    assertEquals(8, MessageHeaderEncoder.ENCODED_LENGTH);
  }

  @Test
  void quoteRequestRoundTrip() {
    final QuoteRequestEncoder encoder = new QuoteRequestEncoder();
    encoder
        .wrapAndApplyHeader(buffer, 0, headerEncoder)
        .venueId(7)
        .rfqId(123_456_789L)
        .clientId(42)
        .instrumentId(1001)
        .side(Side.Buy)
        .quantity(100_000_000_00L)
        .currency("USD")
        .dealerCount((short) 4)
        .receivedNanos(1_700_000_000_000_000_000L)
        .expiryNanos(1_700_000_000_000_000_000L + 30_000_000_000L);

    headerDecoder.wrap(buffer, 0);
    assertEquals(QuoteRequestDecoder.TEMPLATE_ID, headerDecoder.templateId());
    assertEquals(QuoteRequestDecoder.SCHEMA_ID, headerDecoder.schemaId());

    final QuoteRequestDecoder decoder = new QuoteRequestDecoder();
    decoder.wrapAndApplyHeader(buffer, 0, headerDecoder);
    assertEquals(7, decoder.venueId());
    assertEquals(123_456_789L, decoder.rfqId());
    assertEquals(42, decoder.clientId());
    assertEquals(1001, decoder.instrumentId());
    assertEquals(Side.Buy, decoder.side());
    assertEquals(100_000_000_00L, decoder.quantity());
    assertEquals("USD", decoder.currency());
    assertEquals(4, decoder.dealerCount());
    assertEquals(1_700_000_000_000_000_000L, decoder.receivedNanos());
    assertEquals(1_700_000_000_000_000_000L + 30_000_000_000L, decoder.expiryNanos());
    assertEquals(
        MessageHeaderEncoder.ENCODED_LENGTH + QuoteRequestEncoder.BLOCK_LENGTH,
        encoder.limit());
  }

  @Test
  void decimal9CarriesTheConstantExponent() {
    final QuoteEncoder encoder = new QuoteEncoder();
    encoder
        .wrapAndApplyHeader(buffer, 0, headerEncoder)
        .venueId(1)
        .rfqId(2)
        .quoteId(3)
        .instrumentId(4);
    encoder.bidPrice().mantissa(4_123_500_000L); // 4.1235 percent as a rate
    encoder.askPrice().mantissa(4_128_500_000L);
    encoder.bidQuantity(50_000_000_00L).askQuantity(50_000_000_00L);
    encoder.referenceNanos(10L).validUntilNanos(20L).sentNanos(11L);

    final QuoteDecoder decoder = new QuoteDecoder();
    decoder.wrapAndApplyHeader(buffer, 0, headerDecoder.wrap(buffer, 0));
    assertEquals(4_123_500_000L, decoder.bidPrice().mantissa());
    assertEquals(-9, decoder.bidPrice().exponent());
    assertEquals(4_128_500_000L, decoder.askPrice().mantissa());
    assertEquals(20L, decoder.validUntilNanos());
  }

  @Test
  void killSwitchRoundTrip() {
    new KillSwitchEncoder()
        .wrapAndApplyHeader(buffer, 0, headerEncoder)
        .reason(KillReason.FeedGap)
        .scopeId(0)
        .requestedBy(99)
        .timestampNanos(5L);
    final KillSwitchDecoder decoder = new KillSwitchDecoder();
    decoder.wrapAndApplyHeader(buffer, 0, headerDecoder.wrap(buffer, 0));
    assertEquals(KillReason.FeedGap, decoder.reason());
    assertEquals(0, decoder.scopeId());
    assertEquals(99, decoder.requestedBy());
    assertEquals(5L, decoder.timestampNanos());
  }
}
