package io.ratesalgo.venues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.fix.codec.FixParser;
import io.ratesalgo.fix.codec.FixTags;
import io.ratesalgo.fix.codec.MsgType;
import io.ratesalgo.fix.gateway.InstrumentMap;
import io.ratesalgo.fix.gateway.VenueGateway;
import io.ratesalgo.fix.order.OrderStateTracker;
import io.ratesalgo.fix.session.FixSession;
import io.ratesalgo.fix.session.FixSessionHandler;
import io.ratesalgo.fix.session.MappedSequenceStore;
import io.ratesalgo.messages.sbe.ExecutionReportDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.NewOrderEncoder;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import io.ratesalgo.venues.clob.ClobSimulator;
import io.ratesalgo.venues.feed.InProcessFeedChannel;
import io.ratesalgo.venues.sim.AcceptorOptions;
import io.ratesalgo.venues.sim.EngineSession;
import io.ratesalgo.venues.sim.Instrument;
import io.ratesalgo.venues.sim.Px;
import io.ratesalgo.venues.sim.VenueHarness;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Resuming a session with ResetSeqNumFlag off, our session and mapped sequence store against the
 * QuickFIX/J acceptor over loopback: fills missed across a restart are resent and booked once,
 * fills replayed after a crash are recognised as duplicates, and a restarted simulator with a
 * store directory keeps its numbers and does not hand out an ExecID twice.
 */
class VenueResumeTest {

  private static final long PX = Px.price("98");

  @TempDir Path dir;

  private final List<UnsafeBuffer> sbe = new ArrayList<>();
  private final UnsafeBuffer out = new UnsafeBuffer(new byte[256]);

  /** Fills as they arrive at the session: ExecID, with a star when flagged PossDup. */
  private final List<String> arrived = new ArrayList<>();

  private VenueGateway gateway(final ManualClock clock) {
    final InstrumentMap map = new InstrumentMap();
    for (final Instrument i : Venues.treasuries()) {
      map.register(i.instrumentId(), i.securityId());
    }
    return new VenueGateway(102, map, (b, o, l) -> {
      final byte[] copy = new byte[l];
      b.getBytes(o, copy);
      sbe.add(new UnsafeBuffer(copy));
      return true;
    }, clock, new OrderStateTracker(256, 1024));
  }

  /** Notes each fill and whether it was flagged, then hands the message to the gateway. */
  private FixSessionHandler noting(final VenueGateway gateway) {
    return new FixSessionHandler() {
      @Override
      public void onApplicationMessage(final FixSession s, final FixParser m, final boolean possDup) {
        if (isFill(m)) {
          arrived.add(m.getString(FixTags.EXEC_ID) + (possDup ? "*" : ""));
        }
        gateway.onApplicationMessage(s, m, possDup);
      }
    };
  }

  private static boolean isFill(final FixParser m) {
    return m.msgType() == MsgType.EXECUTION_REPORT && "F".equals(m.getString(FixTags.EXEC_TYPE));
  }

  private void order(final VenueGateway g, final long clOrdId, final long qty) {
    final NewOrderEncoder e = new NewOrderEncoder();
    e.wrapAndApplyHeader(out, 0, new MessageHeaderEncoder()).venueId(102).clOrdId(clOrdId).instrumentId(1007)
        .side(Side.Buy).ordType(OrdType.Limit).timeInForce(TimeInForce.Day).quantity(qty * 100);
    e.price().mantissa(PX);
    assertTrue(g.offer(out, 0, MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength()));
  }

  /** ExecIDs of the fills the gateway passed on to the trading side, in order. */
  private List<Long> booked() {
    final MessageHeaderDecoder header = new MessageHeaderDecoder();
    final List<Long> ids = new ArrayList<>();
    for (final UnsafeBuffer b : sbe) {
      header.wrap(b, 0);
      if (header.templateId() == ExecutionReportDecoder.TEMPLATE_ID) {
        final ExecutionReportDecoder d = new ExecutionReportDecoder();
        d.wrapAndApplyHeader(b, 0, header);
        if (d.lastQuantity() > 0) {
          ids.add(d.execId());
        }
      }
    }
    return ids;
  }

  /** A dealer sells one million into our resting bid, and time moves past the workup that follows. */
  private static void sellTo(final VenueHarness h, final ClobSimulator clob, final String dealer) {
    assertTrue(clob.submitInternal(dealer, 1007, false, PX, 1_000_000, 0) > 0);
    h.advance(10_000_000_000L);
  }

  private static ClobSimulator clob(final ManualClock clock) {
    return (ClobSimulator) Venues.create("clob", clock, 3, new InProcessFeedChannel());
  }

  /** A resuming session whose numbers come from, and go to, the mapped store. */
  private static EngineSession resume(
      final VenueHarness h, final FixSessionHandler handler, final MappedSequenceStore store) {
    final EngineSession e = h.prepare("RALGO", handler, null, null, null, false);
    e.session().setSequenceNumbers(store.nextOutSeq(), store.resumeInSeq());
    e.session().sequenceStore(store);
    return e;
  }

