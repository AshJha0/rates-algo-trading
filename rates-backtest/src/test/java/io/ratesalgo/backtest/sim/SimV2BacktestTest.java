package io.ratesalgo.backtest.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import io.ratesalgo.backtest.model.ClobFillModel;
import io.ratesalgo.backtest.model.HedgeCostModel;
import io.ratesalgo.backtest.model.RfqOutcomeModel;
import io.ratesalgo.backtest.strategy.FixedSpreadStrategy;
import io.ratesalgo.backtest.synth.SyntheticDay;
import io.ratesalgo.backtest.util.Prices;
import io.ratesalgo.journal.JournalReader;
import io.ratesalgo.messages.sbe.BookUpdateDecoder;
import io.ratesalgo.messages.sbe.CurveInputDecoder;
import io.ratesalgo.messages.sbe.CurveInputKind;
import io.ratesalgo.messages.sbe.ExecutionReportDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.sim.MarketModel;
import io.ratesalgo.sim.MarketSpec;
import io.ratesalgo.sim.RfqDecision;
import io.ratesalgo.sim.SessionSeeds;
import io.ratesalgo.sim.SimInstrument;
import io.ratesalgo.sim.SimParams;
import io.ratesalgo.sim.SimRfq;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The back-test's binding to simulator v2: the synthetic day is the shared model's day, RFQs are
 * settled by the model's competitors, hedges trade on the model's book, marks are the latent mid.
 * This is the back-test half of acceptance test A7 (one specification); the venue half compares
 * the venue simulator with the same model.
 *
 * <p>Nothing here looks at the desk's P&amp;L: the assertions are about which model decided what.
 * Seeds are from the acceptance base, 28,000,000.
 */
class SimV2BacktestTest {

