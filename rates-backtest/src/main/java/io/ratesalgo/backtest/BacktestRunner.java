package io.ratesalgo.backtest;

import io.ratesalgo.backtest.model.ClobFillModel;
import io.ratesalgo.backtest.model.EventCalendar;
import io.ratesalgo.backtest.model.HedgeCostModel;
import io.ratesalgo.backtest.model.HitRateModel;
import io.ratesalgo.backtest.model.RfqOutcomeModel;
import io.ratesalgo.backtest.pnl.PositionBook;
import io.ratesalgo.backtest.util.Prices;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.ChainHash;
import io.ratesalgo.journal.JournalFormat;
import io.ratesalgo.journal.JournalReader;
import io.ratesalgo.journal.JournalWriter;
import io.ratesalgo.messages.sbe.BookAction;
import io.ratesalgo.messages.sbe.BookUpdateDecoder;
import io.ratesalgo.messages.sbe.CurveInputDecoder;
import io.ratesalgo.messages.sbe.CurveInputKind;
import io.ratesalgo.messages.sbe.ExecutionReportDecoder;
import io.ratesalgo.messages.sbe.ExecutionReportEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.NewOrderEncoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.QuoteEncoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteResponseDecoder;
import io.ratesalgo.messages.sbe.QuoteResponseEncoder;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Replays journals of SBE messages through a {@link Strategy} and settles its quotes and orders
 * with the configured outcome models (API_SPEC.md Section 16.1).
 *
 * <p>The runner reads frames with {@link JournalReader}, which verifies the hash chain, and drives
 * a {@link ManualClock} to each frame's timestamp, as {@code Replayer} does; it reads frames
 * itself rather than through {@code Replayer} so that internal events due before a frame (RFQ
 * outcomes, fills, strategy timers) are delivered first, at their own times. Internal events are
 * ordered by time and then by the order they were scheduled, so a run is a pure function of the
 * journals, the configuration and the strategy. Every output (Quote, QuoteResponse, NewOrder,
 * ExecutionReport) is SBE-encoded, folded into an FNV-1a chain hash and, if an output directory is
 * configured, appended to a results journal; the hash is the determinism fingerprint.
 *
 * <p>The runner is single-threaded and not on the hot path; it allocates per event.
 */
public final class BacktestRunner {

  /** Capacity of the results journal file. */
  public static final int RESULTS_JOURNAL_CAPACITY = 32 << 20;

  private final BacktestConfig config;

  public BacktestRunner(final BacktestConfig config) {
    this.config = config;
  }

  /** Runs one strategy over the configured journals. Each call is an independent run. */
  public BacktestResult run(final Strategy strategy) {
    return new Run(strategy).execute();
  }

  private record Pending(long time, long seq, Kind kind, Object ref) {}

  private enum Kind {
    RFQ_OUTCOME,
    ORDER_ARRIVAL,
    TIMER
  }

  private static final Comparator<Pending> ORDER =
      Comparator.comparingLong(Pending::time).thenComparingLong(Pending::seq);

  private static final class Order {
    final long clOrdId;
    final int instrumentId;
    final boolean buy;
    final OrdType type;
    final long quantity;
    final long priceMantissa;
    final boolean hedge;
    ClobFillModel.Resting resting;
    boolean cancelRequested;

    Order(
        final long clOrdId,
        final int instrumentId,
        final boolean buy,
        final OrdType type,
        final long quantity,
        final long priceMantissa,
        final boolean hedge) {
      this.clOrdId = clOrdId;
      this.instrumentId = instrumentId;
      this.buy = buy;
      this.type = type;
      this.quantity = quantity;
      this.priceMantissa = priceMantissa;
      this.hedge = hedge;
    }
  }

  /** Top of a CLOB book. */
  private static final class Top {
    long bid;
    long bidQty;
    long ask;
    long askQty;

    boolean twoSided() {
      return bid != 0 && ask != 0;
    }
  }

