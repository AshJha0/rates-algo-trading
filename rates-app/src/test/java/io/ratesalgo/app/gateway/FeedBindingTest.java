package io.ratesalgo.app.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.core.ControlMessages;
import io.ratesalgo.core.RingBufferContract;
import io.ratesalgo.fix.gateway.MessageSink;
import io.ratesalgo.messages.sbe.BookAction;
import io.ratesalgo.messages.sbe.BookUpdateDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.venues.feed.BookFeedPublisher;
import io.ratesalgo.venues.feed.InProcessFeedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.agrona.DirectBuffer;
import org.agrona.collections.Int2IntHashMap;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBuffer;
import org.junit.jupiter.api.Test;

/**
 * The book feed is a stream of deltas: the loop's book must equal the venue's after every way a
 * frame can fail to arrive (API_SPEC.md Section 18.5). Each test ends by comparing the book built
 * from what the loop received with a fresh snapshot of the venue's.
 */
class FeedBindingTest {

  private static final int VENUE = 102;
  private static final int FEED_STATUS = 3;
  private static final int A = 1008;
  private static final int B = 1009;

  private final ManualClock clock = new ManualClock(1_793_626_000_000_000_000L);
  private final InProcessFeedChannel channel = new InProcessFeedChannel();
  private final BookFeedPublisher publisher = new BookFeedPublisher(VENUE, channel, clock);

  /** What a loop does with the inbound stream: a log of it and the book it builds. */
  private static final class Loop implements MessageSink {
    final List<String> log = new ArrayList<>();
    final Map<String, Long> book = new TreeMap<>();
    private final MessageHeaderDecoder header = new MessageHeaderDecoder();
    private final BookUpdateDecoder update = new BookUpdateDecoder();
    /** Offers still to accept before refusing; negative accepts everything. */
    int acceptThenRefuse = -1;

    @Override
    public boolean offer(final DirectBuffer buffer, final int offset, final int length) {
      if (acceptThenRefuse == 0) {
        return false;
      }
      if (acceptThenRefuse > 0) {
        acceptThenRefuse--;
      }
      take(buffer, offset);
      return true;
    }

    void take(final DirectBuffer buffer, final int offset) {
      header.wrap(buffer, offset);
      if (header.templateId() == ControlMessages.GATEWAY_STATUS_TEMPLATE_ID) {
        assertEquals(FEED_STATUS, ControlMessages.gatewayStatusGateway(buffer, offset));
        log.add(ControlMessages.gatewayStatus(buffer, offset) == ControlMessages.STATUS_CONNECTED ? "UP" : "DOWN");
        return;
      }
      update.wrapAndApplyHeader(buffer, offset, header);
      final String instrument = update.instrumentId() + " ";
      if (update.action() == BookAction.Clear) {
        book.keySet().removeIf(k -> k.startsWith(instrument));
        log.add("CLEAR " + update.instrumentId() + " @" + update.venueSeqNo());
        return;
      }
      final String key = instrument + (update.side() == Side.Buy ? "B " : "S ") + update.price().mantissa();
      if (update.action() == BookAction.Delete) {
        book.remove(key);
      } else {
        book.put(key, update.quantity());
      }
      log.add(update.action() + " " + key + " @" + update.venueSeqNo());
    }
  }

  private final Loop loop = new Loop();

  /** The venue's book, as a snapshot taken now shows it. */
  private Map<String, Long> venueBook() {
    final Loop truth = new Loop();
    publisher.snapshot(truth);
    return truth.book;
  }

  private FeedBinding synced() {
    final FeedBinding binding = new FeedBinding(channel, publisher, loop, FEED_STATUS, clock);
    assertFalse(binding.synced());
    binding.doWork();
    assertTrue(binding.synced());
    return binding;
  }

