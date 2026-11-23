package io.ratesalgo.posttrade.chaos;

import static io.ratesalgo.posttrade.chaos.SyntheticDay.CUT;
import static io.ratesalgo.posttrade.chaos.SyntheticDay.MIDNIGHT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.Replayer;
import io.ratesalgo.messages.sbe.ExecutionReportDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.NewOrderDecoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.PostTradeBustDecoder;
import io.ratesalgo.messages.sbe.QuoteDecoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteResponseDecoder;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TradeSource;
import io.ratesalgo.posttrade.recon.ReconItem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The synthetic day read back frame by frame and checked against its specification (the class
 * comment and API_SPEC.md Section 17.8): the order and RFQ schedule, the economics of every fill,
 * each injected fault where and as often as asked, the drop copy, and the counts in the result.
 */
class SyntheticDayTest {

  private static final long SECOND = 1_000_000_000L;
  private static final long MINUTE = 60 * SECOND;
  private static final long OPEN = MIDNIGHT + 9 * 3_600 * SECOND;
  private static final long SPAN = 7 * 3_600 * SECOND;

  @TempDir Path dir;

  /** One frame, decoded into what the checks need. */
  private record Frame(long seq, long at, String kind, Map<String, Object> f) {
    long l(final String k) {
      return ((Number) f.get(k)).longValue();
    }
  }