  /** Growable series of mids for markouts. */
  private static final class MidSeries {
    long[] times = new long[1024];
    double[] mids = new double[1024];
    int size;

    void add(final long t, final double mid) {
      if (size == times.length) {
        times = Arrays.copyOf(times, size * 2);
        mids = Arrays.copyOf(mids, size * 2);
      }
      times[size] = t;
      mids[size] = mid;
      size++;
    }

    /** Last mid at or before {@code t}; the first mid if {@code t} precedes all. */
    double at(final long t) {
      int idx = Arrays.binarySearch(times, 0, size, t);
      if (idx < 0) {
        idx = -idx - 2;
      } else {
        while (idx + 1 < size && times[idx + 1] == t) {
          idx++;
        }
      }
      return mids[Math.max(idx, 0)];
    }
  }

  private final class Run implements StrategyContext {
    private final Strategy strategy;
    private final ManualClock clock = new ManualClock(0L);
    private final PositionBook book;
    private final PriorityQueue<Pending> pending = new PriorityQueue<>(ORDER);
    private final Map<Long, RfqRecord> rfqById = new HashMap<>();
    private final List<RfqRecord> rfqs = new ArrayList<>();
    private final List<FillRecord> fills = new ArrayList<>();
    private final Map<Long, Order> resting = new LinkedHashMap<>();
    private final Map<Long, Order> inFlight = new HashMap<>();
    private final Map<Integer, Top> tops = new TreeMap<>();
    private final Map<Integer, MidSeries> mids = new TreeMap<>();
    private final Map<Integer, Long> maxAbsPosition = new TreeMap<>();
    private final RfqOutcomeModel outcomes = config.outcomes();
    private final HedgeCostModel hedgeCost = config.hedgeCost();

    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final QuoteRequestDecoder rfqDecoder = new QuoteRequestDecoder();
    private final BookUpdateDecoder bookDecoder = new BookUpdateDecoder();
    private final CurveInputDecoder curveDecoder = new CurveInputDecoder();
    private final QuoteResponseDecoder responseDecoder = new QuoteResponseDecoder();
    private final ExecutionReportDecoder execDecoder = new ExecutionReportDecoder();

    private final UnsafeBuffer out = new UnsafeBuffer(ByteBuffer.allocateDirect(512));
    private final UnsafeBuffer callback = new UnsafeBuffer(ByteBuffer.allocateDirect(512));
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final QuoteEncoder quoteEncoder = new QuoteEncoder();
    private final QuoteResponseEncoder responseEncoder = new QuoteResponseEncoder();
    private final NewOrderEncoder orderEncoder = new NewOrderEncoder();
    private final ExecutionReportEncoder execEncoder = new ExecutionReportEncoder();

    private JournalWriter results;
    private EventCalendar calendar;
    private long seq;
    private long nextClOrdId = 1;
    private long nextTimerId = 1;
    private long nextExecId = 1;
    private long nextQuoteId = 1;
    private long outputHash = JournalFormat.GENESIS_HASH;
    private long inputFrames;
    private long inputChainHash;
    private long startNanos = -1;
    private double maxAbsNetDv01;

    Run(final Strategy strategy) {
      this.strategy = strategy;
      this.book = new PositionBook(config.instruments(), config.carry());
    }

    BacktestResult execute() {
      try {
        if (config.outputDir() != null) {
          Files.createDirectories(config.outputDir());
          final Path file = config.outputDir().resolve("results.raj");
          Files.deleteIfExists(file);
          results = JournalWriter.create(file, RESULTS_JOURNAL_CAPACITY, clock);
        }
        boolean started = false;
        for (final Path journal : config.journals()) {
          try (JournalReader reader = JournalReader.open(journal)) {
            final boolean[] first = {started};
            while (reader.next(
                (sequence, ts, templateId, version, buffer, offset, length) -> {
                  if (!first[0]) {
                    first[0] = true;
                    begin(ts);
                  }
                  onFrame(ts, templateId, buffer, offset);
                })) {
              inputFrames++;
            }
            started = first[0];
            inputChainHash = reader.chainHash();
          }
        }
        if (!started) {
          throw new IllegalArgumentException("journals contain no frames: " + config.journals());
        }
        drain(Long.MAX_VALUE);
        book.close(clock.epochNanos());
        return finish();
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      } finally {
        if (results != null) {
          results.close();
        }
      }
    }