  @Test
  void fillsMissedBeforeARestartAreResentOnceWithPossDup() {
    final ManualClock clock = new ManualClock(Venues.T0);
    final ClobSimulator clob = clob(clock);
    final VenueGateway g = gateway(clock);
    final Path file = dir.resolve("RALGO-CLOB.seq");
    try (VenueHarness h = new VenueHarness(clob, clock)) {
      MappedSequenceStore store = MappedSequenceStore.open(file);
      final long outBefore;
      final long inBefore;
      try (EngineSession e = resume(h, noting(g), store)) {
        g.bind(e.session());
        e.logon();
        order(g, 1, 4_000_000);
        e.quiesce();
        store.safeInSeq(e.session().expectedInSeq()); // everything so far is journalled
        // two fills leave the venue and never reach the session: lost on the way in
        final int[] dropped = {0};
        final FixParser seen = new FixParser();
        e.transport().filter((b, o, l) -> {
          seen.wrap(b, o, l);
          if (isFill(seen)) {
            dropped[0]++;
          }
          return false;
        });
        sellTo(h, clob, "DLR-A");
        e.pollUntil(() -> dropped[0] == 1, 10_000);
        sellTo(h, clob, "DLR-B");
        e.pollUntil(() -> dropped[0] == 2, 10_000);
        assertEquals(List.of(), booked());
        outBefore = e.session().nextOutSeq();
        inBefore = e.session().expectedInSeq();
        // the process dies: the socket drops, nothing is logged out or closed cleanly
        e.disconnect();
        store.closeWithoutMarkingClean();
      }

      // a new process: new session object, numbers from the file
      store = MappedSequenceStore.open(file);
      assertTrue(store.existed());
      assertFalse(store.cleanAtOpen());
      assertEquals(outBefore, store.nextOutSeq());
      assertEquals(inBefore, store.resumeInSeq());
      try (EngineSession e = resume(h, noting(g), store)) {
        g.bind(e.session());
        e.logon();
        e.quiesce();
        assertEquals(1, e.session().stats().gapsDetected(), "the venue's Logon was above what we expected");
        assertEquals(1, e.session().stats().resendRequestsSent());
        assertEquals(0, e.session().stats().sequenceTooLow());
        final List<Long> fills = clob.executionIds("RALGO");
        assertEquals(2, fills.size());
        assertEquals(List.of(fills.get(0) + "*", fills.get(1) + "*"), arrived, "each missed fill once, flagged");
        assertEquals(fills, booked());
        assertEquals(0, g.orders().duplicates());
        // the session carries on live (the venue cancelled order 1 when we dropped, so a new order)
        order(g, 2, 2_000_000);
        e.quiesce();
        sellTo(h, clob, "DLR-C");
        e.quiesce();
        assertEquals(3, booked().size());
        assertEquals(3, arrived.size());
        assertFalse(arrived.get(2).endsWith("*"), "a live fill is not flagged");
        assertEquals(e.session().expectedInSeq(), store.expectedInSeq());
        assertEquals(e.session().nextOutSeq(), store.nextOutSeq());
      }
      store.close();
    }
  }

  @Test
  void fillsReplayedAfterACrashAreBookedOnce() {
    final ManualClock clock = new ManualClock(Venues.T0);
    final ClobSimulator clob = clob(clock);
    final VenueGateway g = gateway(clock);
    final Path file = dir.resolve("RALGO-CLOB.seq");
    try (VenueHarness h = new VenueHarness(clob, clock)) {
      MappedSequenceStore store = MappedSequenceStore.open(file);
      try (EngineSession e = resume(h, noting(g), store)) {
        g.bind(e.session());
        e.logon();
        order(g, 1, 4_000_000);
        e.quiesce();
        store.safeInSeq(e.session().expectedInSeq()); // journalled up to here
        sellTo(h, clob, "DLR-A");
        e.quiesce();
        sellTo(h, clob, "DLR-B");
        e.quiesce();
        assertEquals(2, booked().size(), "both fills were processed");
        // the process dies before the journal confirmed them: the watermark is still below them
        e.disconnect();
        store.closeWithoutMarkingClean();
      }
      final List<Long> fills = clob.executionIds("RALGO");
      assertEquals(fills, booked());

      store = MappedSequenceStore.open(file);
      assertTrue(store.resumeInSeq() < store.expectedInSeq(), "resume below what was received");
      try (EngineSession e = resume(h, noting(g), store)) {
        g.bind(e.session());
        e.logon();
        e.quiesce();
        assertEquals(1, e.session().stats().resendRequestsSent());
        assertEquals(
            List.of(fills.get(0) + "", fills.get(1) + "", fills.get(0) + "*", fills.get(1) + "*"),
            arrived,
            "the venue sent both fills again, flagged");
        assertEquals(2, g.orders().duplicates(), "the tracker knew both ExecIDs");
        assertEquals(fills, booked(), "and each was booked once");
        assertEquals(0, e.session().stats().sequenceTooLow());
      }
      store.close();
    }
  }

