package io.ratesalgo.posttrade.position;

import io.ratesalgo.journal.FrameHandler;
import io.ratesalgo.messages.sbe.BustReason;
import io.ratesalgo.messages.sbe.ExecutionReportDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.NewOrderDecoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.QuoteDecoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteResponseDecoder;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import io.ratesalgo.messages.sbe.TradeAmendDecoder;
import io.ratesalgo.messages.sbe.TradeBustDecoder;
import io.ratesalgo.messages.sbe.PostTradeBustDecoder;
import io.ratesalgo.messages.sbe.TradeSource;
import io.ratesalgo.posttrade.records.OrderRecord;
import io.ratesalgo.posttrade.trade.BookAssignment;
import io.ratesalgo.posttrade.trade.BookMapper;
import io.ratesalgo.posttrade.trade.PositionKey;
import io.ratesalgo.posttrade.trade.Trade;
import io.ratesalgo.posttrade.trade.TradeKey;
import io.ratesalgo.posttrade.trade.TradeStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.agrona.DirectBuffer;

/**
 * The single source of truth for positions, average cost and realised P&amp;L per book, account
 * and instrument (API_SPEC.md Section 17.2).
 *
 * <p>The keeper is a {@link FrameHandler}: it is driven by the journal, live or in replay, and its
 * state is a function of the frames it has seen. It reads ExecutionReport fills, QuoteResponse
 * {@code Done} outcomes for RFQ trades (joined to their QuoteRequest for instrument and side),
 * NewOrder and Quote for the order records, and the post-trade messages PostTradeBust (100) and
 * TradeAmend (101). A venue's TradeBust (44, FIX ExecType H through the gateway) is applied as a
 * PostTradeBust of the Execution trade {@code (venueId, execId)} with reason VenueError
 * (DECISIONS.md DR-P6-01, API_SPEC.md Section 17.14).
 *
 * <ul>
 *   <li>Duplicates: a second fill with a booked trade key is ignored; if its economics differ it is
 *       flagged {@link AuditKind#DUPLICATE_CONFLICT} for the reconciliation.
 *   <li>Late fills: positions are recomputed in transaction-time order, so a fill that arrives
 *       after a later one lands where it belongs; it is flagged {@link AuditKind#LATE_FILL}.
 *   <li>Busts: the trade stays in the records with status BUSTED and leaves the position. A bust
 *       that arrives before its fill is held and applied when the fill arrives.
 *   <li>Amendments: a new version of the trade with the person who made it in the audit trail.
 * </ul>
 *
 * <p>Off the hot path: it allocates freely and runs on the post-trade thread or in a batch job.
 */
public final class PositionKeeper implements FrameHandler {

  private static final Comparator<Trade> TRANSACTION_ORDER =
      Comparator.comparingLong(Trade::transactNanos)
          .thenComparingLong(Trade::journalSequence)
          .thenComparing(Trade::key);

  private final BookMapper bookMapper;
  private final InstrumentCatalogue catalogue;
  private final ValuationConvention convention;

  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final ExecutionReportDecoder executionReport = new ExecutionReportDecoder();
  private final QuoteRequestDecoder quoteRequest = new QuoteRequestDecoder();
  private final QuoteDecoder quote = new QuoteDecoder();
  private final QuoteResponseDecoder quoteResponse = new QuoteResponseDecoder();
  private final NewOrderDecoder newOrder = new NewOrderDecoder();
  private final PostTradeBustDecoder tradeBust = new PostTradeBustDecoder();
  private final TradeBustDecoder venueBust = new TradeBustDecoder();
  private final TradeAmendDecoder tradeAmend = new TradeAmendDecoder();