    private void begin(final long firstTs) {
      startNanos = firstTs;
      clock.setEpochNanos(firstTs);
      final LocalDate date =
          LocalDate.ofInstant(Instant.ofEpochSecond(firstTs / 1_000_000_000L), ZoneOffset.UTC);
      final BacktestConfig.CalendarSpec spec = config.calendar();
      calendar =
          EventCalendar.forDay(date, spec.utcOffsetHours(), spec.minutesEt(), spec.halfWidthMinutes());
      strategy.onStart(this);
    }

    private void onFrame(final long ts, final int templateId, final DirectBuffer buffer, final int offset) {
      if (ts < clock.epochNanos()) {
        throw new IllegalStateException(
            "journal time goes backwards: " + ts + " after " + clock.epochNanos());
      }
      drain(ts);
      clock.setEpochNanos(ts);
      headerDecoder.wrap(buffer, offset);
      if (headerDecoder.schemaId() != QuoteRequestDecoder.SCHEMA_ID
          || headerDecoder.version() != QuoteRequestDecoder.SCHEMA_VERSION) {
        throw new IllegalStateException(
            "schema mismatch: expected id "
                + QuoteRequestDecoder.SCHEMA_ID
                + " version "
                + QuoteRequestDecoder.SCHEMA_VERSION
                + ", found id "
                + headerDecoder.schemaId()
                + " version "
                + headerDecoder.version());
      }
      final int bodyOffset = offset + headerDecoder.encodedLength();
      final int blockLength = headerDecoder.blockLength();
      final int version = headerDecoder.version();
      switch (templateId) {
        case QuoteRequestDecoder.TEMPLATE_ID -> {
          rfqDecoder.wrap(buffer, bodyOffset, blockLength, version);
          onQuoteRequest();
        }
        case BookUpdateDecoder.TEMPLATE_ID -> {
          bookDecoder.wrap(buffer, bodyOffset, blockLength, version);
          onBookUpdate();
        }
        case CurveInputDecoder.TEMPLATE_ID -> {
          curveDecoder.wrap(buffer, bodyOffset, blockLength, version);
          onCurveInput();
        }
        default -> {
          // other templates (heartbeats, risk events) carry nothing the back-tester settles
        }
      }
    }

    private void drain(final long upTo) {
      while (!pending.isEmpty() && pending.peek().time() <= upTo) {
        final Pending p = pending.poll();
        if (p.time() > clock.epochNanos()) {
          clock.setEpochNanos(p.time());
        }
        switch (p.kind()) {
          case RFQ_OUTCOME -> settle((RfqRecord) p.ref());
          case ORDER_ARRIVAL -> arrive((Order) p.ref());
          case TIMER -> strategy.onTimer((Long) p.ref(), clock.epochNanos(), this);
        }
      }
    }

    private void schedule(final long time, final Kind kind, final Object ref) {
      pending.add(new Pending(time, seq++, kind, ref));
    }

    // ---------------------------------------------------------------- inputs

    private void onQuoteRequest() {
      final int instrumentId = rfqDecoder.instrumentId();
      if (!config.instruments().contains(instrumentId)) {
        return;
      }
      final int clientId = rfqDecoder.clientId();
      final RfqRecord rec =
          new RfqRecord(
              rfqDecoder.rfqId(),
              rfqDecoder.receivedNanos(),
              rfqDecoder.expiryNanos(),
              clientId,
              1 + (clientId - 1) / config.clientsPerTier(),
              instrumentId,
              rfqDecoder.side() == Side.Buy,
              rfqDecoder.quantity(),
              rfqDecoder.dealerCount(),
              calendar.inWindow(clock.epochNanos()));
      if (rfqById.put(rec.rfqId, rec) != null) {
        throw new IllegalStateException("duplicate rfqId " + rec.rfqId);
      }
      rfqs.add(rec);
      strategy.onQuoteRequest(rfqDecoder, this);
    }

