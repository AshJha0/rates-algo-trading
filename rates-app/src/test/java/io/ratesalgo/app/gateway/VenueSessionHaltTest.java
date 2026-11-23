package io.ratesalgo.app.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.core.ControlMessages;
import io.ratesalgo.core.RingBufferContract;
import io.ratesalgo.fix.codec.FixFramer;
import io.ratesalgo.fix.codec.FixParser;
import io.ratesalgo.fix.codec.FixTags;
import io.ratesalgo.fix.codec.FixWriter;
import io.ratesalgo.fix.codec.MsgType;
import io.ratesalgo.fix.gateway.InstrumentMap;
import io.ratesalgo.fix.session.SessionState;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.NewOrderEncoder;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.QuoteEncoder;
import io.ratesalgo.messages.sbe.QuoteWithdrawDecoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A venue session withdrawing by itself (review findings L1 and L2): the session, not the loop,
 * cancels what is working at the venue, lets nothing new out, repeats cancels the venue does not
 * answer and then logs out. The venue here is a socket the test scripts, so every step is driven by
 * hand and the session's clock is a {@link ManualClock}: nothing waits on real time.
 */
class VenueSessionHaltTest {

  private static final int INSTRUMENT = 1001;
  private static final long ORDER = (1L << 52) + 5;
  private static final long SECOND = 1_000_000_000L;

  /** The venue's side of the session, polled by the test thread. */
  private static final class Venue implements AutoCloseable {
    private final ServerSocketChannel server;
    private final ByteBuffer inBytes = ByteBuffer.allocateDirect(1 << 16);
    private final UnsafeBuffer in = new UnsafeBuffer(inBytes);
    private final FixParser parser = new FixParser();
    private final FixWriter writer = new FixWriter(4096);
    private final UnsafeBuffer out = new UnsafeBuffer(new byte[4200]);
    private final ManualClock clock;
    private SocketChannel channel;
    private long seq = 1;
    final List<String> received = new ArrayList<>();
    boolean answerCancels = true;
    boolean answerLogout = true;
    boolean closedByEngine;

    Venue(final ManualClock clock) throws IOException {
      this.clock = clock;
      server = ServerSocketChannel.open();
      server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1);
      server.configureBlocking(false);
    }

    int port() throws IOException {
      return ((InetSocketAddress) server.getLocalAddress()).getPort();
    }

    private FixWriter begin(final int msgType) {
      return writer
          .begin(msgType)
          .putText(FixTags.SENDER_COMP_ID, "VENUE")
          .putText(FixTags.TARGET_COMP_ID, "RALGO")
          .put(FixTags.MSG_SEQ_NUM, seq++)
          .putTimestamp(FixTags.SENDING_TIME, clock.epochNanos());
    }

    private void send() throws IOException {
      final int length = writer.finish(out, 0);
      final ByteBuffer b = ByteBuffer.wrap(out.byteArray(), 0, length);
      while (b.hasRemaining()) {
        channel.write(b);
      }
    }

    /** RFQ outcome "expired" naming the RFQ only, as the simulator sends to a dealer it passed over. */
    void sendExpired(final long rfqId) throws IOException {
      begin(MsgType.QUOTE_RESPONSE).put(FixTags.QUOTE_REQ_ID, rfqId).put(FixTags.QUOTE_RESP_TYPE, 3);
      send();
    }

    void sendFilled(final long clOrdId) throws IOException {
      begin(MsgType.EXECUTION_REPORT).put(FixTags.CL_ORD_ID, clOrdId).putText(FixTags.ORD_STATUS, "2");
      send();
    }

    void sendCancelReject(final long cancelId, final long clOrdId) throws IOException {
      begin(MsgType.ORDER_CANCEL_REJECT).put(FixTags.CL_ORD_ID, cancelId).put(FixTags.ORIG_CL_ORD_ID, clOrdId)
          .put(102, 1);
      send();
    }

    void poll() throws IOException {
      if (channel == null) {
        channel = server.accept();
        if (channel == null) {
          return;
        }
        channel.configureBlocking(false);
      }
      if (!channel.isOpen()) {
        return;
      }
      final int n;
      try {
        n = channel.read(inBytes);
      } catch (final IOException reset) {
        closedByEngine = true;
        channel.close();
        return;
      }
      if (n < 0) {
        closedByEngine = true;
        channel.close();
        return;
      }
      int start = 0;
      final int limit = inBytes.position();
      while (start < limit) {
        final int length = FixFramer.frame(in, start, limit - start);
        if (length <= 0) {
          break;
        }
        assertEquals(FixParser.OK, parser.wrap(in, start, length));
        onMessage();
        start += length;
        if (!channel.isOpen()) {
          return;
        }
      }
      in.putBytes(0, in, start, limit - start);
      inBytes.position(limit - start);
    }