  private final Map<TradeKey, Trade> trades = new HashMap<>();
  private final Map<TradeKey, List<Trade>> versions = new HashMap<>();
  private final Map<TradeKey, Long> pendingBusts = new HashMap<>();
  private final Set<TradeKey> seenReports = new HashSet<>();
  private final Set<TradeKey> seenQuoteOutcomes = new HashSet<>();
  private final Map<PositionKey, OpeningPosition> openings = new TreeMap<>();
  private final Map<PositionKey, Long> maxTransact = new HashMap<>();
  private final Set<PositionKey> positionKeys = new TreeSet<>();
  private final Map<PositionKey, Position> cache = new HashMap<>();
  private final Map<VenueRef, RfqInfo> rfqs = new HashMap<>();
  private final Map<VenueRef, QuoteInfo> quotes = new HashMap<>();
  private final Map<Long, OrderState> orders = new TreeMap<>();
  private final List<OrderRecord> records = new ArrayList<>();
  private final Map<Long, List<OrderRecord>> recordsByVenueOrderId = new HashMap<>();
  private final List<AuditEntry> audit = new ArrayList<>();

  private long lastSequence;
  private long lastTimestamp;

  public PositionKeeper(final BookMapper bookMapper, final InstrumentCatalogue catalogue) {
    this(bookMapper, catalogue, catalogue);
  }

  /**
   * Creates a keeper with an explicit valuation convention.
   *
   * @param convention how price moves become money; the catalogue's linear convention unless P1
   *     analytics are bound
   */
  public PositionKeeper(
      final BookMapper bookMapper,
      final InstrumentCatalogue catalogue,
      final ValuationConvention convention) {
    this.bookMapper = bookMapper;
    this.catalogue = catalogue;
    this.convention = convention;
  }

  // ---------------------------------------------------------------- inputs

  /** Loads a start-of-day position re-struck at the previous official mark. */
  public void openPosition(final PositionKey key, final OpeningPosition opening) {
    catalogue.get(key.instrumentId());
    openings.put(key, opening);
    positionKeys.add(key);
    cache.remove(key);
    audit.add(
        new AuditEntry(
            0, 0, AuditKind.OPENING_POSITION, null, 0,
            key.text() + " qty=" + opening.quantity() + " mark=" + opening.price()));
  }

  @Override
  public void onFrame(
      final long sequence,
      final long timestampNanos,
      final int templateId,
      final int schemaVersion,
      final DirectBuffer buffer,
      final int offset,
      final int length) {
    lastSequence = sequence;
    lastTimestamp = timestampNanos;
    header.wrap(buffer, offset);
    if (header.schemaId() != MessageHeaderDecoder.SCHEMA_ID) {
      throw new IllegalStateException(
          "frame " + sequence + ": schema id " + header.schemaId() + ", expected "
              + MessageHeaderDecoder.SCHEMA_ID);
    }
    switch (header.templateId()) {
      case ExecutionReportDecoder.TEMPLATE_ID -> {
        executionReport.wrapAndApplyHeader(buffer, offset, header);
        onExecutionReport(sequence);
      }
      case QuoteRequestDecoder.TEMPLATE_ID -> {
        quoteRequest.wrapAndApplyHeader(buffer, offset, header);
        onQuoteRequest(sequence);
      }
      case QuoteDecoder.TEMPLATE_ID -> {
        quote.wrapAndApplyHeader(buffer, offset, header);
        onQuote(sequence);
      }
      case QuoteResponseDecoder.TEMPLATE_ID -> {
        quoteResponse.wrapAndApplyHeader(buffer, offset, header);
        onQuoteResponse(sequence);
      }
      case NewOrderDecoder.TEMPLATE_ID -> {
        newOrder.wrapAndApplyHeader(buffer, offset, header);
        onNewOrder(sequence);
      }
      case PostTradeBustDecoder.TEMPLATE_ID -> {
        tradeBust.wrapAndApplyHeader(buffer, offset, header);
        onBust(sequence);
      }
      case TradeBustDecoder.TEMPLATE_ID -> {
        venueBust.wrapAndApplyHeader(buffer, offset, header);
        onVenueBust(sequence);
      }
      case TradeAmendDecoder.TEMPLATE_ID -> {
        tradeAmend.wrapAndApplyHeader(buffer, offset, header);
        onAmend(sequence);
      }
      default -> {
        // market data, heartbeats, risk and control frames carry no booking information
      }
    }
  }