    private void onBookUpdate() {
      final int id = bookDecoder.instrumentId();
      if (!config.instruments().contains(id) || bookDecoder.level() != 0) {
        strategy.onBookUpdate(bookDecoder, this);
        return;
      }
      final Top top = tops.computeIfAbsent(id, k -> new Top());
      final boolean bid = bookDecoder.side() == Side.Buy;
      final BookAction action = bookDecoder.action();
      if (action == BookAction.Clear) {
        top.bid = 0;
        top.bidQty = 0;
        top.ask = 0;
        top.askQty = 0;
      } else if (action == BookAction.Delete) {
        if (bid) {
          top.bid = 0;
          top.bidQty = 0;
        } else {
          top.ask = 0;
          top.askQty = 0;
        }
      } else {
        if (bid) {
          top.bid = bookDecoder.price().mantissa();
          top.bidQty = bookDecoder.quantity();
        } else {
          top.ask = bookDecoder.price().mantissa();
          top.askQty = bookDecoder.quantity();
        }
      }
      if (top.twoSided()) {
        mark(id, Prices.toDouble(top.bid + top.ask) / 2.0);
      }
      checkResting(id, top);
      strategy.onBookUpdate(bookDecoder, this);
    }

    private void onCurveInput() {
      final int id = curveDecoder.instrumentId();
      if (curveDecoder.kind() == CurveInputKind.ParSwap && config.instruments().contains(id)) {
        mark(id, Prices.toDouble(curveDecoder.value().mantissa()));
      }
      strategy.onCurveInput(curveDecoder, this);
    }

    private void mark(final int id, final double mid) {
      book.mark(id, mid, clock.epochNanos());
      mids.computeIfAbsent(id, k -> new MidSeries()).add(clock.epochNanos(), mid);
    }

    // ---------------------------------------------------------------- RFQs

    @Override
    public void quote(final long rfqId, final long bidMantissa, final long askMantissa) {
      final RfqRecord rec = rfqById.get(rfqId);
      if (rec == null) {
        throw new IllegalArgumentException("quote for unknown rfqId " + rfqId);
      }
      if (rec.quoted) {
        throw new IllegalStateException("rfqId " + rfqId + " already quoted");
      }
      if (bidMantissa > askMantissa) {
        throw new IllegalArgumentException("crossed quote: bid " + bidMantissa + " ask " + askMantissa);
      }
      final long sent = clock.epochNanos() + config.quoteLatencyNanos();
      if (sent > rec.expiryNanos) {
        throw new IllegalStateException("quote for rfqId " + rfqId + " would arrive after expiry");
      }
      final Instrument instrument = config.instruments().get(rec.instrumentId);
      final double mid = mid(rec.instrumentId);
      rec.quoted = true;
      rec.quoteSentNanos = sent;
      rec.midAtQuote = mid;
      rec.ourPriceMantissa = rec.clientBuys ? askMantissa : bidMantissa;
      final double ours = Prices.toDouble(rec.ourPriceMantissa);
      rec.ourSpreadBp = instrument.priceToBp(rec.clientBuys ? ours - mid : mid - ours);
      quoteEncoder
          .wrapAndApplyHeader(out, 0, headerEncoder)
          .venueId(0)
          .rfqId(rfqId)
          .quoteId(nextQuoteId++)
          .instrumentId(rec.instrumentId)
          .bidQuantity(rec.quantity)
          .askQuantity(rec.quantity)
          .referenceNanos(clock.epochNanos())
          .validUntilNanos(rec.expiryNanos)
          .sentNanos(sent);
      quoteEncoder.bidPrice().mantissa(bidMantissa);
      quoteEncoder.askPrice().mantissa(askMantissa);
      emit(out, QuoteEncoder.TEMPLATE_ID, quoteEncoder.limit());
      schedule(sent + config.clientDecisionNanos(), Kind.RFQ_OUTCOME, rec);
    }

