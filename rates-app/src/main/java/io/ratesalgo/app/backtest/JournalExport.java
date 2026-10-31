package io.ratesalgo.app.backtest;

import io.ratesalgo.app.loop.AppIds;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.FrameHandler;
import io.ratesalgo.journal.JournalWriter;
import io.ratesalgo.journal.SegmentedJournalReader;
import io.ratesalgo.messages.sbe.BookAction;
import io.ratesalgo.messages.sbe.BookUpdateDecoder;
import io.ratesalgo.messages.sbe.BookUpdateEncoder;
import io.ratesalgo.messages.sbe.CurveInputDecoder;
import io.ratesalgo.messages.sbe.HeartbeatDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.Side;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Turns a loop's inbound journal into the single RAJ1 file the P5 back-tester reads (API_SPEC.md
 * Section 18.7). The back-tester settles RFQs itself and keeps a top of book from level-0
 * BookUpdates, so the export keeps what it consumes and reshapes the book:
 *
 * <ul>
 *   <li>QuoteRequest, CurveInput and the curve feed's Heartbeat are copied unchanged.
 *   <li>The venue's depth updates are replayed into a book, and every change of its top becomes
 *       three level-0 frames: Clear, Add bid, Add ask, with the venue sequence renumbered from 1
 *       so the desk's feed-gap check passes.
 *   <li>QuoteResponse, ExecutionReport, TradeBust, other heartbeats and control frames are left
 *       out: the back-tester produces its own outcomes and fills.
 * </ul>
 *
 * Every frame keeps its journal timestamp. Off the hot path.
 */
public final class JournalExport {

  private final ManualClock clock = new ManualClock(0L);
  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final BookUpdateDecoder update = new BookUpdateDecoder();
  private final BookUpdateEncoder top = new BookUpdateEncoder();
  private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
  private final UnsafeBuffer out = new UnsafeBuffer(new byte[128]);
  private final Map<Integer, TreeMap<Long, Long>> bids = new HashMap<>();
  private final Map<Integer, TreeMap<Long, Long>> asks = new HashMap<>();
  private final Map<Integer, long[]> lastTop = new HashMap<>();
  private JournalWriter writer;
  private long venueSeq;
  private long copied;
  private long topFrames;

  private JournalExport() {}

  /**
   * Exports journal {@code name} in {@code dir} to {@code target}.
   *
   * @return frames written
   */
  public static long export(final Path dir, final String name, final Path target, final int capacityBytes) {
    final JournalExport e = new JournalExport();
    try (JournalWriter w = JournalWriter.create(target, capacityBytes, e.clock);
        SegmentedJournalReader reader = SegmentedJournalReader.open(dir, name)) {
      e.writer = w;
      final FrameHandler handler = e::onFrame;
      while (reader.next(handler)) {
        // every frame
      }
    }
    return e.copied + e.topFrames;
  }

  private void onFrame(
      final long sequence, final long timestampNanos, final int templateId, final int schemaVersion,
      final DirectBuffer buffer, final int offset, final int length) {
    header.wrap(buffer, offset);
    if (header.schemaId() != MessageHeaderDecoder.SCHEMA_ID) {
      return;
    }
    clock.setEpochNanos(Math.max(clock.epochNanos(), timestampNanos));
    switch (templateId) {
      case QuoteRequestDecoder.TEMPLATE_ID, CurveInputDecoder.TEMPLATE_ID -> copy(templateId, schemaVersion, buffer, offset, length);
      case HeartbeatDecoder.TEMPLATE_ID -> {
        if (buffer.getInt(offset + MessageHeaderDecoder.ENCODED_LENGTH, java.nio.ByteOrder.LITTLE_ENDIAN)
            == AppIds.CURVE_HEARTBEAT_SOURCE) {
          copy(templateId, schemaVersion, buffer, offset, length);
        }
      }
      case BookUpdateDecoder.TEMPLATE_ID -> {
        update.wrap(buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH, header.blockLength(), header.version());
        onBookUpdate();
      }
      default -> {
        // the back-tester produces its own outcomes and fills
      }
    }
  }

  private void copy(final int templateId, final int version, final DirectBuffer buffer, final int offset, final int length) {
    writer.append(templateId, version, buffer, offset, length);
    copied++;
  }

  private TreeMap<Long, Long> side(final int instrumentId, final boolean bid) {
    return (bid ? bids : asks)
        .computeIfAbsent(instrumentId, k -> bid ? new TreeMap<>(java.util.Comparator.reverseOrder()) : new TreeMap<>());
  }

  private void onBookUpdate() {
    final int id = update.instrumentId();
    final BookAction action = update.action();
    if (action == BookAction.Clear) {
      side(id, true).clear();
      side(id, false).clear();
    } else {
      final TreeMap<Long, Long> levels = side(id, update.side() == Side.Buy);
      if (action == BookAction.Delete || update.quantity() <= 0) {
        levels.remove(update.price().mantissa());
      } else {
        levels.put(update.price().mantissa(), update.quantity());
      }
    }
    final TreeMap<Long, Long> b = side(id, true);
    final TreeMap<Long, Long> a = side(id, false);
    if (b.isEmpty() || a.isEmpty()) {
      return;
    }
    final long[] now = {b.firstKey(), b.firstEntry().getValue(), a.firstKey(), a.firstEntry().getValue()};
    final long[] before = lastTop.get(id);
    if (before != null && java.util.Arrays.equals(before, now)) {
      return;
    }
    lastTop.put(id, now);
    writeTop(id, update.venueId(), BookAction.Clear, Side.Buy, 0L, 0L);
    writeTop(id, update.venueId(), BookAction.Add, Side.Buy, now[0], now[1]);
    writeTop(id, update.venueId(), BookAction.Add, Side.Sell, now[2], now[3]);
  }

  private void writeTop(final int id, final int venueId, final BookAction action, final Side side, final long price,
      final long qty) {
    top.wrapAndApplyHeader(out, 0, headerEncoder)
        .venueId(venueId)
        .instrumentId(id)
        .venueSeqNo(++venueSeq)
        .side(side)
        .action(action)
        .level((short) 0)
        .quantity(qty)
        .orderCount(1)
        .timestampNanos(clock.epochNanos());
    top.price().mantissa(price);
    writer.append(BookUpdateEncoder.TEMPLATE_ID, BookUpdateEncoder.SCHEMA_VERSION, out, 0,
        MessageHeaderEncoder.ENCODED_LENGTH + top.encodedLength());
    topFrames++;
  }
}