  private void onNewOrder(final long sequence) {
    final int venueId = newOrder.venueId();
    final long clOrdId = newOrder.clOrdId();
    final int instrumentId = newOrder.instrumentId();
    final BookAssignment booking = bookMapper.assign(venueId, instrumentId, clOrdId);
    final OrderState state =
        new OrderState(venueId, clOrdId, instrumentId, newOrder.side(), newOrder.quantity());
    orders.put(clOrdId, state);
    final InstrumentReference ref = catalogue.get(instrumentId);
    addRecord(
        new OrderRecord(
            0, tradingDate(newOrder.sentNanos()), "T3", "NEWO", buySell(newOrder.side()), "DEAL",
            false, booking.algorithmId(), clOrdId, 0, venueCode(venueId),
            newOrder.ordType() == OrdType.Market ? "MKT" : "LMT", newOrder.price().mantissa(),
            ref.currency(), ref.priceNotation(), newOrder.quantity(), 0, newOrder.sentNanos(), 0,
            "", validity(newOrder.timeInForce()), ref.identifier(), instrumentId, 0, sequence, ""));
  }

  private void onExecutionReport(final long sequence) {
    final int venueId = executionReport.venueId();
    final TradeKey key = new TradeKey(venueId, TradeSource.Execution, executionReport.execId());
    final long clOrdId = executionReport.clOrdId();
    final long venueOrderId = executionReport.venueOrderId();
    final int instrumentId = executionReport.instrumentId();
    final Side side = executionReport.side();
    final OrdStatus status = executionReport.ordStatus();
    final long lastQty = executionReport.lastQuantity();
    final long lastPrice = executionReport.lastPrice().mantissa();
    final long transact = executionReport.transactNanos();
    final boolean fill = lastQty > 0 && (status == OrdStatus.PartiallyFilled || status == OrdStatus.Filled);

    if (!seenReports.add(key)) {
      final Trade existing = trades.get(key);
      final boolean same =
          existing == null
              || (existing.price() == lastPrice
                  && existing.quantity() == lastQty
                  && existing.instrumentId() == instrumentId
                  && existing.side() == side);
      audit.add(
          new AuditEntry(
              sequence, lastTimestamp,
              same ? AuditKind.DUPLICATE_IGNORED : AuditKind.DUPLICATE_CONFLICT, key, 0,
              same ? "repeat of execution report"
                  : "conflicting repeat: price=" + lastPrice + " qty=" + lastQty));
      return;
    }

    final OrderState order =
        orders.computeIfAbsent(
            clOrdId, id -> new OrderState(venueId, id, instrumentId, side, executionReport.cumQuantity()
                + executionReport.leavesQuantity()));
    order.venueOrderId = venueOrderId;
    order.leaves = executionReport.leavesQuantity();
    order.cum = executionReport.cumQuantity();
    order.status = status;

    final BookAssignment booking = bookMapper.assign(venueId, instrumentId, clOrdId);
    final InstrumentReference ref = catalogue.get(instrumentId);
    addRecord(
        new OrderRecord(
            0, tradingDate(transact), "T3", eventType(status), buySell(side), "DEAL", false,
            booking.algorithmId(), clOrdId, venueOrderId, venueCode(venueId), "LMT",
            fill ? lastPrice : 0, ref.currency(), ref.priceNotation(),
            executionReport.leavesQuantity(), fill ? lastQty : 0, 0, transact,
            Long.toString(executionReport.execId()), "GDAY", ref.identifier(), instrumentId, 0,
            sequence, fill ? key.text() : ""));

    if (fill) {
      book(new Trade(key, venueOrderId, clOrdId, instrumentId, side, lastPrice, lastQty, transact,
          sequence, booking, TradeStatus.LIVE, 1, false));
    }
  }

  private void onQuoteRequest(final long sequence) {
    final VenueRef ref = new VenueRef(quoteRequest.venueId(), quoteRequest.rfqId());
    final RfqInfo info =
        new RfqInfo(quoteRequest.instrumentId(), quoteRequest.side(), quoteRequest.clientId(),
            quoteRequest.quantity());
    rfqs.put(ref, info);
    final InstrumentReference instrument = catalogue.get(info.instrumentId());
    addRecord(
        new OrderRecord(
            0, tradingDate(quoteRequest.receivedNanos()), "T2", "NEWO", buySell(info.clientSide()),
            "DEAL", true, "", quoteRequest.rfqId(), 0, venueCode(ref.venueId()), "RFQ", 0,
            instrument.currency(), instrument.priceNotation(), info.quantity(), 0,
            quoteRequest.receivedNanos(), quoteRequest.receivedNanos(), "", "GTDT",
            instrument.identifier(), info.instrumentId(), info.clientId(), sequence, ""));
  }

