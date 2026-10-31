package io.ratesalgo.fix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.fix.codec.FixTags;
import io.ratesalgo.fix.codec.FixWriter;
import io.ratesalgo.fix.codec.MsgType;
import io.ratesalgo.fix.gateway.InstrumentMap;
import io.ratesalgo.fix.gateway.VenueGateway;
import io.ratesalgo.fix.order.OrderStateTracker;
import io.ratesalgo.fix.session.FixSession;
import io.ratesalgo.fix.session.FixSessionConfig;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.QuoteEncoder;
import java.lang.management.ManagementFactory;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

/**
 * The engine-side FIX path allocates nothing per message after warm-up (P2 gate). One million
 * messages cycle through four flows: an inbound QuoteRequest parsed, sequence-checked and
 * translated to SBE; an outbound SBE Quote translated to FIX and written; an inbound fill through
 * the order tracker; an inbound two-entry incremental refresh translated to BookUpdates. The
 * allocated-bytes counter of this thread must not move.
 */
class AllocationTest {

  private static final byte[] ISIN = "US91282CZH77".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  private long sbeMessages;
  private int lastTemplate;

  @Test
  void millionMessagesAllocateNothing() {
    final ManualClock clock = new ManualClock(1_793_629_800_000_000_000L);
    final ScriptedVenue venue = new ScriptedVenue(clock);
    final MessageHeaderDecoder header = new MessageHeaderDecoder();
    final OrderStateTracker tracker = new OrderStateTracker(4096, 300_000);
    final VenueGateway gateway =
        new VenueGateway(
            101,
            new InstrumentMap().register(1008, "US91282CZH77"),
            (b, o, l) -> {
              header.wrap(b, o);
              lastTemplate = header.templateId();
              sbeMessages++;
              return true;
            },
            clock,
            tracker);
    final FixSession session =
        new FixSession(new FixSessionConfig("ENGINE", "VENUE", 30, true), clock, venue, gateway);
    gateway.bind(session);
    session.onConnected();
    venue.begin(MsgType.LOGON).put(98, 0).put(108, 30).putBool(141, true);
    venue.deliver(session);
    venue.quiet();

    final UnsafeBuffer quote = new UnsafeBuffer(new byte[128]);
    final QuoteEncoder q = new QuoteEncoder();
    q.wrapAndApplyHeader(quote, 0, new MessageHeaderEncoder())
        .venueId(101)
        .instrumentId(1008)
        .bidQuantity(1_000_000_000L)
        .askQuantity(1_000_000_000L)
        .validUntilNanos(clock.epochNanos())
        .sentNanos(clock.epochNanos());
    q.bidPrice().mantissa(99_500_000_000L);
    q.askPrice().mantissa(99_515_625_000L);
    final int quoteLength = MessageHeaderEncoder.ENCODED_LENGTH + q.encodedLength();

    final com.sun.management.ThreadMXBean mx =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    final long thread = Thread.currentThread().threadId();
    mx.getThreadAllocatedBytes(thread);

    run(200_000, venue, session, gateway, q, quote, quoteLength, tracker);
    final long before = mx.getThreadAllocatedBytes(thread);
    final long sentBefore = venue.sentCount();
    final long sbeBefore = sbeMessages;
    run(1_000_000, venue, session, gateway, q, quote, quoteLength, tracker);
    final long allocated = mx.getThreadAllocatedBytes(thread) - before;

    assertEquals(0, allocated, "bytes allocated over one million messages");
    assertEquals(250_000, venue.sentCount() - sentBefore);
    assertEquals(1_000_000, sbeMessages - sbeBefore); // 250k QuoteRequest, 250k ER, 500k BookUpdate
    assertEquals(0, session.stats().sequenceErrors());
    assertTrue(lastTemplate > 0);
  }

  private static void run(
      final int messages,
      final ScriptedVenue venue,
      final FixSession session,
      final VenueGateway gateway,
      final QuoteEncoder q,
      final UnsafeBuffer quote,
      final int quoteLength,
      final OrderStateTracker tracker) {
    for (int i = 0; i < messages; i++) {
      switch (i & 3) {
        case 0 -> {
          final FixWriter w = venue.begin(MsgType.QUOTE_REQUEST);
          w.put(FixTags.QUOTE_REQ_ID, i)
              .put(FixTags.NO_RELATED_SYM, 1)
              .putAscii(FixTags.SYMBOL, ISIN)
              .putAscii(FixTags.SECURITY_ID, ISIN)
              .putChar(FixTags.SECURITY_ID_SOURCE, (byte) '4')
              .putChar(FixTags.SIDE, (byte) '1')
              .put(FixTags.ORDER_QTY, 10_000_000)
              .putChars(FixTags.CURRENCY, (byte) 'U', (byte) 'S')
              .put(FixTags.DEALER_COUNT, 4)
              .putTimestamp(FixTags.EXPIRE_TIME, venue.clock.epochNanos());
          venue.deliver(session);
        }
        case 1 -> {
          q.rfqId(i).quoteId(i);
          gateway.offer(quote, 0, quoteLength);
        }
        case 2 -> {
          final FixWriter w = venue.begin(MsgType.EXECUTION_REPORT);
          w.put(FixTags.ORDER_ID, 1)
              .put(FixTags.CL_ORD_ID, i & 1023)
              .put(FixTags.EXEC_ID, i)
              .putChar(FixTags.EXEC_TYPE, (byte) 'F')
              .putChar(FixTags.ORD_STATUS, (byte) '1')
              .putAscii(FixTags.SECURITY_ID, ISIN)
              .putChar(FixTags.SIDE, (byte) '2')
              .put(FixTags.ORDER_QTY, 1_000_000_000)
              .put(FixTags.LAST_QTY, 1_000_000)
              .putDecimal(FixTags.LAST_PX, 99_500_000_000L, 9)
              .putTimestamp(FixTags.TRANSACT_TIME, venue.clock.epochNanos());
          venue.deliver(session);
        }
        default -> {
          final FixWriter w = venue.begin(MsgType.MARKET_DATA_INCREMENTAL);
          w.put(FixTags.MD_REQ_ID, 1)
              .put(FixTags.NO_MD_ENTRIES, 2)
              .putChar(FixTags.MD_UPDATE_ACTION, (byte) '1')
              .putChar(FixTags.MD_ENTRY_TYPE, (byte) '0')
              .putAscii(FixTags.SECURITY_ID, ISIN)
              .put(FixTags.RPT_SEQ, i)
              .putDecimal(FixTags.MD_ENTRY_PX, 99_500_000_000L, 9)
              .put(FixTags.MD_ENTRY_SIZE, 5_000_000)
              .putChar(FixTags.MD_UPDATE_ACTION, (byte) '1')
              .putChar(FixTags.MD_ENTRY_TYPE, (byte) '1')
              .putAscii(FixTags.SECURITY_ID, ISIN)
              .putDecimal(FixTags.MD_ENTRY_PX, 99_515_625_000L, 9)
              .put(FixTags.MD_ENTRY_SIZE, 5_000_000);
          venue.deliver(session);
        }
      }
      if (i % 100_000 == 99_999) {
        tracker.clear();
      }
    }
  }
}