  private static final LocalDate DATE = LocalDate.of(2027, 1, 4);
  private static final long SEED = SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 60_000);

  private static MarketSpec spec;

  @TempDir static Path dir;

  private static Path journal;
  private static SyntheticDay.Summary summary;

  @BeforeAll
  static void generate() {
    spec = new MarketSpec(SimParams.loadDefault());
    journal = dir.resolve("v2.raj");
    summary =
        SyntheticDay.generateSimV2(
            SimDay.of(spec, SEED, DATE), SyntheticDay.Publication.fromPresets(TestSupport.presets()), journal,
            dir.resolve("truth.csv"));
  }

  private static BacktestResult run(final SimDay day, final Strategy strategy) {
    return new BacktestRunner(BacktestConfig.forSimV2(TestSupport.presets(), day, List.of(journal), null)).run(strategy);
  }

  /** The journal carries exactly the model's RFQs and reference prices, and is reproducible. */
  @Test
  void a7_journalIsTheSharedModelsDay() throws IOException {
    final SimDay day = SimDay.of(spec, SEED, DATE);
    final MarketModel model = day.model();
    assertEquals(SessionSeeds.dayType(SEED), model.dayType());
    final MessageHeaderDecoder header = new MessageHeaderDecoder();
    final QuoteRequestDecoder rfq = new QuoteRequestDecoder();
    final CurveInputDecoder curve = new CurveInputDecoder();
    final BookUpdateDecoder bookUpdate = new BookUpdateDecoder();
    final int[] seen = new int[3];
    try (JournalReader reader = JournalReader.open(journal)) {
      while (reader.next(
          (sequence, ts, templateId, version, buffer, offset, length) -> {
            if (templateId == QuoteRequestDecoder.TEMPLATE_ID) {
              rfq.wrapAndApplyHeader(buffer, offset, header);
              final SimRfq r = model.rfqs().get(seen[0]++);
              assertEquals(day.rfqId(r), rfq.rfqId());
              assertEquals(r, day.rfq(rfq.rfqId()));
              assertEquals(day.openEpochNanos() + r.arrivalNanos(), ts);
              assertEquals(ts, rfq.receivedNanos());
              assertEquals(spec.instrument(r.instrument()).id(), rfq.instrumentId());
              assertEquals(r.clientBuys() ? Side.Buy : Side.Sell, rfq.side());
              assertEquals(Math.round(r.quantityUnits() * 100.0), rfq.quantity());
              assertEquals(r.dealersAsked(), rfq.dealerCount());
              assertEquals(r.tier() + 1, rfq.clientId());
            } else if (templateId == CurveInputDecoder.TEMPLATE_ID) {
              curve.wrapAndApplyHeader(buffer, offset, header);
              if (curve.kind() == CurveInputKind.ParSwap) {
                // the published reference: the latent mid rounded to the swap's tick
                final int i = day.index(curve.instrumentId());
                final double published = Prices.toDouble(curve.value().mantissa());
                assertEquals(model.referencePrice(i, ts - day.openEpochNanos()), published, 1e-9);
                assertEquals(0, curve.value().mantissa() % 100_000L);
                assertTrue(Math.abs(published - day.latentMid(curve.instrumentId(), ts)) <= 0.00005 + 1e-12);
                seen[1]++;
              }
            } else if (templateId == BookUpdateDecoder.TEMPLATE_ID) {
              bookUpdate.wrapAndApplyHeader(buffer, offset, header);
              final SimInstrument inst = day.instrument(bookUpdate.instrumentId());
              assertTrue(inst.hasBook());
              assertEquals(0, bookUpdate.price().mantissa() % Math.round(inst.tick() * 1e9));
              // within five ticks of the latent mid plus the transient displacement of a few ticks
              final double away = Prices.toDouble(bookUpdate.price().mantissa()) - day.latentMid(inst.id(), ts);
              assertTrue(Math.abs(away) < 12 * inst.tick(), "book price " + away + " from the latent mid");
              seen[2]++;
            }
          })) {
        // frames are checked in the handler
      }
    }
    assertEquals(model.rfqs().size(), seen[0]);
    assertEquals(summary.rfqs(), seen[0]);
    assertEquals(summary.curveInputs() - 1, seen[1]);
    assertEquals(7 * (36_000 / 5 + 1), seen[1]);
    assertEquals(summary.bookUpdates(), seen[2]);
    assertTrue(seen[2] > 10 * spec.bookCount());
    assertEquals(SEED, summary.seed());
    assertEquals(DATE, summary.date());
    assertEquals(model.rfqs().size() + 1, Files.readAllLines(dir.resolve("truth.csv")).size());
    assertTrue(summary.informedRfqs() > 0 && summary.informedRfqs() < summary.rfqs());

    // the same seed writes the same journal, whatever was done to the first day's model
    day.market(InstrumentSet.TY_FUTURE, true, 500 * 10_000_000L, day.openEpochNanos() + 60_000_000_000L);
    final SyntheticDay.Summary again =
        SyntheticDay.generateSimV2(day, SyntheticDay.Publication.fromPresets(TestSupport.presets()), dir.resolve("again.raj"), null);
    assertEquals(summary.chainHash(), again.chainHash());
    assertEquals(summary.frames(), again.frames());
    final SyntheticDay.Summary other =
        SyntheticDay.generateSimV2(
            SimDay.of(spec, SEED + 1, DATE), new SyntheticDay.Publication(60, 3.6), dir.resolve("other.raj"), null);
    assertNotEquals(summary.chainHash(), other.chainHash());
    assertThrows(IllegalArgumentException.class, () -> new SyntheticDay.Publication(0, 3.6));
  }

  /** Every quote is settled by the model's own decision rule: same competitors, same reserve, best price. */
  @Test
  void a7_quotesAreSettledByTheSharedModel() {
    // a threshold no session reaches, so the desk never trades on the book and the model stays exogenous
    final FixedSpreadStrategy quoter = new FixedSpreadStrategy(0.12, 0.07, 1e15, 1);
    final SimDay day = SimDay.of(spec, SEED, DATE);
    final BacktestResult result = run(day, quoter);
    final MarketModel twin = SessionSeeds.session(spec, SEED);
    final BacktestConfig config = BacktestConfig.forSimV2(TestSupport.presets(), day, List.of(journal), null);
    assertEquals(twin.rfqs().size(), result.rfqs().size());
    int won = 0;
    int lost = 0;
    for (int n = 0; n < result.rfqs().size(); n++) {
      final RfqRecord rec = result.rfqs().get(n);
      final SimRfq r = twin.rfqs().get(n);
      assertEquals(day.rfqId(r), rec.rfqId());
      assertEquals(r.tier() + 1, rec.tier());
      assertTrue(rec.quoted() && rec.settled());
      final RfqDecision expected =
          twin.decide(r, Prices.toDouble(rec.ourPriceMantissa()), config.quoteLatencyNanos());
      assertEquals(expected.deskWon(), rec.won(), "rfq " + n);
      // the desk's spread is measured from the latent mid, which is the mid the client measures from
      assertEquals(twin.midAtArrival(r), rec.midAtQuote(), 1e-12);
      if (rec.won()) {
        won++;
        assertEquals(rec.ourPriceMantissa(), rec.tradedPriceMantissa());
        assertEquals(1.0, rec.winProbability(), 0.0);
        if (!Double.isNaN(expected.coverPrice())) {
          // the cover reported to the desk is the second-best price
          final Instrument inst = config.instruments().get(rec.instrumentId());
          final double sign = rec.clientBuys() ? 1.0 : -1.0;
          assertEquals(inst.priceToBp(sign * (expected.coverPrice() - rec.midAtQuote())), rec.coverBp(), 1e-9);
          assertTrue(rec.spreadToCoverBp() <= 1e-9);
        }
      } else {
        lost++;
        assertEquals(0.0, rec.winProbability(), 0.0);
      }
    }
    assertTrue(won > 20 && lost > 20, "won " + won + " lost " + lost);
    assertEquals(won, result.clientFills());
    assertEquals(0, result.hedgeFills());
    assertEquals(0.0, day.levelOffset(day.closeEpochNanos()), 0.0);
    // client fills are marked at the latent mid of the fill time
    for (final FillRecord f : result.fills()) {
      assertEquals(day.latentMid(f.instrumentId(), f.timeNanos()), f.mid(), 1e-12);
    }
    // a second run on a fresh day of the same seed is the same run
    assertEquals(result.outputChainHash(), run(SimDay.of(spec, SEED, DATE), new FixedSpreadStrategy(0.12, 0.07, 1e15, 1)).outputChainHash());
  }

  /** Hedges are market orders on the model's book: they pay the venue fee and move the level factor. */
  @Test
  void hedgesTradeOnTheSharedModelsBook() {
    final SimDay day = SimDay.of(spec, SEED, DATE);
    final BacktestResult result = run(day, new FixedSpreadStrategy(0.12, 0.07, 20_000, 1));
    assertTrue(result.hedgeFills() > 5, "hedges " + result.hedgeFills());
    final double feePerLot = TestSupport.presets().get(HedgeCostModel.PRESET).value("fee_per_lot");
    for (final FillRecord f : result.fills()) {
      if (f.hedge()) {
        assertEquals(InstrumentSet.TY_FUTURE, f.instrumentId());
        assertEquals(feePerLot * Math.abs(f.signedQuantity()) / 10_000_000.0, f.fee(), 1e-9);
        // a buy pays above the mid it is marked at less the book's transient displacement: within ten ticks
        assertTrue(Math.abs(f.price() - f.mid()) < 10 * 0.015625);
      }
    }
    assertNotEquals(0.0, day.levelOffset(day.closeEpochNanos()));
    assertNotEquals(0.0, day.model().book(day.index(InstrumentSet.TY_FUTURE)).ownDayVolume() + 0.5);
    assertEquals(
        result.outputChainHash(),
        run(SimDay.of(spec, SEED, DATE), new FixedSpreadStrategy(0.12, 0.07, 20_000, 1)).outputChainHash());
  }

  /** A strategy that works limit orders on the 10-year note, cancels them, and sweeps the future's book once. */
  private static final class OrderWorker implements Strategy {
    final List<String> reports = new ArrayList<>();
    long near;
    long far;
    long swap;
    long sweep;
    long marketable;
    long firstTimer;
    long nearTraded;

    @Override
    public String name() {
      return "order_worker";
    }

    @Override
    public void onStart(final StrategyContext ctx) {
      firstTimer = ctx.scheduleTimer(ctx.nowEpochNanos() + 3L * 3600L * 1_000_000_000L);
    }

    @Override
    public void onQuoteRequest(final QuoteRequestDecoder rfq, final StrategyContext ctx) {
      // declines every RFQ
    }

    @Override
    public void onTimer(final long timerId, final long nowEpochNanos, final StrategyContext ctx) {
      final int note = 202;
      final long tick = ctx.instruments().get(note).tickMantissa();
      if (timerId == firstTimer) {
        final long bid = Math.floorDiv(Prices.toMantissa(ctx.mid(note)), tick) * tick - tick;
        near = ctx.sendOrder(note, Side.Buy, OrdType.Limit, 20 * InstrumentSet.MILLION, bid, true);
        far = ctx.sendOrder(note, Side.Sell, OrdType.Limit, 5 * InstrumentSet.MILLION, bid + 200 * tick, true);
        marketable = ctx.sendOrder(note, Side.Sell, OrdType.Limit, 3 * InstrumentSet.MILLION, bid - 20 * tick, true);
        swap = ctx.sendOrder(InstrumentSet.SOFR_10Y, Side.Buy, OrdType.Market, 10 * InstrumentSet.MILLION, 0L, true);
        sweep = ctx.sendOrder(InstrumentSet.TY_FUTURE, Side.Sell, OrdType.Market, 20_000 * 10_000_000L, 0L, true);
        ctx.scheduleTimer(nowEpochNanos + 1800L * 1_000_000_000L);
      } else {
        ctx.cancelOrder(near);
        ctx.cancelOrder(far);
      }
    }

    @Override
    public void onExecutionReport(final ExecutionReportDecoder report, final StrategyContext ctx) {
      reports.add(report.clOrdId() + ":" + report.ordStatus() + ":" + report.lastQuantity());
      if (report.clOrdId() == near && report.ordStatus() != OrdStatus.New) {
        nearTraded += report.lastQuantity();
      }
    }
  }

  @Test
  void restingOrdersFillOnTheSharedModelsBook() {
    final SimDay day = SimDay.of(spec, SEED, DATE);
    final OrderWorker worker = new OrderWorker();
    final BacktestResult result = run(day, worker);
    final List<String> r = worker.reports;
    // no order book for swaps in Round 3
    assertTrue(r.contains(worker.swap + ":" + OrdStatus.Rejected + ":0"), r.toString());
    // an order larger than the five levels is filled whole, the excess one tick beyond the book
    assertTrue(r.contains(worker.sweep + ":" + OrdStatus.Filled + ":" + 20_000 * 10_000_000L), r.toString());
    // a sell limit far below the bid trades at once and is booked when complete, at its limit
    assertTrue(r.contains(worker.marketable + ":" + OrdStatus.New + ":0"));
    assertTrue(r.contains(worker.marketable + ":" + OrdStatus.Filled + ":" + 3 * InstrumentSet.MILLION), r.toString());
    // a sell limit 200 ticks away rests untouched until it is cancelled
    assertTrue(r.contains(worker.far + ":" + OrdStatus.New + ":0"));
    assertTrue(r.contains(worker.far + ":" + OrdStatus.Cancelled + ":0"));
    assertFalse(r.contains(worker.far + ":" + OrdStatus.Filled + ":" + 5 * InstrumentSet.MILLION));
    // the bid one tick under the market either filled from the queue or was cancelled with what it had traded booked
    assertTrue(r.contains(worker.near + ":" + OrdStatus.New + ":0"));
    final boolean filled = r.contains(worker.near + ":" + OrdStatus.Filled + ":" + 20 * InstrumentSet.MILLION);
    final boolean cancelled = r.contains(worker.near + ":" + OrdStatus.Cancelled + ":0");
    assertTrue(filled != cancelled, r.toString());
    long position = 0;
    for (final FillRecord f : result.fills()) {
      if (f.instrumentId() == 202) {
        position += f.signedQuantity();
      }
    }
    assertEquals(worker.nearTraded - 3 * InstrumentSet.MILLION, position);
    assertEquals(result.rfqs().size(), result.declines());
    // the sweep moved the level factor: selling futures raises yields
    assertTrue(day.levelOffset(day.closeEpochNanos()) > 0.0);
  }

  @Test
  void simV2ModelsRefuseVersionOneCalls() {
    final SimDay day = SimDay.of(spec, SEED, DATE);
    final RfqOutcomeModel outcomes = RfqOutcomeModel.simV2(day);
    assertEquals(day, outcomes.simDay());
    assertEquals(RfqOutcomeModel.ClientChoice.BEST_PRICE, outcomes.clientChoice());
    assertNull(outcomes.hitRate());
    assertNull(outcomes.competitors());
    final Instrument swap = InstrumentSet.simV2(spec.params()).get(InstrumentSet.SOFR_10Y);
    assertThrows(IllegalStateException.class, () -> outcomes.settle(1L, swap, 0.1, 10, 3, false));
    assertThrows(IllegalStateException.class, () -> outcomes.withClientChoice(RfqOutcomeModel.ClientChoice.HIT_RATE));
    final HedgeCostModel hedge = HedgeCostModel.simV2(day, 1.5);
    assertEquals(day, hedge.simDay());
    assertFalse(hedge.depthWalk());
    assertThrows(IllegalArgumentException.class, () -> HedgeCostModel.simV2(day, -1.0));
    final Instrument ty = InstrumentSet.simV2(spec.params()).get(InstrumentSet.TY_FUTURE);
    assertThrows(IllegalArgumentException.class, () -> hedge.fillSimV2(ty, true, 0L, day.openEpochNanos()));
    assertEquals(day, ClobFillModel.simV2(day).simDay());
    // version 1 models have no session, and a version 1 resting order has nothing to cancel
    assertNull(TestSupport.config(journal, io.ratesalgo.backtest.model.BandPoint.CENTRAL).outcomes().simDay());
    assertNull(new ClobFillModel(0.6).simDay());
    assertNull(new HedgeCostModel(0.1, 1.5, 1.0).simDay());
    assertEquals(0L, new ClobFillModel(0.6).join(true, 100L, 100L, 5L).cancel());

    assertThrows(IllegalArgumentException.class, () -> day.index(999));
    assertFalse(day.contains(999));
    assertTrue(day.contains(InstrumentSet.UST_10Y));
    assertThrows(IllegalArgumentException.class, () -> day.rfq(5L));
    assertThrows(IllegalArgumentException.class, () -> day.rfq(day.rfqId(day.model().rfqs().get(0)) + 100_000));
    assertEquals(0.0, day.filledMinor(77), 0.0);
    assertFalse(day.complete(77));
    assertEquals(0.0, day.cancel(77), 0.0);
    assertEquals(DATE, day.date());
    assertEquals(36_000L * 1_000_000_000L, day.closeEpochNanos() - day.openEpochNanos());
    // 07:00 New York on 4 January 2027 is 12:00 UTC
    assertEquals(LocalDate.of(2027, 1, 4).toEpochDay() * 86_400L + 12 * 3600L, day.openEpochNanos() / 1_000_000_000L);
  }

  @Test
  void simV2InstrumentsComeFromTheSharedUniverse() {
    final InstrumentSet set = InstrumentSet.simV2(spec.params());
    assertEquals(12, set.all().size());
    final Instrument note = set.get(InstrumentSet.UST_10Y);
    final Instrument old = InstrumentSet.usdDefault().get(InstrumentSet.UST_10Y);
    assertEquals(old.priceChangePerBp(), note.priceChangePerBp());
    assertEquals(old.tickMantissa(), note.tickMantissa());
    assertEquals(old.lotSize(), note.lotSize());
    assertEquals(old.fundingRate(), note.fundingRate());
    assertEquals(Instrument.Kind.BOND, note.kind());
    final Instrument ty = set.get(InstrumentSet.TY_FUTURE);
    assertEquals(InstrumentSet.usdDefault().get(InstrumentSet.TY_FUTURE).lotSize(), ty.lotSize());
    assertEquals(InstrumentSet.usdDefault().get(InstrumentSet.TY_FUTURE).tickMantissa(), ty.tickMantissa());
    assertEquals(Instrument.Kind.FUTURE, ty.kind());
    final Instrument swap = set.get(InstrumentSet.SOFR_10Y);
    assertEquals(InstrumentSet.usdDefault().get(InstrumentSet.SOFR_10Y).valuePerUnit(), swap.valuePerUnit(), 1e-18);
    assertEquals(100_000L, swap.tickMantissa());
    assertTrue(set.contains(105) && set.contains(106) && set.contains(107) && set.contains(203) && set.contains(204));
  }
}