  private void onQuote(final long sequence) {
    final VenueRef ref = new VenueRef(quote.venueId(), quote.quoteId());
    final QuoteInfo info =
        new QuoteInfo(quote.venueId(), quote.quoteId(), quote.rfqId(), quote.instrumentId(),
            quote.bidPrice().mantissa(), quote.askPrice().mantissa(), quote.validUntilNanos());
    quotes.put(ref, info);
    final BookAssignment booking =
        bookMapper.assign(info.venueId(), info.instrumentId(), info.quoteId());
    final InstrumentReference instrument = catalogue.get(info.instrumentId());
    // A two-way quote is recorded as two order events, one per side.
    for (final Side side : new Side[] {Side.Buy, Side.Sell}) {
      addRecord(
          new OrderRecord(
              0, tradingDate(quote.sentNanos()), "T3", "NEWO", buySell(side), "DEAL", true,
              booking.algorithmId(), info.quoteId(), 0, venueCode(info.venueId()), "QUOTE",
              side == Side.Buy ? info.bid() : info.ask(), instrument.currency(),
              instrument.priceNotation(),
              side == Side.Buy ? quote.bidQuantity() : quote.askQuantity(), 0, quote.sentNanos(),
              0, "", "GTDT", instrument.identifier(), info.instrumentId(), 0, sequence, ""));
    }
  }

  private void onQuoteResponse(final long sequence) {
    final int venueId = quoteResponse.venueId();
    final long quoteId = quoteResponse.quoteId();
    final TradeKey key = new TradeKey(venueId, TradeSource.Rfq, quoteId);
    final QuoteStatus status = quoteResponse.status();
    final long price = quoteResponse.tradedPrice().mantissa();
    final long qty = quoteResponse.tradedQuantity();
    final long when = quoteResponse.timestampNanos();
    final RfqInfo rfq = rfqs.get(new VenueRef(venueId, quoteResponse.rfqId()));
    if (!seenQuoteOutcomes.add(key)) {
      final Trade existing = trades.get(key);
      final boolean same = existing == null || (existing.price() == price && existing.quantity() == qty);
      audit.add(
          new AuditEntry(
              sequence, lastTimestamp,
              same ? AuditKind.DUPLICATE_IGNORED : AuditKind.DUPLICATE_CONFLICT, key, 0,
              same ? "repeat of quote response" : "conflicting repeat: price=" + price + " qty=" + qty));
      return;
    }
    quotes.remove(new VenueRef(venueId, quoteId));
    if (rfq == null) {
      throw new IllegalStateException(
          "frame " + sequence + ": quote response for rfq " + quoteResponse.rfqId()
              + " on venue " + venueId + " without its QuoteRequest in the journal");
    }
    final Side ourSide = rfq.clientSide() == Side.Buy ? Side.Sell : Side.Buy;
    final BookAssignment booking = bookMapper.assign(venueId, rfq.instrumentId(), quoteId);
    final InstrumentReference instrument = catalogue.get(rfq.instrumentId());
    final boolean done = status == QuoteStatus.Done && qty > 0;
    addRecord(
        new OrderRecord(
            0, tradingDate(when), "T3",
            done ? "FILL" : status == QuoteStatus.Expired ? "EXPI" : "REMO",
            buySell(ourSide), "DEAL", true, booking.algorithmId(), quoteId, 0, venueCode(venueId),
            "QUOTE", done ? price : 0, instrument.currency(), instrument.priceNotation(), 0,
            done ? qty : 0, 0, when, done ? key.text() : "", "GTDT", instrument.identifier(),
            rfq.instrumentId(), rfq.clientId(), sequence, done ? key.text() : ""));
    if (done) {
      book(new Trade(key, 0, quoteId, rfq.instrumentId(), ourSide, price, qty, when, sequence,
          booking, TradeStatus.LIVE, 1, false));
    }
  }