    private void onMessage() throws IOException {
      switch (parser.msgType()) {
        case MsgType.LOGON -> {
          received.add("A");
          begin(MsgType.LOGON).put(98, 0).put(108, 30).putBool(141, true);
          send();
        }
        case MsgType.QUOTE -> received.add("S:" + parser.getLong(FixTags.QUOTE_ID));
        case MsgType.NEW_ORDER_SINGLE -> received.add("D:" + parser.getLong(FixTags.CL_ORD_ID));
        case MsgType.QUOTE_CANCEL -> {
          final long quoteId = parser.getLong(FixTags.QUOTE_ID);
          received.add("Z:" + quoteId);
          if (answerCancels) {
            begin(MsgType.QUOTE_STATUS_REPORT).put(FixTags.QUOTE_ID, quoteId).put(FixTags.QUOTE_STATUS, 6);
            send();
          }
        }
        case MsgType.ORDER_CANCEL_REQUEST -> {
          final long orig = parser.getLong(FixTags.ORIG_CL_ORD_ID);
          final long id = parser.getLong(FixTags.CL_ORD_ID);
          received.add("F:" + orig + ":" + id);
          if (answerCancels) {
            begin(MsgType.EXECUTION_REPORT).put(FixTags.CL_ORD_ID, id).put(FixTags.ORIG_CL_ORD_ID, orig)
                .putText(FixTags.ORD_STATUS, "4");
            send();
          }
        }
        case MsgType.LOGOUT -> {
          received.add("5");
          if (answerLogout) {
            begin(MsgType.LOGOUT);
            send();
            channel.close();
          }
        }
        default -> received.add("?" + parser.msgType());
      }
    }

    long count(final String prefix) {
      return received.stream().filter(m -> m.startsWith(prefix)).count();
    }

