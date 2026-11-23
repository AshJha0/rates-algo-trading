package io.ratesalgo.backtest.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.BacktestConfig;
import io.ratesalgo.backtest.BacktestResult;
import io.ratesalgo.backtest.BacktestRunner;
import io.ratesalgo.backtest.FillRecord;
import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.InstrumentSet;
import io.ratesalgo.backtest.RfqRecord;
import io.ratesalgo.backtest.Strategy;
import io.ratesalgo.backtest.StrategyContext;
import io.ratesalgo.backtest.TestSupport;
import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.synth.SyntheticDay;
import io.ratesalgo.backtest.util.Prices;
import io.ratesalgo.messages.sbe.ExecutionReportDecoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteResponseDecoder;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import io.ratesalgo.sim.MarketModel;
import io.ratesalgo.sim.MarketSpec;
import io.ratesalgo.sim.RfqDecision;
import io.ratesalgo.sim.SessionSeeds;
import io.ratesalgo.sim.SimParams;
import io.ratesalgo.sim.SimRfq;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the back-tester does with a strategy's quotes and orders on simulator v2: withdrawals,
 * covers on wins and losses, passive fills booked as they happen, immediate-or-cancel orders.
 * Nothing here reads a P&amp;L level. A two-hour window of one acceptance-base session keeps the
 * replays short; the model behind it is the full day's.
 */
class SimV2OrdersTest {

