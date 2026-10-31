package io.ratesalgo.cookbook;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.InMemoryBookSource;
import io.ratesalgo.api.InMemoryFairValueSource;
import io.ratesalgo.api.InMemoryInstrumentDirectory;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.ParametricSensitivityProvider;
import io.ratesalgo.api.RecordingHedgeOrderSink;
import io.ratesalgo.api.RecordingOutboundSink;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.hedge.HedgeUniverse;
import io.ratesalgo.hedge.Hedger;
import io.ratesalgo.hedge.TableHedgeCostModel;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.QuoteWithdrawDecoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.quoting.ClientTier;
import io.ratesalgo.quoting.ClobQuoter;
import io.ratesalgo.quoting.Desk;
import io.ratesalgo.quoting.HitRatePreset;
import io.ratesalgo.quoting.InputHealth;
import io.ratesalgo.quoting.QuotingConfig;
import io.ratesalgo.quoting.QuotingEngine;
import io.ratesalgo.quoting.RfqOutcome;
import io.ratesalgo.quoting.TierTable;
import io.ratesalgo.risk.RiskLimits;
import java.io.IOException;
import java.util.Locale;

/** COOKBOOK.md recipe 28: the quote lifecycle controls that pull stale quotes. */
public final class Recipe28QuotePulls {

  private Recipe28QuotePulls() {}

  static final long T0 = 1_767_268_800_000_000_000L; // 2026-01-01T12:00Z
  static final long MS = 1_000_000L;
  static final long FACE_25M = 2_500_000_000L;

  public static void main(final String[] args) throws IOException {
    final ManualClock clock = new ManualClock(T0);
    final InMemoryInstrumentDirectory instruments = new InMemoryInstrumentDirectory();
    instruments.add(Instrument.bond(103, "UST10", CurrencyCode.USD, 10.0, FACE_25M));
    final ParametricSensitivityProvider pv01 = new ParametricSensitivityProvider(instruments);
    pv01.defineAtTenor(103, -8.2e-4, 0.0);
    final InMemoryFairValueSource fair = new InMemoryFairValueSource();
    final RecordingOutboundSink gateway = new RecordingOutboundSink();
    final MessageHeaderDecoder header = new MessageHeaderDecoder();
    final QuoteWithdrawDecoder withdraw = new QuoteWithdrawDecoder();
    gateway.listener((templateId, buffer, offset, length) -> {
      if (templateId == QuoteWithdrawDecoder.TEMPLATE_ID) {
        withdraw.wrapAndApplyHeader(buffer, offset, header);
        System.out.println(String.format(Locale.ROOT, "  t=%5d ms  QuoteWithdraw quote %d reason %s",
            (withdraw.sentNanos() - T0) / MS, withdraw.quoteId(), withdraw.reason()));
      }
    });
    // a one-second time to live and a two-second limit on the age of the inputs a quote used
    final QuotingConfig config = new QuotingConfig().firmTtlNanos(1_000 * MS).maxInputAgeNanos(2_000 * MS);
    final Desk desk = new Desk(new Desk.Inputs(instruments, pv01, fair, new InMemoryBookSource(4),
        new RiskLimits().bucketPv01(250_000.0), new HedgeUniverse(), new TableHedgeCostModel(1), gateway,
        new RecordingOutboundSink(), new RecordingHedgeOrderSink(), clock, config,
        TierTable.single(1, new ClientTier(1, 1.0, 10_000_000_000L, true, false)),
        HitRatePreset.shippedDefault(), Hedger.config(), ClobQuoter.config()));
    final QuotingEngine engine = desk.quoting();
    final InputHealth health = desk.health();
    health.trackVenue(7);

    System.out.println("1. time to live: the quote lapses at its validUntil, no message is sent");
    fair.set(103, Decimal9.round(98.25), clock.epochNanos());
    rfq(engine, clock, 1);
    clock.advance(1_000 * MS);
    engine.poll(clock.epochNanos());
    System.out.println("  t= 1000 ms  live " + engine.liveQuotes() + ", expiries " + engine.expiries());

    System.out.println("2. stale reference: quoted from a 1.5 s old fair value, pulled when it passes 2 s");
    clock.advance(500 * MS);
    rfq(engine, clock, 2);
    clock.advance(400 * MS);
    engine.poll(clock.epochNanos());
    System.out.println("  t= 1900 ms  inputs 1.9 s old, live " + engine.liveQuotes());
    clock.advance(200 * MS);
    engine.poll(clock.epochNanos());

    System.out.println("3. feed gap: book sequence 101, 102, 105 on venue 7 pulls every quote");
    fair.set(103, Decimal9.round(98.26), clock.epochNanos());
    clock.advance(300 * MS);
    rfq(engine, clock, 3);
    health.onBookSequence(7, 101);
    health.onBookSequence(7, 102);
    health.onBookSequence(7, 105);
    clock.advance(1 * MS);
    engine.poll(clock.epochNanos());
    rfq(engine, clock, 4);
    // the book is rebuilt from a snapshot; the re-quote throttle (250 ms) then lets the client in again
    health.onFeedRecovered(7);
    clock.advance(300 * MS);
    fair.set(103, Decimal9.round(98.26), clock.epochNanos());
    rfq(engine, clock, 5);
    System.out.println(String.format(Locale.ROOT, "rfqs %d quotes %d passes %d expiries %d pulls %d",
        engine.rfqs(), engine.quotesSent(), engine.passes(), engine.expiries(), engine.pulls()));
  }

  private static void rfq(final QuotingEngine engine, final ManualClock clock, final long rfqId) {
    engine.onQuoteRequest(7, rfqId, 42, 103, Side.Buy, FACE_25M, 4, 0L, rfqId);
    final RfqOutcome o = engine.outcome();
    System.out.println(String.format(Locale.ROOT, "  t=%5d ms  rfq %d: %s %s%s", (clock.epochNanos() - T0) / MS,
        rfqId, o.decision(), o.reason(), o.quoteId() == 0 ? ""
            : String.format(Locale.ROOT, " quote %d valid to t=%d ms", o.quoteId(), (o.validUntilNanos() - T0) / MS)));
  }
}
