package io.ratesalgo.app.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.FairValueSource;
import io.ratesalgo.app.pricing.PricingV2;
import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.app.pricing.V2InputIds;
import io.ratesalgo.app.sim.V2DeskProcess;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.core.AnchoredClock;
import io.ratesalgo.core.ControlMessages;
import io.ratesalgo.core.EventClock;
import io.ratesalgo.core.EventLoop;
import io.ratesalgo.core.InboundPublisher;
import io.ratesalgo.core.LoopJournals;
import io.ratesalgo.core.LoopRecovery;
import io.ratesalgo.core.RecoveryClock;
import io.ratesalgo.core.RingBufferContract;
import io.ratesalgo.journal.JournalRecovery;
import io.ratesalgo.messages.sbe.BookAction;
import io.ratesalgo.messages.sbe.BookUpdateEncoder;
import io.ratesalgo.messages.sbe.CurveInputEncoder;
import io.ratesalgo.messages.sbe.CurveInputKind;
import io.ratesalgo.messages.sbe.ExecutionReportEncoder;
import io.ratesalgo.messages.sbe.HeartbeatEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.NewOrderDecoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.QuoteDecoder;
import io.ratesalgo.messages.sbe.QuoteRequestEncoder;
import io.ratesalgo.messages.sbe.QuoteResponseEncoder;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TradePrintEncoder;
import io.ratesalgo.router.FrameSink;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A restart of the v2 desk on its journals, at desk level (rates-core's {@code LoopRecoveryTest}
 * with the real desk on the loop): a desk is driven through curve rounds, books and prints on
 * three venues, won RFQs, hedges on the CLOBs and the futures venue, and a gap on one venue; it
 * stops; a fresh desk is rebuilt from the inbound journal with the outbound journal proving every
 * message it sends. The rebuilt desk must be the desk as it stood, in everything it would trade
 * on, and must carry on from there.
 */
class DeskV2RecoveryTest {

  private static final UsdUniverse UNIVERSE = UsdUniverse.simV2();
  /** 09:00 New York: clear of the 08:30 and 10:00 release windows, in which the desk declines every RFQ. */
  private static final long START = V2DeskProcess.SESSION_OPEN_EPOCH_NANOS + 7_200_000_000_000L;
  private static final long SECOND = 1_000_000_000L;
  private static final long MILLION = 100_000_000L;
  private static final int SEGMENT = 8 << 20;

  @TempDir Path dir;

  /** One desk on journals in the test directory, live or restarted. */
  private final class Run implements AutoCloseable {
    final ManualClock live;
    final ManyToOneRingBuffer ring = RingBufferContract.newInbound(1 << 18);
    final OneToOneRingBuffer[] out = new OneToOneRingBuffer[AppIds.V2_OUTBOUND_RINGS];
    final InboundPublisher publisher = new InboundPublisher(ring);
    final UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
    final MessageHeaderEncoder header = new MessageHeaderEncoder();
    final long[] feedSeq = new long[200];
    final LoopJournals journals;
    final EventLoop loop;
    final DeskGroup group;
    LoopRecovery.Result recovered;
    long execId = 100;
    long rfqId;

    Run(final long nowEpochNanos, final boolean restart) {
      live = new ManualClock(nowEpochNanos);
      for (int r = 0; r < out.length; r++) {
        out[r] = RingBufferContract.newOutbound(1 << 20);
      }
      final DeskLoopFactory factory = new DeskLoopFactory(DeskProfile.v2(), UNIVERSE, FrameSink.NONE);
      if (!restart) {
        final EventClock clock = new EventClock(new AnchoredClock(live));
        journals = LoopJournals.create(dir, "in", "out", SEGMENT, clock);
        loop = factory.create(V2DeskProcess.loopBuilder(clock, ring, out).journals(journals));
        group = factory.last();
        return;
      }
      JournalRecovery.repair(dir, "in");
      JournalRecovery.repair(dir, "out");
      final RecoveryClock recoveryClock = new RecoveryClock(LoopRecovery.firstFrameNanos(dir, "in"));
      final EventClock clock = new EventClock(new AnchoredClock(recoveryClock));
      journals = LoopJournals.open(dir, "in", "out", SEGMENT, clock);
      boolean ok = false;
      try {
        loop = factory.create(V2DeskProcess.loopBuilder(clock, ring, out).journals(journals));
        group = factory.last();
        recovered =
            LoopRecovery.run(
                loop, recoveryClock, dir, "in", "out", journals.outbound(), LoopRecovery.Observer.NONE,
                LoopRecovery.Observer.NONE);
        recoveryClock.goLive(live);
        ok = true;
      } finally {
        if (!ok) {
          journals.close();
        }
      }
    }