  private static final LocalDate DATE = LocalDate.of(2027, 1, 5);
  private static final long SEED = SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 61_000);
  private static final long HOUR = 3600L * 1_000_000_000L;

  private static MarketSpec spec;

  @TempDir static Path dir;

  private static Path journal;

  @BeforeAll
  static void generate() {
    spec = new MarketSpec(SimParams.loadDefault());
    journal = dir.resolve("window.raj");
    SyntheticDay.generateSimV2(
        SimDay.of(spec, SEED, DATE), SyntheticDay.Publication.fromPresets(TestSupport.presets()), journal, null, 2 * HOUR);
  }

  private static BacktestResult run(final SimDay day, final Strategy s) {
    return new BacktestRunner(BacktestConfig.forSimV2(TestSupport.presets(), day, List.of(journal), null)).run(s);
  }

  /** Quotes every RFQ 0.1 bp from mid; withdraws every other quote at once; records answers. */
  private static final class Quoter implements Strategy {
    final Map<Long, long[]> answers = new HashMap<>();
    final List<Long> withdrawn = new ArrayList<>();
    boolean lateWithdrawal;
    boolean unknownWithdrawal;
    int n;

    @Override
    public String name() {
      return "quoter";
    }

    @Override
    public void onQuoteRequest(final QuoteRequestDecoder rfq, final StrategyContext ctx) {
      final Instrument inst = ctx.instruments().get(rfq.instrumentId());
      final double mid = ctx.mid(rfq.instrumentId());
      final long px = Prices.toMantissa(mid + (rfq.side() == Side.Buy ? 1 : -1) * inst.bpToPrice(0.1));
      ctx.quote(rfq.rfqId(), px, px);
      if (n++ % 2 == 1 && ctx.withdrawQuote(rfq.rfqId())) {
        withdrawn.add(rfq.rfqId());
      }
      unknownWithdrawal |= ctx.withdrawQuote(rfq.rfqId() + 999_999);
    }

    @Override
    public void onQuoteResponse(final QuoteResponseDecoder r, final StrategyContext ctx) {
      answers.put(r.rfqId(), new long[] {r.status().value(), r.tradedPrice().mantissa(), r.coverPrice().mantissa()});
      // the client has decided: too late to withdraw
      lateWithdrawal |= ctx.withdrawQuote(r.rfqId());
    }
  }

  @Test
  void withdrawnQuotesTakeNoPartAndCoversArePassedOnWinsAndLosses() {
    final SimDay day = SimDay.of(spec, SEED, DATE);
    final Quoter q = new Quoter();
    final BacktestResult result = run(day, q);
    final MarketModel twin = SessionSeeds.session(spec, SEED);
    final long latency = BacktestConfig.forSimV2(TestSupport.presets(), day, List.of(journal), null).quoteLatencyNanos();
    assertFalse(q.lateWithdrawal);
    assertFalse(q.unknownWithdrawal);
    assertTrue(q.withdrawn.size() >= 10, "withdrawn " + q.withdrawn.size());
    int coveredWins = 0;
    int disclosedLosses = 0;
    for (final RfqRecord rec : result.rfqs()) {
      final SimRfq r = day.rfq(rec.rfqId());
      final long[] a = q.answers.get(rec.rfqId());
      if (rec.withdrawn()) {
        assertTrue(q.withdrawn.contains(rec.rfqId()));
        assertFalse(rec.won());
        // the client decided between the competitors alone
        final RfqDecision alone = twin.decide(r, Double.NaN, 0L);
        if (alone.outcome() == RfqDecision.Outcome.DONE) {
          assertEquals(QuoteStatus.TradedAway.value(), a[0]);
          assertEquals(Prices.toMantissa(alone.price()), a[1]);
        }
        // nothing of ours to learn from
        assertEquals(0L, a[2]);
        continue;
      }
      final RfqDecision d = twin.decide(r, Prices.toDouble(rec.ourPriceMantissa()), latency);
      assertEquals(d.deskWon(), rec.won());
      if (rec.won()) {
        assertEquals(QuoteStatus.Done.value(), a[0]);
        assertEquals(Double.isNaN(d.coverPrice()) ? 0L : Prices.toMantissa(d.coverPrice()), a[2]);
        coveredWins += a[2] != 0L ? 1 : 0;
      } else if (d.outcome() == RfqDecision.Outcome.DONE) {
        // lost to a competitor: the winning level is the traded price and the cover
        assertEquals(QuoteStatus.TradedAway.value(), a[0]);
        assertEquals(Prices.toMantissa(d.price()), a[1]);
        assertEquals(a[1], a[2]);
        disclosedLosses++;
      } else {
        assertEquals(0L, a[2]);
      }
    }
    assertTrue(coveredWins > 5 && disclosedLosses > 5, "wins with cover " + coveredWins + ", losses " + disclosedLosses);
  }

  /** Works one large bid at the touch, an immediate-or-cancel sweep, and cancels the bid later. */
  private static final class Worker implements Strategy {
    final List<String> reports = new ArrayList<>();
    final List<long[]> bidFills = new ArrayList<>();
    long bid;
    long ioc;
    long first;

    @Override
    public String name() {
      return "worker";
    }

    @Override
    public void onStart(final StrategyContext ctx) {
      first = ctx.scheduleTimer(ctx.nowEpochNanos() + HOUR / 4);
    }

    @Override
    public void onQuoteRequest(final QuoteRequestDecoder rfq, final StrategyContext ctx) {
      // declines
    }

    @Override
    public void onTimer(final long timerId, final long nowEpochNanos, final StrategyContext ctx) {
      final int note = InstrumentSet.UST_10Y;
      final long tick = ctx.instruments().get(note).tickMantissa();
      if (timerId == first) {
        final long best = Math.floorDiv(Prices.toMantissa(ctx.mid(note)), tick) * tick;
        // a day order that crosses the book: what the book shows trades at once, the rest rests
        // on the 5-year note, so the two orders do not compete for the same depth
        final int five = 203;
        final long tick5 = ctx.instruments().get(five).tickMantissa();
        final long best5 = Math.floorDiv(Prices.toMantissa(ctx.mid(five)), tick5) * tick5;
        bid = ctx.sendOrder(five, Side.Buy, OrdType.Limit, TimeInForce.Day, 2_000 * InstrumentSet.MILLION, best5 + 3 * tick5, true);
        ioc = ctx.sendOrder(note, Side.Buy, OrdType.Limit, TimeInForce.ImmediateOrCancel, 2_000 * InstrumentSet.MILLION,
            best + 3 * tick, true);
        ctx.scheduleTimer(nowEpochNanos + HOUR);
      } else {
        ctx.cancelOrder(bid);
      }
    }

    @Override
    public void onExecutionReport(final ExecutionReportDecoder r, final StrategyContext ctx) {
      reports.add(r.clOrdId() + ":" + r.ordStatus() + ":" + r.lastQuantity() + ":" + r.cumQuantity() + ":" + r.leavesQuantity());
      if (r.clOrdId() == bid && (r.ordStatus() == OrdStatus.PartiallyFilled || r.ordStatus() == OrdStatus.Filled)) {
        bidFills.add(new long[] {r.lastQuantity(), r.cumQuantity()});
      }
    }
  }

  @Test
  void passiveFillsAreBookedAsTheyHappenAndIocLeavesNothingResting() {
    final SimDay day = SimDay.of(spec, SEED, DATE);
    final Worker w = new Worker();
    final BacktestResult result = run(day, w);
    final List<String> r = w.reports;
    assertTrue(r.contains(w.bid + ":New:0:0:" + 2_000 * InstrumentSet.MILLION), r.toString());
    // the crossing part is reported and booked at once, before the order is cancelled
    assertTrue(r.indexOf(w.bid + ":New:0:0:" + 2_000 * InstrumentSet.MILLION) < r.size() - 1);
    assertTrue(r.get(r.indexOf(w.bid + ":New:0:0:" + 2_000 * InstrumentSet.MILLION) + 1).startsWith(w.bid + ":PartiallyFilled:"),
        r.toString());
    assertFalse(w.bidFills.isEmpty(), r.toString());
    long cum = 0;
    for (final long[] f : w.bidFills) {
      cum += f[0];
      assertEquals(cum, f[1]);
    }
    long booked = 0;
    long iocBooked = 0;
    for (final FillRecord f : result.fills()) {
      if (f.refId() == w.bid) {
        booked += f.signedQuantity();
      } else if (f.refId() == w.ioc) {
        iocBooked += f.signedQuantity();
      }
    }
    assertEquals(cum, booked);
    // the rest then filled from the book's takers, or was cancelled with what it had traded booked
    final String done = cum == 2_000 * InstrumentSet.MILLION ? ":Filled:" : ":Cancelled:0:" + cum + ":0";
    assertTrue(r.stream().anyMatch(x -> x.startsWith(w.bid + done)), r.toString());
    // the IOC sweep traded what three ticks of book showed, and the rest was cancelled at once
    assertTrue(iocBooked > 0 && iocBooked < 2_000 * InstrumentSet.MILLION, "ioc booked " + iocBooked);
    assertTrue(r.contains(w.ioc + ":Cancelled:0:" + iocBooked + ":0"), r.toString());
    assertFalse(r.stream().anyMatch(s -> s.startsWith(w.ioc + ":Filled")));
  }

  /** Version 1 has no withdrawal, and the explicit time in force falls back to the old order path. */
  @Test
  void versionOneIgnoresWithdrawalsAndTimeInForce(@TempDir final Path tmp) {
    final Path v1 = TestSupport.day(tmp, LocalDate.of(2026, 2, 2));
    final boolean[] withdrew = {false};
    final long[] order = {0};
    final BacktestResult result =
        new BacktestRunner(TestSupport.config(v1, BandPoint.CENTRAL))
            .run(
                new Strategy() {
                  @Override
                  public String name() {
                    return "v1";
                  }

                  @Override
                  public void onQuoteRequest(final QuoteRequestDecoder rfq, final StrategyContext ctx) {
                    final long px = Prices.toMantissa(ctx.mid(rfq.instrumentId()));
                    if (order[0] == 0) {
                      ctx.quote(rfq.rfqId(), px, px);
                      withdrew[0] |= ctx.withdrawQuote(rfq.rfqId());
                      order[0] = ctx.sendOrder(InstrumentSet.TY_FUTURE, Side.Buy, OrdType.Market, TimeInForce.Day,
                          10_000_000L, 0L, true);
                    }
                  }
                });
    assertFalse(withdrew[0]);
    assertTrue(order[0] > 0);
    assertEquals(1, result.hedgeFills());
  }
}