    @Override
    public void close() throws IOException {
      if (channel != null) {
        channel.close();
      }
      server.close();
    }
  }

  private final ManualClock clock = new ManualClock(1_793_629_800_000_000_000L);
  private final OneToOneRingBuffer ring = RingBufferContract.newOutbound(RingBufferContract.DEFAULT_OUTBOUND_CAPACITY);
  private final UnsafeBuffer scratch = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final List<Integer> statuses = new ArrayList<>();
  private Venue venue;
  private VenueSession session;

  @BeforeEach
  void logOn() throws Exception {
    venue = new Venue(clock);
    session =
        new VenueSession(
            101,
            0,
            new String[] {"RALGO", "VENUE"},
            "127.0.0.1",
            venue.port(),
            new InstrumentMap().register(INSTRUMENT, "US91282CKQ33"),
            (b, o, l) -> {
              if (RingBufferContract.templateId(b, o) == ControlMessages.GATEWAY_STATUS_TEMPLATE_ID) {
                statuses.add((int) ControlMessages.gatewayStatus(b, o));
              }
              return true;
            },
            clock,
            new RingBuffer[] {ring},
            ReconnectPolicy.NONE,
            new VenueSession.WithdrawPolicy(250_000_000L, 2, SECOND));
    session.connect();
    until(session::isActive, "logon");
  }

  @AfterEach
  void close() throws Exception {
    session.close();
    venue.close();
  }

  /** Duty cycles on both sides until {@code done}; the two ends talk over a real loopback socket. */
  private void until(final BooleanSupplier done, final String what) throws IOException {
    final long deadline = System.nanoTime() + 30 * SECOND;
    while (!done.getAsBoolean()) {
      session.doWork();
      venue.poll();
      assertTrue(System.nanoTime() - deadline < 0, "timed out waiting for " + what + "; venue saw " + venue.received);
    }
  }

  /** A fixed number of duty cycles, for "nothing more happens" checks. */
  private void settle() throws IOException {
    final long[] rounds = {0};
    final long seen = venue.received.size();
    until(() -> ++rounds[0] > 2_000, "settling");
    assertTrue(venue.received.size() >= seen);
  }

  private void quote(final long rfqId, final long quoteId) {
    final QuoteEncoder q = new QuoteEncoder();
    q.wrapAndApplyHeader(scratch, 0, header).venueId(101).rfqId(rfqId).quoteId(quoteId).instrumentId(INSTRUMENT)
        .bidQuantity(1_000_000_00L).askQuantity(1_000_000_00L).referenceNanos(clock.epochNanos())
        .validUntilNanos(clock.epochNanos() + 5 * SECOND).sentNanos(clock.epochNanos());
    q.bidPrice().mantissa(99_500_000_000L);
    q.askPrice().mantissa(99_600_000_000L);
    assertTrue(ring.write(QuoteEncoder.TEMPLATE_ID, scratch, 0, MessageHeaderEncoder.ENCODED_LENGTH + q.encodedLength()));
  }

  private void order(final long clOrdId) {
    final NewOrderEncoder o = new NewOrderEncoder();
    o.wrapAndApplyHeader(scratch, 0, header).venueId(101).clOrdId(clOrdId).parentId(0).instrumentId(INSTRUMENT)
        .side(Side.Sell).ordType(OrdType.Limit).timeInForce(TimeInForce.Day).quantity(5_000_000_00L).expireNanos(0)
        .sentNanos(clock.epochNanos());
    o.price().mantissa(99_550_000_000L);
    assertTrue(ring.write(NewOrderEncoder.TEMPLATE_ID, scratch, 0, MessageHeaderEncoder.ENCODED_LENGTH + o.encodedLength()));
  }

  private void quoteAndOrderWorking() throws IOException {
    quote(7, 70);
    order(ORDER);
    until(() -> venue.count("S:") == 1 && venue.count("D:") == 1, "the quote and the order at the venue");
    assertEquals(1, session.liveQuotes());
    assertEquals(1, session.liveOrders());
    assertEquals(VenueSession.RUNNING, session.haltState());
  }

  @Test
  void aHaltCancelsWhatIsWorkingBlocksNewQuotesAndIsConfirmedByTheVenue() throws Exception {
    quoteAndOrderWorking();
    session.halt(false);
    assertEquals(VenueSession.HALTING, session.haltState());
    assertEquals(2, session.unconfirmed());
    until(() -> session.haltState() == VenueSession.WITHDRAWN, "the venue's acknowledgements");
    assertTrue(venue.received.contains("Z:70"), venue.received.toString());
    assertTrue(venue.received.contains("F:" + ORDER + ":" + (ORDER | LiveSet.CANCEL_FLAG)), venue.received.toString());
    assertEquals(2, session.haltCancelsSent());
    assertEquals(0, session.unconfirmed());
    assertTrue(session.isActive(), "a confirmed halt does not need the logout");

    // the loop does not know yet and keeps sending: nothing reaches the venue
    quote(8, 80);
    order(ORDER + 1);
    settle();
    assertEquals(1, venue.count("S:"));
    assertEquals(1, venue.count("D:"));
    assertEquals(2, session.blockedOutbound());
    assertEquals(VenueSession.WITHDRAWN, session.haltState());
    session.halt(false); // idempotent
    settle();
    assertEquals(2, session.haltCancelsSent());
  }

  @Test
  void unansweredCancelsAreRepeatedThenTheSessionLogsOutAndDropsTheConnection() throws Exception {
    venue.answerCancels = false;
    venue.answerLogout = false;
    quoteAndOrderWorking();
    session.halt(false);
    until(() -> venue.count("Z:") == 1 && venue.count("F:") == 1, "the first cancels");
    settle();
    assertEquals(1, venue.count("Z:"), "no repeat before the retry time");

    clock.advance(250_000_000L);
    until(() -> venue.count("Z:") == 2 && venue.count("F:") == 2, "the first repeat");
    clock.advance(250_000_000L);
    until(() -> venue.count("Z:") == 3 && venue.count("F:") == 3, "the second repeat");
    assertEquals(VenueSession.HALTING, session.haltState());
    assertEquals(2, session.unconfirmed());

    clock.advance(250_000_000L);
    until(() -> venue.count("5") == 1, "the Logout once the repeats are used up");
    assertEquals(3, venue.count("Z:"));
    assertEquals(VenueSession.HALTING, session.haltState(), "not confirmed until the session is down");

    clock.advance(SECOND);
    until(() -> session.haltState() == VenueSession.WITHDRAWN, "the forced disconnect");
    assertEquals(SessionState.DISCONNECTED, session.state());
    until(() -> venue.closedByEngine, "the venue seeing the disconnect");
    assertEquals(0, session.unconfirmed());
    assertEquals(0, session.liveQuotes() + session.liveOrders(), "the venue cancels on disconnect");
    assertEquals((int) ControlMessages.STATUS_DISCONNECTED, (int) statuses.get(statuses.size() - 1), "the loop is told");
    session.connect(); // a halted session stays down
    assertFalse(session.reconnectPending());
    assertEquals(SessionState.DISCONNECTED, session.state());
  }

  @Test
  void aHaltWithLogoutEndsTheSessionAfterTheCancels() throws Exception {
    quoteAndOrderWorking();
    session.halt(true);
    until(() -> session.haltState() == VenueSession.WITHDRAWN, "the logout");
    assertEquals(SessionState.DISCONNECTED, session.state());
    final int z = venue.received.indexOf("Z:70");
    assertTrue(z >= 0 && z < venue.received.indexOf("5"), "cancels go before the Logout: " + venue.received);
    assertTrue(session.isHalted());
  }

  @Test
  void aLogoutCanBeAddedToAHaltAndTheVenuesOwnReportsEmptyTheLiveSet() throws Exception {
    quoteAndOrderWorking();
    order(ORDER + 1);
    until(() -> venue.count("D:") == 2, "the second order");
    venue.sendExpired(7);
    venue.sendFilled(ORDER);
    venue.sendCancelReject(ORDER + 1 | LiveSet.CANCEL_FLAG, ORDER + 1);
    until(() -> session.liveQuotes() + session.liveOrders() == 0, "the venue's reports");
    session.halt(false);
    assertEquals(VenueSession.WITHDRAWN, session.haltState(), "nothing was working: confirmed at once");
    assertEquals(0, session.haltCancelsSent());
    session.halt(true);
    assertEquals(VenueSession.HALTING, session.haltState(), "a requested logout is confirmed by the disconnect");
    until(() -> session.haltState() == VenueSession.WITHDRAWN, "the logout");
    assertEquals(1, venue.count("5"));
  }

  @Test
  void abortFromAnotherThreadClosesTheSocketAndTheOwnerTellsTheLoop() throws Exception {
    quoteAndOrderWorking();
    final Thread other = new Thread(session::abort, "supervisor");
    other.start();
    other.join();
    until(() -> session.state() == SessionState.DISCONNECTED, "the owner noticing the closed socket");
    until(() -> venue.closedByEngine, "the venue seeing the disconnect");
    assertEquals((int) ControlMessages.STATUS_DISCONNECTED, (int) statuses.get(statuses.size() - 1));
    assertEquals(0, session.liveQuotes() + session.liveOrders());
    session.halt(true);
    assertEquals(VenueSession.WITHDRAWN, session.haltState());
  }

  @Test
  void theLiveSetStopsAtItsCapacityAndSaysSo() {
    final LiveSet live = new LiveSet(101, 2);
    for (int i = 1; i <= 3; i++) {
      final QuoteEncoder q = new QuoteEncoder();
      q.wrapAndApplyHeader(scratch, 0, header).rfqId(i).quoteId(10L * i).instrumentId(INSTRUMENT);
      live.onSent(QuoteEncoder.TEMPLATE_ID, scratch, 0, QuoteEncoder.BLOCK_LENGTH, QuoteEncoder.SCHEMA_VERSION);
    }
    assertEquals(2, live.liveQuotes());
    assertTrue(live.overflowed());
    // a requote on the same RFQ replaces the earlier quote rather than adding one
    final QuoteEncoder q = new QuoteEncoder();
    q.wrapAndApplyHeader(scratch, 0, header).rfqId(1).quoteId(11).instrumentId(INSTRUMENT);
    live.onSent(QuoteEncoder.TEMPLATE_ID, scratch, 0, QuoteEncoder.BLOCK_LENGTH, QuoteEncoder.SCHEMA_VERSION);
    assertEquals(2, live.liveQuotes());
    final List<Long> cancelled = new ArrayList<>();
    final QuoteWithdrawDecoder d = new QuoteWithdrawDecoder();
    final int offered =
        live.cancelAll(
            (b, o, l) -> {
              d.wrap(b, o + MessageHeaderEncoder.ENCODED_LENGTH, QuoteWithdrawDecoder.BLOCK_LENGTH,
                  QuoteWithdrawDecoder.SCHEMA_VERSION);
              cancelled.add(d.quoteId());
              return true;
            },
            1L);
    assertEquals(2, offered);
    assertTrue(cancelled.contains(11L) && cancelled.contains(20L), cancelled.toString());
    live.onDisconnect();
    assertEquals(0, live.liveQuotes());
    assertFalse(live.overflowed());
    assertThrows(IllegalArgumentException.class, () -> new VenueSession.WithdrawPolicy(0, 1, 1));
  }
}