    void send(final int length) {
      assertTrue(publisher.offer(buffer, 0, length));
      live.advance(1_000_000L);
      loop.doWork();
    }

    void pass(final long nanos, final long step) {
      for (long done = 0; done < nanos; done += step) {
        live.advance(step);
        loop.doWork();
      }
    }

    void level(final int venue, final int instrument, final long seq, final Side side, final BookAction action,
        final long price, final long quantity) {
      final BookUpdateEncoder e = new BookUpdateEncoder();
      e.wrapAndApplyHeader(buffer, 0, header).venueId(venue).instrumentId(instrument).venueSeqNo(seq).side(side)
          .action(action).level((short) 1).quantity(quantity).orderCount(1).timestampNanos(live.epochNanos());
      e.price().mantissa(price);
      send(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
    }

    /** Both sides of every Treasury on both CLOBs and of the future, a tick either side of the open. */
    void books(final BookAction action) {
      for (final UsdUniverse.BondSpec b : UNIVERSE.bonds()) {
        for (final int venue : new int[] {AppIds.CLOB_VENUE, AppIds.CLOB2_VENUE}) {
          level(venue, b.instrumentId(), ++feedSeq[venue], Side.Buy, action, b.initialMid() - b.tick(), 30 * MILLION);
          level(venue, b.instrumentId(), ++feedSeq[venue], Side.Sell, action, b.initialMid() + b.tick(), 30 * MILLION);
        }
      }
      final UsdUniverse.FutureSpec f = UNIVERSE.futures().get(0);
      final int venue = AppIds.FUTURES_VENUE;
      level(venue, f.instrumentId(), ++feedSeq[venue], Side.Buy, action, f.initialMid() - f.tick(), 400 * 100L);
      level(venue, f.instrumentId(), ++feedSeq[venue], Side.Sell, action, f.initialMid() + f.tick(), 400 * 100L);
    }

    void curveRound(final long shiftMantissa) {
      final PricingV2 pricing = group.pricingV2();
      final long stamp = live.epochNanos();
      for (int i = 0; i < pricing.inputCount(); i++) {
        final int id = pricing.inputId(i);
        final CurveInputKind kind =
            id == V2InputIds.OVERNIGHT
                ? CurveInputKind.Fixing
                : V2InputIds.isSr1(id) || V2InputIds.isSr3(id) ? CurveInputKind.Future : CurveInputKind.ParSwap;
        final UsdUniverse.SwapSpec s = UNIVERSE.swap(id);
        final long rate = (s != null ? s.initialMid() : UNIVERSE.swaps().get(0).initialMid()) + shiftMantissa;
        final CurveInputEncoder e = new CurveInputEncoder();
        e.wrapAndApplyHeader(buffer, 0, header).curveId(AppIds.SOFR_CURVE).kind(kind).instrumentId(id).tenor("    ")
            .timestampNanos(stamp);
        e.value().mantissa(
            kind == CurveInputKind.Fixing ? UNIVERSE.swaps().get(0).initialMid()
                : kind == CurveInputKind.Future ? 100_000_000_000L - rate : rate);
        send(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
      }
      final HeartbeatEncoder hb = new HeartbeatEncoder();
      hb.wrapAndApplyHeader(buffer, 0, header).sourceId(AppIds.CURVE_HEARTBEAT_SOURCE).timestampNanos(stamp);
      send(MessageHeaderEncoder.ENCODED_LENGTH + hb.encodedLength());
    }

    /** Quotes the desk sent since the last call: {rfqId, quoteId, price}. */
    List<long[]> quotes() {
      final List<long[]> found = new ArrayList<>();
      final QuoteDecoder q = new QuoteDecoder();
      final MessageHeaderDecoder h = new MessageHeaderDecoder();
      out[AppIds.D2C_GATEWAY].read(
          (type, b, index, length) -> {
            if (type == QuoteDecoder.TEMPLATE_ID) {
              q.wrapAndApplyHeader(b, index, h);
              found.add(new long[] {q.rfqId(), q.quoteId(), q.bidQuantity() > 0 ? q.bidPrice().mantissa() : q.askPrice().mantissa()});
            }
          },
          4096);
      return found;
    }

    /** Child orders the desk sent to a venue since the last call: {clOrdId, quantity, price, instrumentId, side}. */
    List<long[]> orders(final int gateway) {
      final List<long[]> found = new ArrayList<>();
      final NewOrderDecoder d = new NewOrderDecoder();
      final MessageHeaderDecoder h = new MessageHeaderDecoder();
      out[gateway].read(
          (type, b, index, length) -> {
            if (type == NewOrderDecoder.TEMPLATE_ID) {
              d.wrapAndApplyHeader(b, index, h);
              found.add(new long[] {d.clOrdId(), d.quantity(), d.price().mantissa(), d.instrumentId(), d.side() == Side.Buy ? 1 : 0});
            }
          },
          4096);
      return found;
    }

    /** An RFQ and, if the desk quoted, the venue's Done at the desk's price with a cover. */
    boolean rfqWon(final int instrument, final Side clientSide, final long quantity) {
      final QuoteRequestEncoder e = new QuoteRequestEncoder();
      e.wrapAndApplyHeader(buffer, 0, header).venueId(AppIds.D2C_VENUE).rfqId(++rfqId).clientId(2001)
          .instrumentId(instrument).side(clientSide).quantity(quantity).dealerCount((short) 4)
          .receivedNanos(live.epochNanos()).expiryNanos(0L);
      e.putCurrency((byte) 'U', (byte) 'S', (byte) 'D');
      send(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
      final List<long[]> sent = quotes();
      if (sent.isEmpty()) {
        return false;
      }
      final QuoteResponseEncoder r = new QuoteResponseEncoder();
      r.wrapAndApplyHeader(buffer, 0, header).venueId(AppIds.D2C_VENUE).rfqId(sent.get(0)[0]).quoteId(sent.get(0)[1])
          .status(QuoteStatus.Done).tradedQuantity(quantity).timestampNanos(live.epochNanos());
      r.tradedPrice().mantissa(sent.get(0)[2]);
      r.coverPrice().mantissa(sent.get(0)[2]);
      send(MessageHeaderEncoder.ENCODED_LENGTH + r.encodedLength());
      return true;
    }

    void report(final int venue, final long[] order, final OrdStatus status, final long lastQuantity) {
      final ExecutionReportEncoder e = new ExecutionReportEncoder();
      e.wrapAndApplyHeader(buffer, 0, header).venueId(venue).clOrdId(order[0]).venueOrderId(order[0]).execId(++execId)
          .instrumentId((int) order[3]).side(order[4] == 1 ? Side.Buy : Side.Sell).ordStatus(status)
          .lastQuantity(lastQuantity).leavesQuantity(order[1] - lastQuantity).cumQuantity(lastQuantity)
          .transactNanos(live.epochNanos());
      e.lastPrice().mantissa(lastQuantity > 0 ? order[2] : 0);
      send(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
    }

    void print(final int venue, final int instrument, final long price, final long quantity) {
      final TradePrintEncoder e = new TradePrintEncoder();
      e.wrapAndApplyHeader(buffer, 0, header).venueId(venue).instrumentId(instrument).venueSeqNo(++feedSeq[venue + 50])
          .aggressorSide(Side.Buy).quantity(quantity).timestampNanos(live.epochNanos());
      e.price().mantissa(price);
      send(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
    }

    void status(final int id, final byte status) {
      send(ControlMessages.encodeGatewayStatus(buffer, 0, live.epochNanos(), id, status));
    }

    @Override
    public void close() {
      journals.close();
    }
  }

  @Test
  void aDeskRebuiltFromItsJournalsIsTheDeskAsItStoodAndCarriesOn() {
    final int[] ids = UNIVERSE.instrumentIds();
    final int tenYearSwap = UNIVERSE.swaps().get(5).instrumentId();
    final int thirtyYearSwap = UNIVERSE.swaps().get(7).instrumentId();
    final int future = UNIVERSE.futureIds()[0];
    final long digest;
    final long[] positions = new long[ids.length];
    final long[] fairValues = new long[ids.length];
    final long[] spreadPositions = new long[ids.length];
    final long stoppedAt;
    final long hedgesRouted;
    final long hedgeFills;
    final int liveChildren;
    final int workingParents;
    final long reprices;
    final long quotesSent;
    final int liveQuotes;
    final int timers;
    long filledOnFutures = 0;
    long filledOnClobs = 0;

    try (Run first = new Run(START, false)) {
      first.books(BookAction.Add);
      first.curveRound(0L);
      first.pass(500_000_000L, 10_000_000L);
      first.books(BookAction.Change);
      first.curveRound(200_000L);
      // the desk wins client trades in swaps: receivers and payers of size, so the hedger must act
      int won = 0;
      for (int i = 0; i < 6; i++) {
        won += first.rfqWon(i % 2 == 0 ? tenYearSwap : thirtyYearSwap, Side.Buy, 50 * MILLION) ? 1 : 0;
        first.pass(100_000_000L, 10_000_000L);
      }
      assertTrue(won > 0, "the desk quoted and won");
      assertTrue(first.group.desk().quoting().fills() > 0);

      // time passes: the hedger routes parents, the venues fill some children and leave others working
      for (int round = 0; round < 60; round++) {
        first.pass(SECOND, 10_000_000L);
        first.books(BookAction.Change);
        first.curveRound(200_000L + (round % 3) * 100_000L);
        first.print(AppIds.CLOB_VENUE, UNIVERSE.bondIds()[2], UNIVERSE.bonds().get(2).initialMid(), 2 * MILLION);
        first.print(AppIds.FUTURES_VENUE, future, UNIVERSE.futures().get(0).initialMid(), 3 * 100L);
        for (final int gateway : new int[] {AppIds.CLOB_GATEWAY, AppIds.CLOB2_GATEWAY, AppIds.FUTURES_GATEWAY}) {
          final int venue =
              gateway == AppIds.CLOB_GATEWAY ? AppIds.CLOB_VENUE
                  : gateway == AppIds.CLOB2_GATEWAY ? AppIds.CLOB2_VENUE : AppIds.FUTURES_VENUE;
          int n = 0;
          for (final long[] order : first.orders(gateway)) {
            // two children in three fill in full; the third is acknowledged and stays working
            if (n++ % 3 == 2) {
              first.report(venue, order, OrdStatus.New, 0);
            } else {
              first.report(venue, order, OrdStatus.Filled, order[1]);
              if (venue == AppIds.FUTURES_VENUE) {
                filledOnFutures++;
              } else {
                filledOnClobs++;
              }
            }
          }
        }
      }
      assertTrue(first.group.hedgesRouted() > 0, "the hedger routed");
      assertTrue(filledOnFutures + filledOnClobs > 0, "children were filled");

      // the second CLOB's feed gaps just before the stop
      first.status(AppIds.CLOB2_FEED_STATUS, ControlMessages.STATUS_DISCONNECTED);
      assertFalse(first.group.venueUsable(AppIds.CLOB2_VENUE));

      digest = first.group.stateDigest(ids);
      for (int i = 0; i < ids.length; i++) {
        positions[i] = first.group.desk().risk().position(ids[i]);
        fairValues[i] = first.group.binding().fairMid(ids[i]);
        spreadPositions[i] = first.group.spreadRisk().position(ids[i]);
      }
      hedgesRouted = first.group.hedgesRouted();
      hedgeFills = first.group.hedgeFills();
      liveChildren = first.group.liveChildren();
      workingParents = first.group.desk().hedger(CurrencyCode.USD).workingParents();
      reprices = first.group.pricingV2().reprices();
      quotesSent = first.group.desk().quoting().quotesSent();
      liveQuotes = first.group.desk().quoting().liveQuotes();
      timers = first.group.loop().timers().liveCount();
      stoppedAt = first.live.epochNanos();
      System.out.println("desk v2 before the stop: fills " + first.group.desk().quoting().fills() + ", hedges routed "
          + hedgesRouted + ", hedge fills " + hedgeFills + " (futures children " + filledOnFutures + ", CLOB children "
          + filledOnClobs + "), live children " + liveChildren + ", working parents " + workingParents + ", reprices "
          + reprices);
    }
    boolean anyPosition = false;
    for (final long p : positions) {
      anyPosition |= p != 0;
    }
    assertTrue(anyPosition, "the desk holds positions at the stop");

    // ten seconds later the process is back
    try (Run second = new Run(stoppedAt + 10 * SECOND, true)) {
      assertTrue(second.recovered.inboundFrames() > 1_000, "the inbound journal was replayed");
      assertTrue(second.recovered.outboundVerified() > 0, "what the rebuilt desk sent was checked against the journal");
      assertEquals(0, second.recovered.outboundCompleted(), "the stopped desk had journaled everything it sent");
      final DeskGroup rebuilt = second.group;
      for (int i = 0; i < ids.length; i++) {
        assertEquals(positions[i], rebuilt.desk().risk().position(ids[i]), "position of " + ids[i]);
        assertEquals(fairValues[i], rebuilt.binding().fairMid(ids[i]), "fair value of " + ids[i]);
        assertEquals(spreadPositions[i], rebuilt.spreadRisk().position(ids[i]), "spread position of " + ids[i]);
      }
      assertEquals(hedgesRouted, rebuilt.hedgesRouted());
      assertEquals(hedgeFills, rebuilt.hedgeFills());
      assertEquals(liveChildren, rebuilt.liveChildren());
      assertEquals(workingParents, rebuilt.desk().hedger(CurrencyCode.USD).workingParents());
      assertEquals(reprices, rebuilt.pricingV2().reprices());
      assertEquals(0, rebuilt.pricingV2().unfedInputs());
      assertFalse(rebuilt.venueUsable(AppIds.CLOB2_VENUE), "the gapped venue is still gapped");
      assertTrue(rebuilt.venueUsable(AppIds.CLOB_VENUE) && rebuilt.venueUsable(AppIds.FUTURES_VENUE));
      assertNotEquals(FairValueSource.NO_VALUE, rebuilt.binding().fairMid(future));
      assertEquals(quotesSent, rebuilt.desk().quoting().quotesSent());
      assertEquals(liveQuotes, rebuilt.desk().quoting().liveQuotes());
      assertEquals(timers, rebuilt.loop().timers().liveCount(), "timers armed");
      assertEquals(digest, rebuilt.stateDigest(ids), "positions, quotes, hedges, children, venues, curve");

      // and it carries on: the sessions are gone, they come back, the feeds are rebuilt, it quotes
      for (final int gateway :
          new int[] {AppIds.D2C_GATEWAY, AppIds.CLOB_GATEWAY, AppIds.CLOB2_GATEWAY, AppIds.FUTURES_GATEWAY}) {
        second.status(gateway, ControlMessages.STATUS_DISCONNECTED);
      }
      assertEquals(0, rebuilt.desk().quoting().liveQuotes());
      for (final int gateway :
          new int[] {AppIds.D2C_GATEWAY, AppIds.CLOB_GATEWAY, AppIds.CLOB2_GATEWAY, AppIds.FUTURES_GATEWAY}) {
        second.status(gateway, ControlMessages.STATUS_CONNECTED);
      }
      for (final int feed : new int[] {AppIds.CLOB_FEED_STATUS, AppIds.CLOB2_FEED_STATUS, AppIds.FUTURES_FEED_STATUS}) {
        second.status(feed, ControlMessages.STATUS_DISCONNECTED);
      }
      second.feedSeq[AppIds.CLOB_VENUE] = 5_000;
      second.feedSeq[AppIds.CLOB2_VENUE] = 5_000;
      second.feedSeq[AppIds.FUTURES_VENUE] = 5_000;
      for (final UsdUniverse.BondSpec b : UNIVERSE.bonds()) {
        second.level(AppIds.CLOB_VENUE, b.instrumentId(), 5_000, Side.Buy, BookAction.Clear, 0, 0);
        second.level(AppIds.CLOB2_VENUE, b.instrumentId(), 5_000, Side.Buy, BookAction.Clear, 0, 0);
        for (final int venue : new int[] {AppIds.CLOB_VENUE, AppIds.CLOB2_VENUE}) {
          second.level(venue, b.instrumentId(), 5_000, Side.Buy, BookAction.Add, b.initialMid() - b.tick(), 30 * MILLION);
          second.level(venue, b.instrumentId(), 5_000, Side.Sell, BookAction.Add, b.initialMid() + b.tick(), 30 * MILLION);
        }
      }
      final UsdUniverse.FutureSpec f = UNIVERSE.futures().get(0);
      second.level(AppIds.FUTURES_VENUE, future, 5_000, Side.Buy, BookAction.Clear, 0, 0);
      second.level(AppIds.FUTURES_VENUE, future, 5_000, Side.Buy, BookAction.Add, f.initialMid() - f.tick(), 400 * 100L);
      second.level(AppIds.FUTURES_VENUE, future, 5_000, Side.Sell, BookAction.Add, f.initialMid() + f.tick(), 400 * 100L);
      for (final int feed : new int[] {AppIds.CLOB_FEED_STATUS, AppIds.CLOB2_FEED_STATUS, AppIds.FUTURES_FEED_STATUS}) {
        second.status(feed, ControlMessages.STATUS_CONNECTED);
      }
      assertTrue(rebuilt.venueUsable(AppIds.CLOB2_VENUE), "the snapshot made the second CLOB whole");
      second.curveRound(300_000L);
      second.quotes();
      second.rfqId = 10_000; // the venue's ids carry on; an id from before the stop would be a duplicate
      final long quotesBefore = rebuilt.desk().quoting().quotesSent();
      second.rfqWon(tenYearSwap, Side.Sell, 10 * MILLION);
      assertTrue(rebuilt.desk().quoting().quotesSent() > quotesBefore, "the rebuilt desk quotes again");
    }
  }
}
