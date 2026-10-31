package io.ratesalgo.app.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.core.ControlMessages;
import io.ratesalgo.core.RingBufferContract;
import io.ratesalgo.messages.sbe.BookUpdateEncoder;
import io.ratesalgo.messages.sbe.HeartbeatEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.QuoteRequestEncoder;
import io.ratesalgo.messages.sbe.QuoteResponseEncoder;
import io.ratesalgo.messages.sbe.QuoteStatus;
import java.util.ArrayList;
import java.util.List;
import org.agrona.collections.Int2IntHashMap;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBuffer;
import org.junit.jupiter.api.Test;

/** Instrument-group partitioning and the full-ring policy (API_SPEC.md Sections 9.3 and 18.5). */
class InboundRouterTest {

  private final UnsafeBuffer b = new UnsafeBuffer(new byte[256]);
  private final MessageHeaderEncoder h = new MessageHeaderEncoder();

  private static List<Integer> drain(final RingBuffer ring) {
    final List<Integer> templates = new ArrayList<>();
    ring.read((t, buf, i, l) -> templates.add(t), 1000);
    return templates;
  }

  private int quoteRequest(final int instrumentId, final long rfqId) {
    final QuoteRequestEncoder e = new QuoteRequestEncoder();
    e.wrapAndApplyHeader(b, 0, h).venueId(101).rfqId(rfqId).instrumentId(instrumentId);
    return MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength();
  }

  private int quoteResponse(final long rfqId, final QuoteStatus status) {
    final QuoteResponseEncoder e = new QuoteResponseEncoder();
    e.wrapAndApplyHeader(b, 0, h).venueId(101).rfqId(rfqId).quoteId(1).status(status);
    return MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength();
  }

  @Test
  void messagesGoToTheirInstrumentsGroupAndResponsesFollowTheirRequest() {
    final ManyToOneRingBuffer swaps = RingBufferContract.newInbound(RingBufferContract.MIN_CAPACITY);
    final ManyToOneRingBuffer bonds = RingBufferContract.newInbound(RingBufferContract.MIN_CAPACITY);
    final Int2IntHashMap groupOf = new Int2IntHashMap(-1);
    groupOf.put(2003, 0);
    groupOf.put(1008, 1);
    final InboundRouter router = new InboundRouter(new RingBuffer[] {swaps, bonds}, groupOf);

    assertTrue(router.offer(b, 0, quoteRequest(1008, 7)));
    assertTrue(router.offer(b, 0, quoteRequest(2003, 8)));
    assertTrue(router.offer(b, 0, quoteResponse(7, QuoteStatus.Rejected)));
    assertTrue(router.offer(b, 0, quoteResponse(7, QuoteStatus.Done)));
    assertFalse(router.offer(b, 0, quoteResponse(7, QuoteStatus.Expired)), "the final response forgot the RFQ");
    assertTrue(router.offer(b, 0, quoteResponse(8, QuoteStatus.TradedAway)));
    assertFalse(router.offer(b, 0, quoteRequest(55, 9)), "an instrument no group owns");
    final HeartbeatEncoder hb = new HeartbeatEncoder();
    hb.wrapAndApplyHeader(b, 0, h).sourceId(900);
    assertTrue(router.offer(b, 0, MessageHeaderEncoder.ENCODED_LENGTH + hb.encodedLength()));
    final int status = ControlMessages.encodeGatewayStatus(b, 0, 1L, 0, ControlMessages.STATUS_CONNECTED);
    assertTrue(router.offer(b, 0, status));

    assertEquals(List.of(10, 12, 1, 65003), drain(swaps));
    assertEquals(List.of(10, 12, 12, 1, 65003), drain(bonds));
    assertEquals(2, router.unrouted());
    assertEquals(9, router.published());
  }

  @Test
  void aFullRingDropsMarketDataAndRetriesSessionMessages() throws Exception {
    final ManyToOneRingBuffer ring = RingBufferContract.newInbound(RingBufferContract.MIN_CAPACITY);
    final Int2IntHashMap groupOf = new Int2IntHashMap(-1);
    groupOf.put(1008, 0);
    final InboundRouter router = new InboundRouter(new RingBuffer[] {ring}, groupOf);
    final BookUpdateEncoder e = new BookUpdateEncoder();
    e.wrapAndApplyHeader(b, 0, h).venueId(102).instrumentId(1008);
    final int length = MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength();
    int accepted = 0;
    while (router.offer(b, 0, length)) {
      accepted++;
    }
    assertTrue(accepted > 0);
    assertEquals(1, router.dropped());
    final Thread reader = new Thread(() -> {
      try {
        Thread.sleep(50);
      } catch (final InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
      ring.read((t, buf, i, l) -> {}, 10);
    });
    reader.start();
    assertTrue(router.offer(b, 0, quoteRequest(1008, 1)), "a session message waits for room");
    reader.join();
    assertTrue(router.retries() > 0);
  }
}