  private void onBust(final long sequence) {
    bust(new TradeKey(tradeBust.venueId(), tradeBust.tradeSource(), tradeBust.tradeId()),
        tradeBust.reason().name(), sequence);
  }

  private void onVenueBust(final long sequence) {
    bust(new TradeKey(venueBust.venueId(), TradeSource.Execution, venueBust.execId()),
        BustReason.VenueError.name(), sequence);
  }

  private void bust(final TradeKey key, final String reason, final long sequence) {
    final Trade t = trades.get(key);
    if (t == null) {
      if (pendingBusts.putIfAbsent(key, sequence) == null) {
        audit.add(new AuditEntry(sequence, lastTimestamp, AuditKind.BUST_PENDING, key, 0,
            "bust before fill, reason=" + reason));
      } else {
        audit.add(new AuditEntry(sequence, lastTimestamp, AuditKind.DUPLICATE_BUST, key, 0, reason));
      }
      return;
    }
    applyBust(t, sequence, reason);
  }

  private void applyBust(final Trade t, final long sequence, final String reason) {
    if (!t.isLive()) {
      audit.add(new AuditEntry(sequence, lastTimestamp, AuditKind.DUPLICATE_BUST, t.key(), 0, reason));
      return;
    }
    replace(t.busted());
    audit.add(new AuditEntry(sequence, lastTimestamp, AuditKind.BUSTED, t.key(), 0, "reason=" + reason));
    final InstrumentReference instrument = catalogue.get(t.instrumentId());
    addRecord(
        new OrderRecord(
            0, tradingDate(t.transactNanos()), "T3", "BUST", buySell(t.side()), "DEAL",
            t.key().source() == TradeSource.Rfq, t.booking().algorithmId(), t.orderOrQuoteId(),
            t.venueOrderId(), venueCode(t.key().venueId()), "LMT", t.price(),
            instrument.currency(), instrument.priceNotation(), 0, t.quantity(), 0, lastTimestamp,
            Long.toString(t.key().tradeId()), "GDAY", instrument.identifier(), t.instrumentId(),
            0, sequence, t.key().text()));
  }

  private void onAmend(final long sequence) {
    final TradeKey key =
        new TradeKey(tradeAmend.venueId(), tradeAmend.tradeSource(), tradeAmend.tradeId());
    amend(key, tradeAmend.newPrice().mantissa(), tradeAmend.newQuantity(), tradeAmend.amendedBy(),
        tradeAmend.reason().name(), sequence);
  }

  private void amend(
      final TradeKey key,
      final long newPrice,
      final long newQuantity,
      final int actor,
      final String reason,
      final long sequence) {
    final Trade t = trades.get(key);
    if (t == null || !t.isLive() || newQuantity <= 0) {
      audit.add(new AuditEntry(sequence, lastTimestamp, AuditKind.AMEND_REJECTED, key, actor,
          t == null ? "unknown trade" : !t.isLive() ? "trade is busted" : "quantity " + newQuantity));
      return;
    }
    final Trade next = t.amended(newPrice, newQuantity, sequence);
    replace(next);
    audit.add(new AuditEntry(sequence, lastTimestamp, AuditKind.AMENDED, key, actor,
        "reason=" + reason + " price " + t.price() + "->" + newPrice + " qty " + t.quantity() + "->"
            + newQuantity + " version " + next.version()));
    final InstrumentReference instrument = catalogue.get(t.instrumentId());
    addRecord(
        new OrderRecord(
            0, tradingDate(t.transactNanos()), "T3", "AMND", buySell(t.side()), "DEAL",
            key.source() == TradeSource.Rfq, t.booking().algorithmId(), t.orderOrQuoteId(),
            t.venueOrderId(), venueCode(key.venueId()), "LMT", newPrice, instrument.currency(),
            instrument.priceNotation(), 0, newQuantity, 0, lastTimestamp,
            Long.toString(key.tradeId()), "GDAY", instrument.identifier(), t.instrumentId(), 0,
            sequence, key.text()));
  }

