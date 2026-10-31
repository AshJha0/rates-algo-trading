package io.ratesalgo.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.app.loop.AppIds;
import io.ratesalgo.app.loop.DeskLoopFactory;
import io.ratesalgo.app.loop.DeskSettings;
import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.common.time.SystemClock;
import io.ratesalgo.core.AnchoredClock;
import io.ratesalgo.core.EventClock;
import io.ratesalgo.core.EventLoop;
import io.ratesalgo.core.InboundPublisher;
import io.ratesalgo.core.LoopCounters;
import io.ratesalgo.core.LoopRunner;
import io.ratesalgo.core.RingBufferContract;
import io.ratesalgo.journal.JournalWriter;
import io.ratesalgo.messages.sbe.BookAction;
import io.ratesalgo.messages.sbe.BookUpdateEncoder;
import io.ratesalgo.messages.sbe.CurveInputEncoder;
import io.ratesalgo.messages.sbe.CurveInputKind;
import io.ratesalgo.messages.sbe.HeartbeatEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.QuoteRequestEncoder;
import io.ratesalgo.messages.sbe.QuoteResponseEncoder;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.router.FrameSink;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The allocation gate of rates-core's {@code LoopAllocationTest}, run with the real desk on the
 * loop (API_SPEC.md Section 18.4): the pricing binding with incremental curve rebuilds, the
 * composite book, the quoting engine, the risk engine, the hedger and the routers, all behind the
 * same handlers the process registers, with inbound and outbound journals.
 *
 * <p>The traffic mixes QuoteRequests (each one priced, risk-checked and usually quoted),
 * QuoteResponses (one in eight a fill, which moves the risk ladder and so the hedger, whose
 * parents go to the routers), BookUpdates on every Treasury, curve-feed Heartbeats and one
 * CurveInput every 64 messages (an incremental bootstrap and a full recompute of the ladders); the
 * test thread answers every Quote as the D2C venue would and the drainer fills every child order
 * as the CLOB would. Windowed as in rates-core (DR-INT-02), with one difference recorded in
 * DR-R2W-12: no window after the first may allocate more than 1,024 bytes on the loop thread, and
 * at least one of them must allocate nothing. The desk's rare paths (a first hedge parent on an
 * instrument, an enum's first use, a deoptimisation) add a few hundred bytes at irregular times,
 * so the last window is not always the clean one; a per-message leak of even one byte still shows
 * as 150,000 bytes in every window.
 */
class WiredLoopAllocationTest {

  private static final int WARM_UP = 600_000;
  private static final int WINDOW = 150_000;
  private static final int WINDOWS = 4;
  private static final long LATER_WINDOW_BOUND = 1024L;
  private static final int EXTRA_WARM_UP = 4;

  @TempDir Path dir;

  private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final QuoteRequestEncoder rfq = new QuoteRequestEncoder();
  private final QuoteResponseEncoder response = new QuoteResponseEncoder();
  private final BookUpdateEncoder book = new BookUpdateEncoder();
  private final CurveInputEncoder curve = new CurveInputEncoder();
  private final HeartbeatEncoder heartbeat = new HeartbeatEncoder();
  private final UsdUniverse universe = UsdUniverse.standard();
  private long bookSeq;
  private OneToOneRingBuffer venueSide;
  private final long[] pendingRfq = new long[1 << 16];
  private final long[] pendingQuote = new long[1 << 16];
  private final int[] pendingInstrument = new int[1 << 16];
  private int pendingHead;
  private int pendingTail;
  private final io.ratesalgo.messages.sbe.QuoteDecoder quote = new io.ratesalgo.messages.sbe.QuoteDecoder();
  private final io.ratesalgo.messages.sbe.MessageHeaderDecoder headerDecoder = new io.ratesalgo.messages.sbe.MessageHeaderDecoder();
  private final org.agrona.concurrent.MessageHandler onQuote =
      (t, b, i, l) -> {
        if (t == io.ratesalgo.messages.sbe.QuoteDecoder.TEMPLATE_ID) {
          quote.wrapAndApplyHeader(b, i, headerDecoder);
          pendingRfq[pendingTail & 0xFFFF] = quote.rfqId();
          pendingQuote[pendingTail & 0xFFFF] = quote.quoteId();
          pendingInstrument[pendingTail & 0xFFFF] = quote.instrumentId();
          pendingTail++;
        }
      };
  private long venueSeq;
  private long rfqId;