  @Test
  void aRestartedSimulatorKeepsItsNumbersItsMessagesAndItsIdSpace() throws Exception {
    final ManualClock clock = new ManualClock(Venues.T0);
    final VenueGateway g = gateway(clock);
    final Path storeDir = dir.resolve("venue-store");
    final AcceptorOptions options = new AcceptorOptions(null, null, null, storeDir);
    final long engineOut;
    final long engineIn;
    final long inBeforeFill;
    final long venueOut;
    final List<Long> firstRunIds;
    final ClobSimulator first = clob(clock);
    try (VenueHarness h = new VenueHarness(first, clock, options);
        EngineSession e = h.prepare("RALGO", noting(g), null, null, null, false)) {
      g.bind(e.session());
      e.logon();
      order(g, 1, 4_000_000);
      e.quiesce();
      inBeforeFill = e.session().expectedInSeq();
      sellTo(h, first, "DLR-A");
      e.quiesce();
      firstRunIds = first.executionIds("RALGO");
      assertEquals(1, firstRunIds.size());
      assertTrue(firstRunIds.get(0) < (1L << 32), "the first run numbers from 1");
      e.disconnect();
      engineOut = e.session().nextOutSeq();
      engineIn = e.session().expectedInSeq();
      venueOut = first.session("RALGO").getExpectedSenderNum();
      assertEquals(engineIn, venueOut);
    }
    assertEquals("1", Files.readString(storeDir.resolve("CLOB.generation"), StandardCharsets.US_ASCII));

    // the simulator process restarts on the same store directory
    final ClobSimulator second = clob(clock);
    try (VenueHarness h = new VenueHarness(second, clock, options)) {
      assertEquals(venueOut, second.session("RALGO").getExpectedSenderNum(), "outbound number kept");
      assertEquals(engineOut, second.session("RALGO").getExpectedTargetNum(), "inbound number kept");
      // our side also lost the fill of the first run (say): it asks from before it
      try (EngineSession e = h.prepare("RALGO", noting(g), null, null, null, false)) {
        e.session().setSequenceNumbers(engineOut, inBeforeFill);
        g.bind(e.session());
        e.logon();
        e.quiesce();
        assertEquals(0, e.session().stats().sequenceTooLow(), "a venue that had reset would be below our number");
        assertEquals(1, e.session().stats().resendRequestsSent());
        assertEquals(
            List.of(firstRunIds.get(0) + "", firstRunIds.get(0) + "*"),
            arrived,
            "the restarted venue resent the fill of its first run from its store");
        assertEquals(1, g.orders().duplicates());

        // new business on the restarted venue: ids above every id of the first run
        order(g, 2, 2_000_000);
        e.quiesce();
        sellTo(h, second, "DLR-B");
        e.quiesce();
        final List<Long> secondRunIds = second.executionIds("RALGO");
        assertEquals(1, secondRunIds.size());
        assertTrue(secondRunIds.get(0) > (1L << 32), "second run ids start at 2^32: " + secondRunIds);
        assertTrue(secondRunIds.get(0) < (1L << 43), "and stay below the router's ExecID bound");
        final Set<Long> all = new HashSet<>(booked());
        assertEquals(2, all.size(), "two fills, two ExecIDs");
        assertEquals(1, g.orders().duplicates(), "the new fill was not mistaken for an old one");
      }
    }
    assertEquals("2", Files.readString(storeDir.resolve("CLOB.generation"), StandardCharsets.US_ASCII));
  }

  @Test
  void theIdGenerationHasALimitAndMustBeReadable() throws Exception {
    final ManualClock clock = new ManualClock(Venues.T0);
    final Path storeDir = dir.resolve("worn-store");
    Files.createDirectories(storeDir);
    final AcceptorOptions options = new AcceptorOptions(null, null, null, storeDir);
    Files.writeString(storeDir.resolve("CLOB.generation"), "2048", StandardCharsets.US_ASCII);
    final ClobSimulator worn = clob(clock);
    final IllegalStateException past =
        assertThrows(IllegalStateException.class, () -> worn.start(VenueHarness.freePort(), options));
    assertTrue(past.getMessage().contains("empty store directory"), past.getMessage());
    assertEquals(0, worn.port());

    Files.writeString(storeDir.resolve("CLOB.generation"), "not a number", StandardCharsets.US_ASCII);
    final IllegalStateException unreadable =
        assertThrows(IllegalStateException.class, () -> clob(clock).start(VenueHarness.freePort(), options));
    assertTrue(unreadable.getMessage().contains("CLOB.generation"), unreadable.getMessage());

    // the last generation that fits still starts
    Files.writeString(storeDir.resolve("CLOB.generation"), "2047", StandardCharsets.US_ASCII);
    final ClobSimulator last = clob(clock);
    try (VenueHarness h = new VenueHarness(last, clock, options)) {
      final long id = last.submitInternal("DLR-A", 1007, true, PX, 1_000_000, 0);
      assertTrue(id > (2047L << 32) && id < (1L << 43), "id " + id);
      assertTrue(h.simulator().port() > 0);
    }
  }
}
