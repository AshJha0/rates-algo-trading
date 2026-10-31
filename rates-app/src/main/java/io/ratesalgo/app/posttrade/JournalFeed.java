package io.ratesalgo.app.posttrade;

import io.ratesalgo.app.loop.AppIds;
import io.ratesalgo.app.pricing.AnalyticsPricing;
import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.journal.FrameHandler;
import io.ratesalgo.journal.SegmentedJournalReader;
import io.ratesalgo.messages.sbe.ExecutionReportDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.posttrade.position.InstrumentCatalogue;
import io.ratesalgo.posttrade.position.InstrumentReference;
import io.ratesalgo.posttrade.position.PositionKeeper;
import io.ratesalgo.posttrade.trade.BookAssignment;
import io.ratesalgo.posttrade.trade.FixedBookMapper;
import java.math.BigDecimal;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Feeds a loop's journals to P6's {@link PositionKeeper} (API_SPEC.md Section 18.6): fills,
 * RFQ outcomes and venue busts from the inbound journal, the orders and quotes we sent from the
 * outbound journal, merged into one stream in time order (inbound before outbound at equal
 * stamps, journal order within each). The keeper's sequence numbers are positions in that merged
 * stream.
 *
 * <p>The D2C venue confirms an RFQ trade twice, with a QuoteResponse (booked as the RFQ trade
 * {@code venue:R:quoteId}) and with an ExecutionReport carrying the quote id; the second is a
 * confirmation of the first and is not fed to the keeper, or every RFQ trade would be booked
 * twice (DECISIONS.md DR-R2W-06). Off the hot path: reads whole journals into memory.
 */
public final class JournalFeed {

  private static final class Frame {
    private final long timestamp;
    private final int direction;
    private final long sequence;
    private final int templateId;
    private final int version;
    private final byte[] payload;

    Frame(final long timestamp, final int direction, final long sequence, final int templateId, final int version,
        final byte[] payload) {
      this.timestamp = timestamp;
      this.direction = direction;
      this.sequence = sequence;
      this.templateId = templateId;
      this.version = version;
      this.payload = payload;
    }

    long timestamp() {
      return timestamp;
    }

    int direction() {
      return direction;
    }

    long sequence() {
      return sequence;
    }

    int templateId() {
      return templateId;
    }

    int version() {
      return version;
    }

    byte[] payload() {
      return payload;
    }
  }

  private JournalFeed() {}

  /** A book mapper: RFQ trades to book 1, hedges and orders to book 2, account 1, as the desk runs. */
  public static FixedBookMapper books(final UsdUniverse universe) {
    final FixedBookMapper m = new FixedBookMapper(new BookAssignment(1, 1, "RALGO-RFQ"));
    for (final UsdUniverse.BondSpec b : universe.bonds()) {
      m.with(b.instrumentId(), new BookAssignment(2, 1, "RALGO-HEDGE"));
    }
    return m;
  }

  /** The post-trade catalogue from the pricing binding's directory: bonds per 100, swaps per annuity. */
  public static InstrumentCatalogue catalogue(final AnalyticsPricing pricing) {
    final InstrumentCatalogue c = new InstrumentCatalogue();
    for (final UsdUniverse.SwapSpec s : pricing.universe().swaps()) {
      final double annuity = pricing.directory().byId(s.instrumentId()).quoteValue() * 100.0;
      c.add(new InstrumentReference(s.instrumentId(), s.securityId(), "USD", "PERC", BigDecimal.valueOf(annuity)));
    }
    for (final UsdUniverse.BondSpec b : pricing.universe().bonds()) {
      c.add(new InstrumentReference(b.instrumentId(), b.securityId(), "USD", "PERC", BigDecimal.ONE));
    }
    return c;
  }

  /** Replays the journals {@code in} and {@code out} in {@code dir} into a new keeper. */
  public static PositionKeeper keeper(
      final Path dir, final String in, final String out, final FixedBookMapper books, final InstrumentCatalogue catalogue) {
    final PositionKeeper keeper = new PositionKeeper(books, catalogue);
    feed(dir, in, out, keeper);
    return keeper;
  }

  /** Feeds the merged journals to {@code handler}; returns the frames fed. */
  public static long feed(final Path dir, final String in, final String out, final FrameHandler handler) {
    final List<Frame> frames = new ArrayList<>();
    read(dir, in, 0, frames);
    read(dir, out, 1, frames);
    frames.sort(Comparator.comparingLong(Frame::timestamp).thenComparingInt(Frame::direction).thenComparingLong(Frame::sequence));
    final UnsafeBuffer view = new UnsafeBuffer(new byte[0]);
    long seq = 0;
    for (final Frame f : frames) {
      if (isRfqConfirmation(f)) {
        continue;
      }
      view.wrap(f.payload());
      handler.onFrame(++seq, f.timestamp(), f.templateId(), f.version(), view, 0, f.payload().length);
    }
    return seq;
  }

  private static boolean isRfqConfirmation(final Frame f) {
    if (f.templateId() != ExecutionReportDecoder.TEMPLATE_ID || f.direction() != 0) {
      return false;
    }
    final int venueId =
        new UnsafeBuffer(f.payload()).getInt(MessageHeaderDecoder.ENCODED_LENGTH + ExecutionReportDecoder.venueIdEncodingOffset(),
            ByteOrder.LITTLE_ENDIAN);
    return venueId == AppIds.D2C_VENUE;
  }

  private static void read(final Path dir, final String name, final int direction, final List<Frame> into) {
    try (SegmentedJournalReader reader = SegmentedJournalReader.open(dir, name)) {
      final FrameHandler collect =
          (sequence, timestampNanos, templateId, schemaVersion, buffer, offset, length) -> {
            if (MessageHeaderDecoder.SCHEMA_ID == (buffer.getShort(offset + 4, ByteOrder.LITTLE_ENDIAN) & 0xFFFF)) {
              final byte[] copy = new byte[length];
              buffer.getBytes(offset, copy);
              into.add(new Frame(timestampNanos, direction, sequence, templateId, schemaVersion, copy));
            }
          };
      while (reader.next(collect)) {
        // every frame
      }
    }
  }
}