  @Test
  void theWiredDeskAllocatesNothingOnTheLoopThread() throws Exception {
    final EventClock clock = new EventClock(new AnchoredClock(SystemClock.INSTANCE));
    final ManyToOneRingBuffer inbound = RingBufferContract.newInbound(RingBufferContract.DEFAULT_INBOUND_CAPACITY);
    final OneToOneRingBuffer[] outbound = new OneToOneRingBuffer[AppIds.OUTBOUND_RINGS];
    for (int r = 0; r < outbound.length; r++) {
      outbound[r] = RingBufferContract.newOutbound(RingBufferContract.DEFAULT_INBOUND_CAPACITY);
    }
    try (JournalWriter in = JournalWriter.create(dir.resolve("in.raj"), 512 << 20, clock);
        JournalWriter out = JournalWriter.create(dir.resolve("out.raj"), 256 << 20, clock)) {
      final DeskLoopFactory factory = new DeskLoopFactory(universe, DeskSettings.defaults().withQuoteRate(1e9), FrameSink.NONE);
      final EventLoop loop =
          factory.create(
              EventLoop.builder(clock)
                  .inbound(inbound)
                  .outbound(outbound[0])
                  .outbound(outbound[1])
                  .outbound(outbound[2])
                  .inboundJournal(in::append)
                  .outboundJournal(out::append));
      final AtomicBoolean draining = new AtomicBoolean(true);
      final ClobVenue clobVenue = new ClobVenue(new InboundPublisher(inbound));
      final Thread drainer =
          new Thread(
              () -> {
                while (draining.get()) {
                  int n = outbound[AppIds.CLOB_GATEWAY].read(clobVenue, 1024);
                  n += outbound[AppIds.CONTROL_GATEWAY].read((t, b, i, l) -> {}, 1024);
                  if (n == 0) {
                    Thread.onSpinWait();
                  }
                }
              },
              "wired-alloc-drain");
      drainer.setDaemon(true);
      drainer.start();
      final LoopRunner runner =
          LoopRunner.builder(loop).name("wired-alloc-loop").idleStrategy(new BusySpinIdleStrategy()).daemon(true).build()
              .start();
      final com.sun.management.ThreadMXBean threads =
          (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
      threads.getThreadAllocatedBytes(runner.threadId());

      final InboundPublisher publisher = new InboundPublisher(inbound);
      venueSide = outbound[AppIds.D2C_GATEWAY];
      publish(publisher, WARM_UP);
      await(loop, WARM_UP);
      long sent = WARM_UP;
      // Under machine load the JIT's compiler threads fall behind, so compilations and
      // deoptimisations of the desk's paths can land in the measured windows instead of the fixed
      // warm-up. Keep warming up, a window at a time, until a whole window passes with no new JIT
      // compilation time, up to EXTRA_WARM_UP windows (DR-INT-06). The measured windows and their
      // bounds are unchanged.
      final java.lang.management.CompilationMXBean jit = ManagementFactory.getCompilationMXBean();
      int extraWarmUp = 0;
      if (jit != null && jit.isCompilationTimeMonitoringSupported()) {
        long compiled = jit.getTotalCompilationTime();
        while (extraWarmUp < EXTRA_WARM_UP) {
          publish(publisher, WINDOW);
          sent += WINDOW;
          await(loop, sent);
          extraWarmUp++;
          final long now = jit.getTotalCompilationTime();
          if (now == compiled) {
            break;
          }
          compiled = now;
        }
      }
      final long[] bytes = new long[WINDOWS];
      for (int w = 0; w < WINDOWS; w++) {
        final long before = threads.getThreadAllocatedBytes(runner.threadId());
        publish(publisher, WINDOW);
        sent += WINDOW;
        await(loop, sent);
        bytes[w] = threads.getThreadAllocatedBytes(runner.threadId()) - before;
      }
      runner.close();
      draining.set(false);
      drainer.join();
      assertNull(runner.failure());
      final var desk = factory.last().desk();
      final long hedgeFills = factory.last().hedgeFills();
      final String report =
          "bytes per window " + java.util.Arrays.toString(bytes) + " after " + extraWarmUp
              + " extra warm-up windows; quotes " + desk.quoting().quotesSent()
              + ", fills " + desk.quoting().fills() + ", hedge parents " + factory.last().hedgesRouted()
              + ", curve rebuilds " + factory.last().pricing().rebuilds()
              + ", hedger sent " + factory.last().desk().hedger(io.ratesalgo.api.CurrencyCode.USD).parentsSent()
              + " refused " + factory.last().desk().hedger(io.ratesalgo.api.CurrencyCode.USD).parentsRefused()
              + ", kill switch " + desk.killSwitch().state() + ", pre-trade rejects " + desk.risk().preTradeRejects()
              + ", passes " + desk.quoting().passes() + ", hedge fills " + factory.last().hedgeFills();
      System.out.println("wired allocation: " + report);
      assertTrue(desk.quoting().quotesSent() > WINDOW / 10, "the desk quoted: " + report);
      assertTrue(factory.last().hedgesRouted() > 0, "the hedger routed parents: " + report);
      assertTrue(hedgeFills > 0, "hedge fills came back: " + report);
      long fewest = Long.MAX_VALUE;
      for (int w = 1; w < WINDOWS; w++) {
        assertTrue(bytes[w] <= LATER_WINDOW_BOUND, report);
        fewest = Math.min(fewest, bytes[w]);
      }
      assertEquals(0L, fewest, "no window after the first was allocation-free: " + report);
    }
  }

  private void publish(final InboundPublisher p, final int count) {
    for (int i = 0; i < count; i++) {
      final int kind = (int) (rfqId + bookSeq) & 63;
      final int length;
      if (kind == 0) {
        final UsdUniverse.SwapSpec s = universe.swaps().get((int) (bookSeq % universe.swaps().size()));
        curve.wrapAndApplyHeader(buffer, 0, header).curveId(1).kind(CurveInputKind.ParSwap)
            .instrumentId(s.instrumentId()).tenor("    ").timestampNanos(System.currentTimeMillis() * 1_000_000L);
        curve.value().mantissa(s.initialMid() + ((bookSeq / 7) % 5 - 2) * 100_000L);
        length = MessageHeaderEncoder.ENCODED_LENGTH + curve.encodedLength();
        bookSeq++;
      } else if (kind == 1) {
        heartbeat.wrapAndApplyHeader(buffer, 0, header).sourceId(AppIds.CURVE_HEARTBEAT_SOURCE)
            .timestampNanos(System.currentTimeMillis() * 1_000_000L);
        length = MessageHeaderEncoder.ENCODED_LENGTH + heartbeat.encodedLength();
        bookSeq++;
      } else if ((kind & 1) == 0) {
        final UsdUniverse.BondSpec b = universe.bonds().get((int) (bookSeq % universe.bonds().size()));
        final boolean bid = ((bookSeq / universe.bonds().size()) & 1) == 0;
        book.wrapAndApplyHeader(buffer, 0, header).venueId(AppIds.CLOB_VENUE).instrumentId(b.instrumentId())
            .venueSeqNo(++venueSeq).side(bid ? Side.Buy : Side.Sell).action(BookAction.Change).level((short) 1)
            .quantity(5_000_000_000L + (bookSeq & 7) * 100_000_000L).orderCount(1)
            .timestampNanos(System.currentTimeMillis() * 1_000_000L);
        book.price().mantissa(b.initialMid() + (bid ? -b.tick() : b.tick()));
        bookSeq++;
        length = MessageHeaderEncoder.ENCODED_LENGTH + book.encodedLength();
      } else if (pendingHead == pendingTail) {
        venueSide.read(onQuote, 1024);
        final int n = universe.instrumentIds().length;
        rfqId++;
        rfq.wrapAndApplyHeader(buffer, 0, header).venueId(AppIds.D2C_VENUE).rfqId(rfqId).clientId(2001)
            .instrumentId(universe.instrumentIds()[(int) (rfqId % n)]).side((rfqId & 2) == 0 ? Side.Buy : Side.Sell)
            .quantity(1_000_000_000L).dealerCount((short) 4).receivedNanos(0L).expiryNanos(0L);
        rfq.putCurrency((byte) 'U', (byte) 'S', (byte) 'D');
        length = MessageHeaderEncoder.ENCODED_LENGTH + rfq.encodedLength();
      } else {
        // the venue's outcome for a quote the loop sent: one in eight a fill
        final int k = pendingHead++ & 0xFFFF;
        final long quoteId = pendingQuote[k];
        response.wrapAndApplyHeader(buffer, 0, header).venueId(AppIds.D2C_VENUE).rfqId(pendingRfq[k]).quoteId(quoteId)
            .status((quoteId & 7) == 0 ? QuoteStatus.Done : QuoteStatus.TradedAway).tradedQuantity(1_000_000_000L)
            .timestampNanos(0L);
        response.tradedPrice().mantissa(midOf(pendingInstrument[k]));
        response.coverPrice().mantissa(0L);
        length = MessageHeaderEncoder.ENCODED_LENGTH + response.encodedLength();
      }
      while (!p.offer(buffer, 0, length)) {
        Thread.onSpinWait();
      }
    }
  }

  /** The CLOB as the drainer thread plays it: fills every child in full, acknowledges cancels. */
  private static final class ClobVenue implements org.agrona.concurrent.MessageHandler {
    private final InboundPublisher publisher;
    private final UnsafeBuffer out = new UnsafeBuffer(new byte[256]);
    private final MessageHeaderEncoder header = new MessageHeaderEncoder();
    private final io.ratesalgo.messages.sbe.MessageHeaderDecoder headerDecoder =
        new io.ratesalgo.messages.sbe.MessageHeaderDecoder();
    private final io.ratesalgo.messages.sbe.NewOrderDecoder order = new io.ratesalgo.messages.sbe.NewOrderDecoder();
    private final io.ratesalgo.messages.sbe.OrderCancelDecoder cancel =
        new io.ratesalgo.messages.sbe.OrderCancelDecoder();
    private final io.ratesalgo.messages.sbe.ExecutionReportEncoder report =
        new io.ratesalgo.messages.sbe.ExecutionReportEncoder();
    private long execId;

    ClobVenue(final InboundPublisher publisher) {
      this.publisher = publisher;
    }

    @Override
    public void onMessage(final int t, final org.agrona.MutableDirectBuffer b, final int i, final int l) {
      if (t == io.ratesalgo.messages.sbe.NewOrderDecoder.TEMPLATE_ID) {
        order.wrapAndApplyHeader(b, i, headerDecoder);
        send(order.clOrdId(), order.instrumentId(), order.side(), io.ratesalgo.messages.sbe.OrdStatus.Filled,
            order.price().mantissa(), order.quantity(), order.quantity());
      } else if (t == io.ratesalgo.messages.sbe.OrderCancelDecoder.TEMPLATE_ID) {
        cancel.wrapAndApplyHeader(b, i, headerDecoder);
        send(cancel.clOrdId(), cancel.instrumentId(), cancel.side(), io.ratesalgo.messages.sbe.OrdStatus.Cancelled,
            0L, 0L, 0L);
      }
    }

    private void send(final long clOrdId, final int instrumentId, final Side side,
        final io.ratesalgo.messages.sbe.OrdStatus status, final long price, final long last, final long cum) {
      report.wrapAndApplyHeader(out, 0, header).venueId(AppIds.CLOB_VENUE).clOrdId(clOrdId).venueOrderId(clOrdId)
          .execId(++execId).instrumentId(instrumentId).side(side).ordStatus(status).lastQuantity(last)
          .leavesQuantity(0L).cumQuantity(cum).transactNanos(0L);
      report.lastPrice().mantissa(price);
      while (!publisher.offer(out, 0, MessageHeaderEncoder.ENCODED_LENGTH + report.encodedLength())) {
        Thread.onSpinWait();
      }
    }
  }

  private long midOf(final int instrumentId) {
    final UsdUniverse.SwapSpec s = universe.swap(instrumentId);
    return s != null ? s.initialMid() : universe.bond(instrumentId).initialMid();
  }

  private static void await(final EventLoop loop, final long count) throws InterruptedException {
    final long deadline = System.currentTimeMillis() + 300_000L;
    while (loop.counters().get(LoopCounters.INBOUND) < count) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("loop processed " + loop.counters().get(LoopCounters.INBOUND) + " of " + count);
      }
      Thread.sleep(1);
    }
  }
}