  @Test
  void theFirstFramesSequenceIsNotTakenOnTrust() {
    // the binding joins a feed that is already at sequence 2: those frames never reach it
    channel.dropNext(2);
    publisher.level(A, Side.Buy, 100, 5, 1);
    publisher.level(A, Side.Sell, 101, 7, 1);
    publisher.level(A, Side.Buy, 99, 3, 1);
    final FeedBinding binding = new FeedBinding(channel, publisher, loop, FEED_STATUS, clock);

    binding.doWork();

    // without the initial snapshot the loop's book would hold only the 99 bid
    assertEquals(venueBook(), loop.book);
    assertEquals(3, loop.book.size());
    // a first snapshot with levels is announced like any other: its frames all carry sequence 3,
    // which a loop that thought the feed whole would read as a gap
    assertEquals("DOWN", loop.log.get(0));
    assertEquals("CLEAR " + A + " @3", loop.log.get(1));
    assertEquals("UP", loop.log.get(loop.log.size() - 1));
    assertEquals(1, loop.log.stream().filter("DOWN"::equals).count());
    assertEquals(1, binding.rebuilds());
    assertEquals(1, binding.duplicates(), "the live frame the snapshot already covered");
    assertEquals(0, binding.frames());
    assertEquals(0, binding.gaps());

    publisher.level(A, Side.Buy, 98, 2, 1);
    binding.doWork();
    assertEquals(1, binding.frames());
    assertEquals(venueBook(), loop.book);
  }

  @Test
  void anEmptyFeedSyncsWithoutSendingTheLoopAnything() {
    final FeedBinding binding = synced();
    assertTrue(loop.log.isEmpty());
    assertEquals(1, binding.rebuilds());
  }

  @Test
  void aRefusedLiveFrameStopsTheFeedTellsTheLoopAndRebuilds() {
    final FeedBinding binding = synced();
    publisher.level(A, Side.Buy, 100, 5, 1);
    publisher.level(A, Side.Buy, 99, 3, 1);
    binding.doWork();
    loop.log.clear();

    publisher.level(A, Side.Buy, 100, 0, 0);
    publisher.level(A, Side.Buy, 98, 4, 1);
    loop.acceptThenRefuse = 0;
    binding.doWork();

    // the Delete was refused: the sequence did not advance past it, the next frame was not
    // forwarded on top of the hole, and nothing could be told to a loop that takes nothing
    assertFalse(binding.synced());
    assertEquals(1, binding.undelivered());
    assertEquals(1, binding.skipped());
    assertEquals(2, binding.frames());
    assertTrue(loop.log.isEmpty());
    assertTrue(loop.book.containsKey(A + " B 100"), "the phantom level, for as long as the loop is deaf");

    loop.acceptThenRefuse = -1;
    binding.doWork();

    assertTrue(binding.synced());
    assertEquals("DOWN", loop.log.get(0), "the loop is told before any snapshot frame");
    assertEquals("CLEAR " + A + " @4", loop.log.get(1));
    assertEquals("UP", loop.log.get(loop.log.size() - 1));
    assertEquals(1, loop.log.stream().filter("DOWN"::equals).count());
    assertEquals(venueBook(), loop.book);
    assertFalse(loop.book.containsKey(A + " B 100"), "the dropped delete must not leave a phantom level");
    assertEquals(0, binding.gaps());

    // and the feed carries on from the snapshot's sequence
    publisher.level(A, Side.Sell, 101, 7, 1);
    binding.doWork();
    assertEquals(3, binding.frames());
    assertEquals(venueBook(), loop.book);
  }

  @Test
  void aGapOnTheWireIsAnnouncedBeforeTheSnapshotAndTheGappedFrameIsNotForwarded() {
    final FeedBinding binding = synced();
    publisher.level(A, Side.Buy, 100, 5, 1);
    binding.doWork();
    loop.log.clear();

    channel.dropNext(2);
    publisher.level(A, Side.Buy, 100, 0, 0);
    publisher.level(A, Side.Buy, 99, 3, 1);
    publisher.level(B, Side.Sell, 101, 7, 1);
    binding.doWork();

    assertEquals(1, binding.gaps());
    assertTrue(binding.synced());
    assertEquals(
        List.of("DOWN", "CLEAR " + A + " @4", "Add " + A + " B 99 @4", "CLEAR " + B + " @4", "Add " + B + " S 101 @4", "UP"),
        loop.log);
    assertEquals(venueBook(), loop.book);
    assertEquals(2, binding.rebuilds());
  }