  @Test
  void theDefaultDayFollowsItsSpecification() {
    final SyntheticDay.Params p = SyntheticDay.Params.DEFAULT;
    final SyntheticDay.Result r = SyntheticDay.generate(dir.resolve("day.raj"), 20261030L, p);
    final List<Frame> frames = read(r.journal());
    assertEquals(SyntheticDay.DATE, r.date());
    assertEquals(frames.size(), r.frames());
    long lastAt = Long.MIN_VALUE;
    long cutSeq = 0;
    for (final Frame fr : frames) {
      assertTrue(fr.at() >= lastAt, "frames arrive in time order");
      lastAt = fr.at();
      if (fr.at() <= CUT) {
        cutSeq = fr.seq();
      }
    }
    assertEquals(cutSeq, r.cutSequence(), "last sequence at or before the cut");

    // orders: schedule, instruments, acks and fills that add up
    final Map<Long, Frame> orders = new TreeMap<>();
    for (final Frame fr : frames) {
      if (fr.kind().equals("NO")) {
        orders.put(fr.l("clOrdId"), fr);
      }
    }
    assertEquals(p.orders(), orders.size());
    final Map<String, Frame> fillByKey = new HashMap<>();
    final Map<String, List<Long>> arrivals = new HashMap<>();
    for (int i = 0; i < p.orders(); i++) {
      final Frame o = orders.get(1_000L + i);
      final long t = OPEN + SPAN * i / p.orders();
      assertEquals(t, o.at(), "order " + i);
      assertEquals(t, o.l("sent"));
      assertEquals(i % 2 == 0 ? SyntheticDay.BOND_A : SyntheticDay.BOND_B, o.l("instrument"), "order " + i);
      assertEquals(SyntheticDay.CLOB_VENUE, o.l("venue"));
      final long px = o.l("price") - 99_500_000_000L;
      assertTrue(px >= 0 && px % 31_250_000L == 0 && px / 31_250_000L < 64, "price grid " + px);
      final long qty = o.l("qty");
      assertTrue(qty % 100_000_000L == 0 && qty >= 100_000_000L && qty <= 1_000_000_000L, "qty " + qty);
      long filled = 0;
      int acks = 0;
      int fills = 0;
      final List<Frame> own = new ArrayList<>();
      for (final Frame fr : frames) {
        if (!fr.kind().equals("ER") || fr.l("clOrdId") != 1_000L + i) {
          continue;
        }
        assertEquals(500_000L + i, fr.l("venueOrderId"));
        assertEquals(o.f().get("side"), fr.f().get("side"));
        assertEquals(o.l("instrument"), fr.l("instrument"));
        if (fr.f().get("status") == OrdStatus.New) {
          acks++;
          assertEquals(t + 1_000_000, fr.l("transact"));
          assertEquals(qty, fr.l("leaves"));
          assertEquals(0L, fr.l("cum"));
          continue;
        }
        final String key = "1:E:" + fr.l("execId");
        arrivals.computeIfAbsent(key, k -> new ArrayList<>()).add(fr.at());
        if (fillByKey.putIfAbsent(key, fr) == null) {
          own.add(fr); // a second delivery is a duplicate, checked below
        }
      }
      // fills in transaction order (a late one may arrive after the next)
      own.sort((a, b) -> Long.compare(a.l("transact"), b.l("transact")));
      for (final Frame fr : own) {
        fills++;
        filled += fr.l("lastQty");
        assertEquals(o.l("price"), fr.l("lastPrice"));
        assertEquals(t + (fills + 1) * 1_000_000L, fr.l("transact"), "fill " + fills + " of order " + i);
        assertEquals(qty - filled, fr.l("leaves"));
        assertEquals(filled, fr.l("cum"));
        assertEquals(filled == qty ? OrdStatus.Filled : OrdStatus.PartiallyFilled, fr.f().get("status"));
      }
      assertEquals(1, acks);
      assertTrue(fills == 1 || fills == 2, "one fill or two halves");
      if (fills == 2) {
        assertEquals(qty / 2, own.get(0).l("lastQty"), "the first half rounds down");
      }
      assertEquals(qty, filled, "order " + i + " fills completely");
    }

    // RFQs: request, quote 50 ms later, outcome 2 s later at our side of the quote
    for (int j = 0; j < p.rfqs(); j++) {
      final long t = OPEN + 7 * MINUTE + SPAN * j / p.rfqs();
      final Frame req = only(frames, "QR", "rfqId", 9_000L + j);
      final Frame quote = only(frames, "Q", "rfqId", 9_000L + j);
      assertEquals(t, req.at(), "rfq " + j);
      assertEquals(t + 30 * SECOND, req.l("expiry"));
      assertEquals(SyntheticDay.SWAP, req.l("instrument"));
      assertTrue(req.l("client") >= 300 && req.l("client") < 320);
      final long qty = req.l("qty");
      assertTrue(qty % 1_000_000_000L == 0 && qty >= 5_000_000_000L && qty <= 50_000_000_000L, "rfq qty " + qty);
      assertEquals(t + 50_000_000, quote.at());
      assertEquals(70_000L + j, quote.l("quoteId"));
      assertEquals(1_000_000L, quote.l("ask") - quote.l("bid"));
      final long mid = quote.l("bid") + 500_000;
      assertTrue(mid >= 4_200_000_000L && mid < 4_300_000_000L && (mid - 4_200_000_000L) % 1_000_000L == 0, "mid " + mid);
      assertEquals(qty, quote.l("bidQty"));
      assertEquals(t + 10 * SECOND, quote.l("validUntil"));
      boolean seen = false;
      for (final Frame fr : frames) {
        if (!fr.kind().equals("QRESP") || fr.l("quoteId") != 70_000L + j) {
          continue;
        }
        assertEquals(t + 2 * SECOND, fr.l("when"));
        if (fr.f().get("status") == QuoteStatus.Done) {
          final String key = "2:R:" + (70_000L + j);
          arrivals.computeIfAbsent(key, k -> new ArrayList<>()).add(fr.at());
          fillByKey.putIfAbsent(key, fr);
          assertEquals(req.f().get("side") == Side.Buy ? quote.l("ask") : quote.l("bid"), fr.l("price"));
          assertEquals(qty, fr.l("qty"));
        } else {
          assertEquals(QuoteStatus.TradedAway, fr.f().get("status"));
          assertEquals(0L, fr.l("qty"));
          assertTrue(!seen, "one outcome");
        }
        seen = true;
      }
      assertTrue(seen, "rfq " + j + " has an outcome");
    }
    assertEquals(fillByKey.size(), r.fills());

    // faults
    int dups = 0;
    int lateIn = 0;
    final List<Long> lateOut = new ArrayList<>();
    final Map<String, Long> transactOf = new HashMap<>();
    for (final Map.Entry<String, Frame> e : fillByKey.entrySet()) {
      final Frame fr = e.getValue();
      final long tx = fr.kind().equals("ER") ? fr.l("transact") : fr.l("when");
      transactOf.put(e.getKey(), tx);
      final List<Long> at = arrivals.get(e.getKey());
      if (at.size() == 2) {
        dups++;
        assertEquals(at.get(0) + 10 * SECOND, at.get(1), "a duplicate comes ten seconds later");
      } else {
        assertEquals(1, at.size());
      }
      final long delay = at.get(0) - tx;
      if (delay == 30 * MINUTE) {
        lateIn++;
      } else if (at.get(0) >= CUT + 30 * MINUTE) {
        lateOut.add(at.get(0));
      } else {
        assertTrue(delay == 0 || delay == 10 * MINUTE, "on time, or the fill whose bust came first: " + delay);
      }
    }
    assertEquals(p.duplicates(), dups);
    assertEquals(p.lateWithinDay(), lateIn);
    lateOut.sort(Long::compare);
    assertEquals(p.lateAfterCut(), lateOut.size());
    for (int k = 0; k < lateOut.size(); k++) {
      assertEquals(CUT + 30 * MINUTE + k * SECOND, lateOut.get(k));
    }
    final Set<String> busted = new LinkedHashSet<>();
    int before = 0;
    int after = 0;
    int bustFirst = 0;
    final List<Long> afterTimes = new ArrayList<>();
    for (final Frame fr : frames) {
      if (!fr.kind().equals("BUST")) {
        continue;
      }
      final String key = fr.l("venue") + (fr.f().get("source") == TradeSource.Execution ? ":E:" : ":R:") + fr.l("tradeId");
      assertTrue(fillByKey.containsKey(key), "a bust names a fill: " + key);
      assertTrue(busted.add(key), "busted once");
      assertEquals(fillByKey.get(key).l("instrument"), fr.l("instrument"));
      if (fr.at() < CUT) {
        before++;
        assertEquals(transactOf.get(key) + 5 * MINUTE, fr.at(), "five minutes after the fill");
        if (arrivals.get(key).get(0) > fr.at()) {
          bustFirst++;
          assertEquals(transactOf.get(key) + 10 * MINUTE, arrivals.get(key).get(0));
        }
      } else {
        after++;
        afterTimes.add(fr.at());
      }
    }
    assertEquals(p.busts(), before);
    assertEquals(1, bustFirst, "the first bust arrives before its fill");
    assertEquals(p.bustsAfterCut(), after);
    for (int k = 0; k < afterTimes.size(); k++) {
      assertEquals(CUT + 3_600 * SECOND + k * SECOND, afterTimes.get(k));
    }

    // the drop copy: every fill not busted, in transaction order, then the repeated lines
    final List<ReconItem> drop = r.dropCopy();
    final int live = fillByKey.size() - busted.size();
    assertEquals(live + p.dropCopyDuplicates(), drop.size());
    final Set<String> keys = new LinkedHashSet<>();
    for (int n = 0; n < drop.size(); n++) {
      final ReconItem item = drop.get(n);
      final Frame fr = fillByKey.get(item.matchKey());
      assertTrue(fr != null && !busted.contains(item.matchKey()), "drop copy line " + item);
      assertEquals(transactOf.get(item.matchKey()), item.transactNanos());
      assertEquals(fr.l("instrument"), item.instrumentId());
      if (n < live) {
        assertTrue(keys.add(item.matchKey()), "each fill once before the repeats");
        if (n > 0) {
          assertTrue(drop.get(n - 1).transactNanos() <= item.transactNanos(), "transaction order");
        }
      } else {
        assertTrue(keys.contains(item.matchKey()), "a repeat of a line already sent");
      }
    }
    assertEquals("duplicates=5 late-within-day=2 late-after-cut=3 busts=3 busts-after-cut=1 drop-copy-duplicates=2",
        r.injected());
  }

