package io.ratesalgo.posttrade.chaos;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.Replayer;
import io.ratesalgo.messages.sbe.AmendReason;
import io.ratesalgo.messages.sbe.BustReason;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TradeSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every JournalScript call appends one frame, returns its sequence and stamps its arrival time. */
class JournalScriptTest {

  @TempDir Path dir;

  @Test
  void eachCallReturnsTheSequenceOfItsFrameAndStampsItsArrival() {
    final Path j = dir.resolve("s.raj");
    final long t0 = 1_000_000_000L;
    final List<Long> returned = new ArrayList<>();
    try (JournalScript s = new JournalScript(j, 1 << 16, t0)) {
      assertEquals(1L, s.nextSequence());
      returned.add(s.newOrder(t0 + 10, 1, 7, 1001, Side.Buy, 100, 300));
      returned.add(s.executionReport(t0 + 20, 1, 7, 507, 70, 1001, Side.Buy, OrdStatus.New, 0, 0, 300, 0, t0 + 20));
      returned.add(s.quoteRequest(t0 + 30, 2, 61, 301, 2001, Side.Buy, 1_000, "USD"));
      returned.add(s.quote(t0 + 40, 2, 61, 81, 2001, 1, 2, 1_000, t0 + 100));
      returned.add(s.quoteResponse(t0 + 50, 2, 61, 81, QuoteStatus.Done, 2, 1_000, t0 + 50));
      returned.add(s.bust(t0 + 60, 1, TradeSource.Execution, 70, 507, 1001, BustReason.VenueError));
      returned.add(s.venueBust(t0 + 70, 1, 7, 70, 1001, Side.Buy, 100, 300));
      returned.add(s.amend(t0 + 80, 2, TradeSource.Rfq, 81, 3, 900, 5, AmendReason.PriceCorrection));
      returned.add(s.raw(t0 + 70, 1, new byte[16])); // earlier than the clock: stamped at the clock
      s.arriveAt(t0 + 200);
      s.arriveAt(t0 + 150); // the clock never goes back
      returned.add(s.raw(t0, 1, new byte[16]));
      assertEquals(11L, s.nextSequence());
    }
    assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), returned);
    final List<Long> stamps = new ArrayList<>();
    final long[] expiry = new long[1];
    final MessageHeaderDecoder h = new MessageHeaderDecoder();
    final QuoteRequestDecoder qr = new QuoteRequestDecoder();
    new Replayer(new ManualClock(0)).replay(j, (seq, at, t, v, b, o, l) -> {
      stamps.add(at);
      if (t == QuoteRequestDecoder.TEMPLATE_ID) {
        h.wrap(b, o);
        qr.wrapAndApplyHeader(b, o, h);
        expiry[0] = qr.expiryNanos() - qr.receivedNanos();
      }
    });
    assertEquals(List.of(t0 + 10, t0 + 20, t0 + 30, t0 + 40, t0 + 50, t0 + 60, t0 + 70, t0 + 80, t0 + 80, t0 + 200),
        stamps);
    assertEquals(30_000_000_000L, expiry[0], "an RFQ is live for thirty seconds");
  }
}