    private void settle(final RfqRecord rec) {
      final Instrument instrument = config.instruments().get(rec.instrumentId);
      final RfqOutcomeModel.Outcome o =
          outcomes.settle(
              rec.rfqId, instrument, rec.ourSpreadBp, rec.sizeMillions(), rec.dealers, rec.inFixingWindow);
      rec.settled = true;
      rec.won = o.won();
      rec.coverBp = o.coverSpreadBp();
      rec.winProbability = o.winProbability();
      final double coverDistance = instrument.bpToPrice(o.coverSpreadBp());
      final long coverPrice =
          Prices.toMantissa(rec.clientBuys ? rec.midAtQuote + coverDistance : rec.midAtQuote - coverDistance);
      final long tradedPrice = o.won() ? rec.ourPriceMantissa : coverPrice;
      rec.tradedPriceMantissa = tradedPrice;
      if (o.won()) {
        final long signed = rec.clientBuys ? -rec.quantity : rec.quantity;
        bookFill(rec.instrumentId, signed, Prices.toDouble(tradedPrice), 0.0, false, rec.rfqId, rec.clientId, rec.tier);
      }
      responseEncoder
          .wrapAndApplyHeader(callback, 0, headerEncoder)
          .venueId(0)
          .rfqId(rec.rfqId)
          .quoteId(0)
          .status(o.won() ? QuoteStatus.Done : QuoteStatus.TradedAway)
          .tradedQuantity(rec.quantity)
          .timestampNanos(clock.epochNanos());
      responseEncoder.tradedPrice().mantissa(tradedPrice);
      responseEncoder.coverPrice().mantissa(o.won() ? coverPrice : 0L);
      emit(callback, QuoteResponseEncoder.TEMPLATE_ID, responseEncoder.limit());
      responseDecoder.wrapAndApplyHeader(callback, 0, headerDecoder);
      strategy.onQuoteResponse(responseDecoder, this);
    }

    // ---------------------------------------------------------------- orders

    @Override
    public long sendOrder(
        final int instrumentId,
        final Side side,
        final OrdType type,
        final long quantity,
        final long priceMantissa,
        final boolean hedge) {
      if (quantity <= 0) {
        throw new IllegalArgumentException("order quantity must be positive: " + quantity);
      }
      config.instruments().get(instrumentId);
      final long clOrdId = nextClOrdId++;
      final Order order =
          new Order(clOrdId, instrumentId, side == Side.Buy, type, quantity, priceMantissa, hedge);
      final long sent = clock.epochNanos();
      orderEncoder
          .wrapAndApplyHeader(out, 0, headerEncoder)
          .venueId(0)
          .clOrdId(clOrdId)
          .parentId(0)
          .instrumentId(instrumentId)
          .side(side)
          .ordType(type)
          .timeInForce(type == OrdType.Market ? TimeInForce.ImmediateOrCancel : TimeInForce.Day)
          .quantity(quantity)
          .expireNanos(0)
          .sentNanos(sent);
      orderEncoder.price().mantissa(priceMantissa);
      emit(out, NewOrderEncoder.TEMPLATE_ID, orderEncoder.limit());
      inFlight.put(clOrdId, order);
      schedule(sent + config.orderLatencyNanos(), Kind.ORDER_ARRIVAL, order);
      return clOrdId;
    }