  @Test
  void aSnapshotTheLoopOnlyHalfTookIsNotDeclaredWholeAndIsSentAgain() {
    final FeedBinding binding = synced();
    publisher.level(A, Side.Buy, 100, 5, 1);
    publisher.level(A, Side.Sell, 101, 7, 1);
    publisher.level(B, Side.Buy, 50, 9, 1);
    binding.doWork();
    loop.log.clear();

    channel.dropNext(1);
    publisher.level(A, Side.Buy, 100, 0, 0);
    publisher.level(A, Side.Buy, 99, 3, 1);
    // room for DOWN and two snapshot frames, then full
    loop.acceptThenRefuse = 3;
    binding.doWork();

    assertFalse(binding.synced());
    assertEquals(1, binding.failedRebuilds());
    assertEquals(List.of("DOWN", "CLEAR " + A + " @5", "Add " + A + " B 99 @5"), loop.log);
    assertFalse(loop.log.contains("UP"), "half a snapshot must not be announced as a whole book");

    // while the loop takes nothing the binding forwards nothing and keeps trying
    publisher.level(B, Side.Buy, 51, 1, 1);
    assertEquals(0, binding.doWork());
    assertEquals(2, binding.failedRebuilds());
    assertEquals(3, loop.log.size());
    assertEquals(1, channel.pending(), "live frames wait behind the rebuild");

    loop.acceptThenRefuse = -1;
    binding.doWork();

    assertTrue(binding.synced());
    assertEquals("UP", loop.log.get(loop.log.size() - 1));
    assertEquals(1, loop.log.stream().filter("DOWN"::equals).count(), "told once, not per attempt");
    assertEquals(1, loop.log.stream().filter("UP"::equals).count());
    assertEquals(venueBook(), loop.book);
    assertEquals(1, binding.duplicates(), "the frame that waited was inside the snapshot");
  }

  @Test
  void aRefusedStatusIsRetriedBeforeAnySnapshotFrame() {
    final FeedBinding binding = synced();
    publisher.level(A, Side.Buy, 100, 5, 1);
    binding.doWork();
    loop.log.clear();
    channel.dropNext(1);
    publisher.level(A, Side.Buy, 99, 3, 1);
    publisher.level(A, Side.Buy, 98, 3, 1);
    loop.acceptThenRefuse = 0;
    binding.doWork();
    binding.doWork();
    assertTrue(loop.log.isEmpty());
    assertEquals(2, binding.failedRebuilds());
    loop.acceptThenRefuse = -1;
    binding.doWork();
    assertEquals("DOWN", loop.log.get(0));
    assertEquals(venueBook(), loop.book);
  }

  @Test
  void throughTheInboundRouterAFullRingIsALossButAnUnownedInstrumentIsNot() {
    final ManyToOneRingBuffer ring = RingBufferContract.newInbound(RingBufferContract.MIN_CAPACITY);
    final Int2IntHashMap groupOf = new Int2IntHashMap(-1);
    groupOf.put(A, 0);
    final InboundRouter router = new InboundRouter(new RingBuffer[] {ring}, groupOf, 8, () -> {});
    final FeedBinding binding = new FeedBinding(channel, publisher, router, FEED_STATUS, clock);
    binding.doWork();

    // B belongs to no group: its frames are passed over, in sequence, without a rebuild
    publisher.level(B, Side.Buy, 50, 9, 1);
    publisher.level(A, Side.Buy, 100, 5, 1);
    binding.doWork();
    assertTrue(binding.synced());
    assertEquals(1, binding.rebuilds());
    assertEquals(1, binding.frames());
    assertEquals(1, router.unrouted());

    // the loop stalls: the ring fills with deltas until one is refused
    long price = 100;
    while (binding.synced()) {
      publisher.level(A, Side.Buy, price, 0, 0);
      publisher.level(A, Side.Buy, ++price, 5, 1);
      binding.doWork();
    }
    assertEquals(1, binding.undelivered());
    assertTrue(router.dropped() >= 1);
    assertTrue(router.undelivered() >= 1, "DISCONNECTED could not be delivered either, and was bounded");
    publisher.level(A, Side.Buy, price, 0, 0);
    publisher.level(A, Side.Buy, 7, 5, 1);
    assertEquals(0, binding.doWork(), "still deaf: nothing forwarded on top of the hole");

    // the loop reads again
    ring.read((t, buf, i, l) -> loop.take(buf, i), Integer.MAX_VALUE);
    binding.doWork();
    ring.read((t, buf, i, l) -> loop.take(buf, i), Integer.MAX_VALUE);

    assertTrue(binding.synced());
    final Map<String, Long> truth = venueBook();
    truth.keySet().removeIf(k -> k.startsWith(B + " "));
    assertEquals(truth, loop.book, "one bid at 7: every deleted level is gone");
    assertEquals(1, loop.book.size());
    assertEquals("UP", loop.log.get(loop.log.size() - 1));
    assertTrue(loop.log.contains("DOWN"));
  }
}