  private void book(final Trade raw) {
    final PositionKey pk = raw.positionKey();
    final Long latest = maxTransact.get(pk);
    final boolean late = latest != null && raw.transactNanos() < latest;
    final Trade t =
        late
            ? new Trade(raw.key(), raw.venueOrderId(), raw.orderOrQuoteId(), raw.instrumentId(),
                raw.side(), raw.price(), raw.quantity(), raw.transactNanos(), raw.journalSequence(),
                raw.booking(), raw.status(), raw.version(), true)
            : raw;
    maxTransact.merge(pk, t.transactNanos(), Math::max);
    positionKeys.add(pk);
    replace(t);
    audit.add(new AuditEntry(t.journalSequence(), lastTimestamp, AuditKind.BOOKED, t.key(), 0,
        pk.text() + " " + t.side() + " " + t.quantity() + "@" + t.price()));
    if (late) {
      audit.add(new AuditEntry(t.journalSequence(), lastTimestamp, AuditKind.LATE_FILL, t.key(), 0,
          "transact " + t.transactNanos() + " before latest " + latest));
    }
    final Long bustSequence = pendingBusts.remove(t.key());
    if (bustSequence != null) {
      applyBust(t, bustSequence, "pending");
    }
  }

  private void replace(final Trade t) {
    trades.put(t.key(), t);
    versions.computeIfAbsent(t.key(), k -> new ArrayList<>()).add(t);
    cache.remove(t.positionKey());
  }

  private void addRecord(final OrderRecord unsequenced) {
    final OrderRecord r =
        new OrderRecord(
            records.size() + 1L, unsequenced.tradingDate(), unsequenced.table(),
            unsequenced.eventType(), unsequenced.buySell(), unsequenced.tradingCapacity(),
            unsequenced.liquidityProvision(), unsequenced.executionWithinFirm(),
            unsequenced.internalOrderId(), unsequenced.venueOrderId(), unsequenced.orderReceiver(),
            unsequenced.orderType(), unsequenced.limitPrice(), unsequenced.priceCurrency(),
            unsequenced.priceNotation(), unsequenced.remainingQuantity(),
            unsequenced.tradedQuantity(), unsequenced.submissionNanos(),
            unsequenced.receiptNanos(), unsequenced.venueTransactionId(),
            unsequenced.validityPeriod(), unsequenced.instrumentIdentifier(),
            unsequenced.instrumentId(), unsequenced.clientId(), unsequenced.journalSequence(),
            unsequenced.tradeKey());
    records.add(r);
    if (r.venueOrderId() != 0) {
      recordsByVenueOrderId.computeIfAbsent(r.venueOrderId(), k -> new ArrayList<>()).add(r);
    }
  }

  // ---------------------------------------------------------------- queries

  /** The position of one key, recomputed from live trades if anything changed since last asked. */
  public Position position(final PositionKey key) {
    return cache.computeIfAbsent(
        key,
        k -> {
          final List<Trade> live = new ArrayList<>();
          for (final Trade t : trades.values()) {
            if (t.isLive() && t.positionKey().equals(k)) {
              live.add(t);
            }
          }
          live.sort(TRANSACTION_ORDER);
          return PositionCalculator.compute(k, openings.get(k), live, convention);
        });
  }

  /** Every position key with a trade or an opening position, in key order. */
  public List<PositionKey> positionKeys() {
    return List.copyOf(positionKeys);
  }

  /** Every position, in key order. */
  public List<Position> positions() {
    final List<Position> out = new ArrayList<>();
    for (final PositionKey k : positionKeys) {
      out.add(position(k));
    }
    return out;
  }

  /** The current version of a trade, or null. */
  public Trade trade(final TradeKey key) {
    return trades.get(key);
  }

  /** Current versions of every trade, live and busted, in transaction-time order. */
  public List<Trade> trades() {
    final List<Trade> all = new ArrayList<>(trades.values());
    all.sort(TRANSACTION_ORDER);
    return all;
  }

  /** Every version of one trade, oldest first. */
  public List<Trade> versions(final TradeKey key) {
    return List.copyOf(versions.getOrDefault(key, List.of()));
  }

  /** The audit trail, in the order the keeper made its decisions. */
  public List<AuditEntry> audit() {
    return List.copyOf(audit);
  }