    @Override
    public boolean cancelOrder(final long clOrdId) {
      final Order r = resting.remove(clOrdId);
      if (r != null) {
        report(r, OrdStatus.Cancelled, 0, 0);
        return true;
      }
      final Order f = inFlight.get(clOrdId);
      if (f != null && f.type != OrdType.Market && !f.cancelRequested) {
        f.cancelRequested = true; // reported as cancelled when it arrives
        return true;
      }
      return false;
    }

    private void arrive(final Order order) {
      inFlight.remove(order.clOrdId);
      if (order.cancelRequested) {
        report(order, OrdStatus.Cancelled, 0, 0);
        return;
      }
      final Instrument instrument = config.instruments().get(order.instrumentId);
      if (!book.hasMid(order.instrumentId)) {
        report(order, OrdStatus.Rejected, 0, 0);
        return;
      }
      final Top top = tops.get(order.instrumentId);
      if (order.type == OrdType.Market) {
        final long mid = Prices.toMantissa(book.mid(order.instrumentId));
        final boolean twoSided = top != null && top.twoSided();
        final long half = twoSided ? (top.ask - top.bid) / 2 : 0L;
        final long touchQty = twoSided ? (order.buy ? top.askQty : top.bidQty) : 0L;
        final HedgeCostModel.Fill fill = hedgeCost.fill(instrument, order.buy, order.quantity, mid, half, touchQty);
        fillOrder(order, fill.priceMantissa(), fill.fee());
        return;
      }
      if (top == null) {
        report(order, OrdStatus.Rejected, 0, 0);
        return;
      }
      order.resting =
          config
              .clob()
              .join(
                  order.buy,
                  order.priceMantissa,
                  order.buy ? top.bid : top.ask,
                  order.buy ? top.bidQty : top.askQty);
      report(order, OrdStatus.New, 0, 0);
      resting.put(order.clOrdId, order);
      checkResting(order.instrumentId, top);
    }

    private void checkResting(final int instrumentId, final Top top) {
      if (resting.isEmpty()) {
        return;
      }
      final List<Order> filled = new ArrayList<>();
      for (final Order o : resting.values()) {
        if (o.instrumentId == instrumentId
            && o.resting.onBook(o.buy ? top.bid : top.ask, o.buy ? top.bidQty : top.askQty, o.buy ? top.ask : top.bid)) {
          filled.add(o);
        }
      }
      for (final Order o : filled) {
        resting.remove(o.clOrdId);
        fillOrder(o, o.priceMantissa, hedgeCost.fee(config.instruments().get(o.instrumentId), o.quantity));
      }
    }

    private void fillOrder(final Order order, final long priceMantissa, final double fee) {
      final long signed = order.buy ? order.quantity : -order.quantity;
      bookFill(order.instrumentId, signed, Prices.toDouble(priceMantissa), fee, order.hedge, order.clOrdId, 0, 0);
      report(order, OrdStatus.Filled, priceMantissa, order.quantity);
    }

    private void report(final Order order, final OrdStatus status, final long price, final long qty) {
      execEncoder
          .wrapAndApplyHeader(callback, 0, headerEncoder)
          .venueId(0)
          .clOrdId(order.clOrdId)
          .venueOrderId(order.clOrdId)
          .execId(nextExecId++)
          .instrumentId(order.instrumentId)
          .side(order.buy ? Side.Buy : Side.Sell)
          .ordStatus(status)
          .lastQuantity(qty)
          .leavesQuantity(status == OrdStatus.New ? order.quantity : 0)
          .cumQuantity(qty)
          .transactNanos(clock.epochNanos());
      execEncoder.lastPrice().mantissa(price);
      emit(callback, ExecutionReportEncoder.TEMPLATE_ID, execEncoder.limit());
      execDecoder.wrapAndApplyHeader(callback, 0, headerDecoder);
      strategy.onExecutionReport(execDecoder, this);
    }

    // ---------------------------------------------------------------- booking and output

