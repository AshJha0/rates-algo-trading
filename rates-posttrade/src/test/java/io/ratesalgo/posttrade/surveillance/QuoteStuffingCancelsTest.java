package io.ratesalgo.posttrade.surveillance;

import static io.ratesalgo.posttrade.Fixtures.NINE;
import static io.ratesalgo.posttrade.Fixtures.px;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.Replayer;
import io.ratesalgo.messages.sbe.CancelReason;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.OrderCancelEncoder;
import io.ratesalgo.messages.sbe.OrderReplaceEncoder;
import io.ratesalgo.messages.sbe.OrderWithdrawEncoder;
import io.ratesalgo.messages.sbe.QuoteCancelEncoder;
import io.ratesalgo.messages.sbe.QuoteWithdrawEncoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.posttrade.chaos.JournalScript;
import io.ratesalgo.posttrade.chaos.SyntheticDay;
import java.nio.file.Path;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PNL_AUDIT.md item 18: cancels, replaces and withdrawals count toward quote stuffing, because a
 * venue's order-to-trade policy counts them, and the detector keeps the day's totals.
 */
class QuoteStuffingCancelsTest {

  @TempDir Path dir;

  private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();

  @Test
  void cancelBurstsRaiseAnAlertAndEveryCancelKindIsCounted() {
    final Path j = dir.resolve("c.raj");
    try (JournalScript s = new JournalScript(j, 1 << 20, SyntheticDay.MIDNIGHT)) {
      // five orders and fifteen cancels in one second: twenty messages, under a threshold of 20
      for (int i = 0; i < 5; i++) {
        s.newOrder(NINE + i * 1_000_000L, 1, i, 1001, Side.Buy, px("99"), 1);
      }
      for (int i = 0; i < 15; i++) {
        s.raw(NINE + 10_000_000L + i * 1_000_000L, OrderCancelEncoder.TEMPLATE_ID, orderCancel(1001, i));
      }
      // one more cancel tips it over: only cancels, which the old detector ignored
      s.raw(NINE + 50_000_000L, OrderCancelEncoder.TEMPLATE_ID, orderCancel(1001, 99));
      // one of each other kind on another instrument, well apart in time
      final long later = NINE + 60_000_000_000L;
      s.raw(later, OrderReplaceEncoder.TEMPLATE_ID, orderReplace(2002));
      s.raw(later + 1, QuoteCancelEncoder.TEMPLATE_ID, quoteCancel(2002));
      s.raw(later + 2, QuoteWithdrawEncoder.TEMPLATE_ID, quoteWithdraw(2002));
      s.raw(later + 3, OrderWithdrawEncoder.TEMPLATE_ID, orderWithdraw(2002));
      s.quote(later + 4, 2, 1, 1, 2002, px("4.25"), px("4.26"), 1, later + 10);
    }
    final QuoteStuffingDetector d = new QuoteStuffingDetector(1_000_000_000L, 20);
    new Replayer(new ManualClock(0)).replay(j, d);
    assertEquals(1, d.alerts().size(), d.alerts()::toString);
    assertEquals(1001, d.alerts().get(0).instrumentId());
    assertTrue(d.alerts().get(0).detail().startsWith("21 quotes, orders and cancels"), d.alerts().get(0).detail());
    assertEquals(21L, d.messages(1001));
    assertEquals(16L, d.cancels(1001));
    assertEquals(5L, d.messages(2002));
    assertEquals(4L, d.cancels(2002));
    assertEquals(0L, d.messages(3003));
    assertEquals(0L, d.cancels(3003));
    assertEquals(List.of(1001, 2002), d.instruments());
  }

  private byte[] bytes(final int length) {
    final byte[] out = new byte[MessageHeaderEncoder.ENCODED_LENGTH + length];
    buffer.getBytes(0, out);
    return out;
  }

  private byte[] orderCancel(final int instrument, final long clOrdId) {
    final OrderCancelEncoder e = new OrderCancelEncoder().wrapAndApplyHeader(buffer, 0, header);
    e.venueId(1).clOrdId(1_000 + clOrdId).origClOrdId(clOrdId).instrumentId(instrument).side(Side.Buy)
        .quantity(1).sentNanos(NINE);
    return bytes(e.encodedLength());
  }

  private byte[] orderReplace(final int instrument) {
    final OrderReplaceEncoder e = new OrderReplaceEncoder().wrapAndApplyHeader(buffer, 0, header);
    e.venueId(1).clOrdId(2).origClOrdId(1).instrumentId(instrument).side(Side.Sell).quantity(1).sentNanos(NINE);
    e.price().mantissa(px("99"));
    return bytes(e.encodedLength());
  }

  private byte[] quoteCancel(final int instrument) {
    final QuoteCancelEncoder e = new QuoteCancelEncoder().wrapAndApplyHeader(buffer, 0, header);
    e.venueId(2).rfqId(1).quoteId(1).instrumentId(instrument).sentNanos(NINE);
    return bytes(e.encodedLength());
  }

  private byte[] quoteWithdraw(final int instrument) {
    final QuoteWithdrawEncoder e = new QuoteWithdrawEncoder().wrapAndApplyHeader(buffer, 0, header);
    e.venueId(2).rfqId(1).quoteId(1).instrumentId(instrument).reason(CancelReason.Expired).sentNanos(NINE);
    return bytes(e.encodedLength());
  }

  private byte[] orderWithdraw(final int instrument) {
    final OrderWithdrawEncoder e = new OrderWithdrawEncoder().wrapAndApplyHeader(buffer, 0, header);
    e.venueId(1).clOrdId(3).origClOrdId(2).instrumentId(instrument).side(Side.Sell).reason(CancelReason.Expired)
        .sentNanos(NINE);
    return bytes(e.encodedLength());
  }
}