  /** Audit entries of one kind. */
  public List<AuditEntry> audit(final AuditKind kind) {
    return audit.stream().filter(a -> a.kind() == kind).toList();
  }

  /** Busts still waiting for their fill. A non-empty answer at end of day is a break. */
  public Set<TradeKey> pendingBusts() {
    return Set.copyOf(pendingBusts.keySet());
  }

  /** Every order record, in record-sequence order. */
  public List<OrderRecord> orderRecords() {
    return List.copyOf(records);
  }

  /** Order records for one venue order id, in record-sequence order. */
  public List<OrderRecord> recordsByVenueOrderId(final long venueOrderId) {
    return List.copyOf(recordsByVenueOrderId.getOrDefault(venueOrderId, List.of()));
  }

  /** Orders the journal says are still working at their venues. */
  public List<OpenOrder> openOrders() {
    final List<OpenOrder> out = new ArrayList<>();
    for (final OrderState o : orders.values()) {
      if (o.isOpen()) {
        out.add(new OpenOrder(o.venueId, o.clOrdId, o.venueOrderId, o.instrumentId, o.leaves, o.cum));
      }
    }
    return out;
  }

  /** Quotes with no outcome yet whose validity extends past {@code nowNanos}. */
  public List<LiveQuote> liveQuotes(final long nowNanos) {
    final List<LiveQuote> out = new ArrayList<>();
    for (final QuoteInfo q : quotes.values()) {
      if (q.validUntil() > nowNanos) {
        out.add(new LiveQuote(q.venueId(), q.quoteId(), q.rfqId(), q.instrumentId(), q.validUntil()));
      }
    }
    out.sort(Comparator.comparingInt(LiveQuote::venueId).thenComparingLong(LiveQuote::quoteId));
    return out;
  }

  /** Sequence of the last frame seen, 0 if none. */
  public long lastSequence() {
    return lastSequence;
  }

  /** Timestamp of the last frame seen. */
  public long lastTimestampNanos() {
    return lastTimestamp;
  }

  // ---------------------------------------------------------------- helpers

  private static String tradingDate(final long epochNanos) {
    return LocalDate.ofInstant(
            Instant.ofEpochSecond(Math.floorDiv(epochNanos, 1_000_000_000L)), ZoneOffset.UTC)
        .toString();
  }

  private static String buySell(final Side side) {
    return side == Side.Buy ? "BUYI" : "SELL";
  }

  private static String venueCode(final int venueId) {
    return "VENUE-" + venueId;
  }

  private static String validity(final TimeInForce tif) {
    return switch (tif) {
      case ImmediateOrCancel -> "IOCA";
      case FillOrKill -> "FOKV";
      case GoodTillDate -> "GTDT";
      case Day, NULL_VAL -> "GDAY";
    };
  }

  private static String eventType(final OrdStatus status) {
    return switch (status) {
      case New -> "NEWO";
      case PartiallyFilled -> "PARF";
      case Filled -> "FILL";
      case Cancelled -> "CAME";
      case Rejected, NULL_VAL -> "REMO";
    };
  }

  /** A venue-scoped id: an rfqId or a quoteId. */
  private record VenueRef(int venueId, long id) {}

  private record RfqInfo(int instrumentId, Side clientSide, int clientId, long quantity) {}

  private record QuoteInfo(
      int venueId, long quoteId, long rfqId, int instrumentId, long bid, long ask, long validUntil) {}

  private static final class OrderState {
    private final int venueId;
    private final long clOrdId;
    private final int instrumentId;
    private long venueOrderId;
    private long leaves;
    private long cum;
    private OrdStatus status = OrdStatus.New;

    OrderState(final int venueId, final long clOrdId, final int instrumentId, final Side side,
        final long quantity) {
      this.venueId = venueId;
      this.clOrdId = clOrdId;
      this.instrumentId = instrumentId;
      this.leaves = quantity;
      if (side == null) {
        throw new IllegalArgumentException("order " + clOrdId + " without a side");
      }
    }

    boolean isOpen() {
      return leaves > 0 && (status == OrdStatus.New || status == OrdStatus.PartiallyFilled);
    }
  }
}