    private void bookFill(
        final int instrumentId,
        final long signed,
        final double price,
        final double fee,
        final boolean hedge,
        final long refId,
        final int clientId,
        final int tier) {
      final double mid = book.mid(instrumentId);
      book.trade(instrumentId, signed, price, fee, hedge, clock.epochNanos());
      fills.add(
          new FillRecord(
              fills.size() + 1L, clock.epochNanos(), instrumentId, signed, price, mid, fee, hedge, refId,
              clientId, tier, List.of()));
      maxAbsNetDv01 = Math.max(maxAbsNetDv01, Math.abs(book.netDv01()));
      maxAbsPosition.merge(instrumentId, Math.abs(book.position(instrumentId)), Math::max);
    }

    private void emit(final UnsafeBuffer buffer, final int templateId, final int length) {
      outputHash = ChainHash.next(outputHash, buffer, 0, length);
      if (results != null) {
        results.append(templateId, QuoteRequestDecoder.SCHEMA_VERSION, buffer, 0, length);
      }
    }

    private BacktestResult finish() {
      final List<Long> horizons = config.markoutHorizonsNanos();
      final List<FillRecord> withMarkouts = new ArrayList<>(fills.size());
      for (final FillRecord f : fills) {
        final MidSeries series = mids.get(f.instrumentId());
        final double v = config.instruments().get(f.instrumentId()).valuePerUnit();
        final List<Double> m = new ArrayList<>(horizons.size());
        for (final long h : horizons) {
          m.add(f.signedQuantity() * (series.at(f.timeNanos() + h) - f.price()) * v);
        }
        withMarkouts.add(f.withMarkouts(m));
      }
      final Map<Integer, io.ratesalgo.backtest.pnl.Attribution> byInstrument = new TreeMap<>();
      for (final Integer id : book.instrumentIds()) {
        byInstrument.put(id, book.attribution(id));
      }
      final long declines = rfqs.stream().filter(r -> !r.quoted).count();
      return new BacktestResult(
          strategy.name(),
          config.bandPoint(),
          config.seed(),
          startNanos,
          clock.epochNanos(),
          inputFrames,
          inputChainHash,
          outputHash,
          rfqs,
          withMarkouts,
          byInstrument,
          book.total(),
          maxAbsNetDv01,
          maxAbsPosition,
          declines);
    }

    // ---------------------------------------------------------------- context

    @Override
    public long nowEpochNanos() {
      return clock.epochNanos();
    }

    @Override
    public InstrumentSet instruments() {
      return config.instruments();
    }

    @Override
    public boolean hasMid(final int instrumentId) {
      return book.hasMid(instrumentId);
    }

    @Override
    public double mid(final int instrumentId) {
      return book.mid(instrumentId);
    }

    @Override
    public double bookHalfSpread(final int instrumentId) {
      final Top top = tops.get(instrumentId);
      return top != null && top.twoSided() ? Prices.toDouble(top.ask - top.bid) / 2.0 : 0.0;
    }

    @Override
    public long touchPrice(final int instrumentId, final boolean bidSide) {
      final Top top = tops.get(instrumentId);
      return top == null ? 0L : bidSide ? top.bid : top.ask;
    }

    @Override
    public long touchQuantity(final int instrumentId, final boolean bidSide) {
      final Top top = tops.get(instrumentId);
      return top == null ? 0L : bidSide ? top.bidQty : top.askQty;
    }

    @Override
    public long position(final int instrumentId) {
      return book.position(instrumentId);
    }

    @Override
    public double netDv01() {
      return book.netDv01();
    }

    @Override
    public HitRateModel hitRateModel() {
      return config.strategyHitRate();
    }

    @Override
    public EventCalendar calendar() {
      return calendar;
    }

    @Override
    public long scheduleTimer(final long epochNanos) {
      if (epochNanos < clock.epochNanos()) {
        throw new IllegalArgumentException("timer in the past: " + epochNanos);
      }
      final long id = nextTimerId++;
      schedule(epochNanos, Kind.TIMER, id);
      return id;
    }
  }
}