  @Test
  void anEmptyDayAndMoreDropCopyRepeatsThanFillsAreHandled() {
    final SyntheticDay.Result empty =
        SyntheticDay.generate(dir.resolve("empty.raj"), 1L, new SyntheticDay.Params(0, 0, 3, 3, 3, 3, 3, 3));
    assertEquals(0L, empty.frames());
    assertEquals(0, empty.fills());
    assertTrue(empty.dropCopy().isEmpty());
    final SyntheticDay.Result one =
        SyntheticDay.generate(dir.resolve("one.raj"), 1L, new SyntheticDay.Params(1, 0, 0, 0, 0, 0, 0, 9));
    assertEquals(one.fills() + one.fills(), one.dropCopy().size(), "at most one repeat per fill");
  }

  private static Frame only(final List<Frame> frames, final String kind, final String field, final long value) {
    Frame found = null;
    for (final Frame fr : frames) {
      if (fr.kind().equals(kind) && fr.l(field) == value) {
        assertTrue(found == null, kind + " " + value + " twice");
        found = fr;
      }
    }
    assertTrue(found != null, kind + " " + value + " missing");
    return found;
  }

  private static List<Frame> read(final Path journal) {
    final List<Frame> out = new ArrayList<>();
    final MessageHeaderDecoder h = new MessageHeaderDecoder();
    final NewOrderDecoder no = new NewOrderDecoder();
    final ExecutionReportDecoder er = new ExecutionReportDecoder();
    final QuoteRequestDecoder qr = new QuoteRequestDecoder();
    final QuoteDecoder q = new QuoteDecoder();
    final QuoteResponseDecoder resp = new QuoteResponseDecoder();
    final PostTradeBustDecoder bust = new PostTradeBustDecoder();
    new Replayer(new ManualClock(0)).replay(journal, (seq, at, template, version, buffer, offset, length) -> {
      h.wrap(buffer, offset);
      final Map<String, Object> f = new HashMap<>();
      final String kind;
      switch (h.templateId()) {
        case NewOrderDecoder.TEMPLATE_ID -> {
          no.wrapAndApplyHeader(buffer, offset, h);
          kind = "NO";
          f.put("clOrdId", no.clOrdId());
          f.put("venue", no.venueId());
          f.put("instrument", no.instrumentId());
          f.put("side", no.side());
          f.put("price", no.price().mantissa());
          f.put("qty", no.quantity());
          f.put("sent", no.sentNanos());
        }
        case ExecutionReportDecoder.TEMPLATE_ID -> {
          er.wrapAndApplyHeader(buffer, offset, h);
          kind = "ER";
          f.put("clOrdId", er.clOrdId());
          f.put("venueOrderId", er.venueOrderId());
          f.put("execId", er.execId());
          f.put("instrument", er.instrumentId());
          f.put("side", er.side());
          f.put("status", er.ordStatus());
          f.put("lastPrice", er.lastPrice().mantissa());
          f.put("lastQty", er.lastQuantity());
          f.put("leaves", er.leavesQuantity());
          f.put("cum", er.cumQuantity());
          f.put("transact", er.transactNanos());
        }
        case QuoteRequestDecoder.TEMPLATE_ID -> {
          qr.wrapAndApplyHeader(buffer, offset, h);
          kind = "QR";
          f.put("rfqId", qr.rfqId());
          f.put("client", qr.clientId());
          f.put("instrument", qr.instrumentId());
          f.put("side", qr.side());
          f.put("qty", qr.quantity());
          f.put("expiry", qr.expiryNanos());
        }
        case QuoteDecoder.TEMPLATE_ID -> {
          q.wrapAndApplyHeader(buffer, offset, h);
          kind = "Q";
          f.put("rfqId", q.rfqId());
          f.put("quoteId", q.quoteId());
          f.put("bid", q.bidPrice().mantissa());
          f.put("ask", q.askPrice().mantissa());
          f.put("bidQty", q.bidQuantity());
          f.put("validUntil", q.validUntilNanos());
        }
        case QuoteResponseDecoder.TEMPLATE_ID -> {
          resp.wrapAndApplyHeader(buffer, offset, h);
          kind = "QRESP";
          f.put("quoteId", resp.quoteId());
          f.put("status", resp.status());
          f.put("price", resp.tradedPrice().mantissa());
          f.put("qty", resp.tradedQuantity());
          f.put("when", resp.timestampNanos());
          f.put("instrument", SyntheticDay.SWAP);
        }
        case PostTradeBustDecoder.TEMPLATE_ID -> {
          bust.wrapAndApplyHeader(buffer, offset, h);
          kind = "BUST";
          f.put("venue", bust.venueId());
          f.put("source", bust.tradeSource());
          f.put("tradeId", bust.tradeId());
          f.put("instrument", bust.instrumentId());
        }
        default -> kind = "OTHER";
      }
      out.add(new Frame(seq, at, kind, f));
    });
    return out;
  }
}
