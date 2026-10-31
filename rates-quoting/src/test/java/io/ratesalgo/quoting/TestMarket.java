package io.ratesalgo.quoting;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.InMemoryBookSource;
import io.ratesalgo.api.InMemoryFairValueSource;
import io.ratesalgo.api.InMemoryInstrumentDirectory;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.OutboundSink;
import io.ratesalgo.api.ParametricSensitivityProvider;
import io.ratesalgo.api.ProductType;
import io.ratesalgo.api.RecordingHedgeOrderSink;
import io.ratesalgo.api.RecordingOutboundSink;
import io.ratesalgo.api.HedgeOrderSink;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.hedge.HedgeUniverse;
import io.ratesalgo.hedge.Hedger;
import io.ratesalgo.hedge.TableHedgeCostModel;
import io.ratesalgo.risk.RiskLimits;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/** A small USD and EUR market over the rates-api stubs, and a desk wired to it. */
final class TestMarket {

  /** 2026-01-01T12:00:00Z, inside the USD liquid session. */
  static final long T0 = 1_767_268_800_000_000_000L;

  static final int UST2 = 101;
  static final int UST5 = 102;
  static final int UST10 = 103;
  static final int UST30 = 104;
  static final int TU = 201;
  static final int FV = 202;
  static final int TY = 203;
  static final int US = 204;
  static final int SW2 = 301;
  static final int SW5 = 302;
  static final int SW10 = 303;
  static final int SW30 = 304;
  static final int BUND10 = 111;
  static final int RX = 211;
  static final int ESTR10 = 311;

  static final int[] ALL = {UST2, UST5, UST10, UST30, TU, FV, TY, US, SW2, SW5, SW10, SW30, BUND10, RX, ESTR10};
  static final int[] RFQ_INSTRUMENTS = {UST2, UST5, UST10, UST30, SW2, SW5, SW10, SW30, BUND10, ESTR10};

  static final long FACE_1M = 100_000_000L;

  final ManualClock clock = new ManualClock(T0);
  final InMemoryInstrumentDirectory directory = new InMemoryInstrumentDirectory();
  final ParametricSensitivityProvider sensitivities;
  final InMemoryFairValueSource fair = new InMemoryFairValueSource();
  final InMemoryBookSource book = new InMemoryBookSource(64);
  final HedgeUniverse universe = new HedgeUniverse();
  final TableHedgeCostModel costs = new TableHedgeCostModel(32);
  final RecordingOutboundSink gateway = new RecordingOutboundSink();
  final RecordingOutboundSink control = new RecordingOutboundSink();
  final RecordingHedgeOrderSink hedgeSink = new RecordingHedgeOrderSink();
  final double[] level = new double[ALL.length];
  final Desk desk;

  TestMarket() {
    this(standardLimits(), new QuotingConfig(), Hedger.config(), null, null);
  }

  TestMarket(final RiskLimits limits, final QuotingConfig config, final Hedger.Config hedgerConfig) {
    this(limits, config, hedgerConfig, null, null);
  }

  TestMarket(
      final RiskLimits limits,
      final QuotingConfig config,
      final Hedger.Config hedgerConfig,
      final OutboundSink gatewayOverride,
      final HedgeOrderSink hedgeOverride) {
    add(Instrument.bond(UST2, "UST2", CurrencyCode.USD, 2.0, 25 * FACE_1M), 99.75);
    add(Instrument.bond(UST5, "UST5", CurrencyCode.USD, 5.0, 25 * FACE_1M), 99.50);
    add(Instrument.bond(UST10, "UST10", CurrencyCode.USD, 10.0, 25 * FACE_1M), 98.25);
    add(Instrument.bond(UST30, "UST30", CurrencyCode.USD, 30.0, 10 * FACE_1M), 96.50);
    add(Instrument.future(TU, "TU", CurrencyCode.USD, 2.0, 50 * FACE_1M), 103.20);
    add(Instrument.future(FV, "FV", CurrencyCode.USD, 5.0, 50 * FACE_1M), 108.40);
    add(Instrument.future(TY, "TY", CurrencyCode.USD, 7.0, 50 * FACE_1M), 111.10);
    add(Instrument.future(US, "US", CurrencyCode.USD, 20.0, 20 * FACE_1M), 117.30);
    add(Instrument.swap(SW2, "SOFR2", CurrencyCode.USD, 2.0, 1.9, TU, 0.9, 50 * FACE_1M), 3.80);
    add(Instrument.swap(SW5, "SOFR5", CurrencyCode.USD, 5.0, 4.5, FV, 0.9, 50 * FACE_1M), 3.70);
    add(Instrument.swap(SW10, "SOFR10", CurrencyCode.USD, 10.0, 8.2, TY, 0.9, 50 * FACE_1M), 3.85);
    add(Instrument.swap(SW30, "SOFR30", CurrencyCode.USD, 30.0, 17.0, US, 0.9, 25 * FACE_1M), 3.95);
    add(Instrument.bond(BUND10, "DBR10", CurrencyCode.EUR, 10.0, 25 * FACE_1M), 99.10);
    add(Instrument.future(RX, "RX", CurrencyCode.EUR, 9.0, 50 * FACE_1M), 129.50);
    add(Instrument.swap(ESTR10, "ESTR10", CurrencyCode.EUR, 10.0, 8.5, RX, 0.9, 50 * FACE_1M), 2.65);
    sensitivities = new ParametricSensitivityProvider(directory);
    final double[] duration = {1.9, 4.5, 8.2, 17.0, 1.9, 4.3, 6.5, 13.0, 1.9, 4.5, 8.2, 17.0, 8.6, 7.8, 8.5};
    for (int n = 0; n < ALL.length; n++) {
      final Instrument inst = directory.byId(ALL[n]);
      final double sign = inst.type() == ProductType.SWAP ? 1.0 : -1.0;
      sensitivities.defineAtTenor(ALL[n], sign * duration[n] * 1e-4, 0.002);
    }
    final long contract = 10_000_000L;
    universe.add(TU, contract, 5_000).add(FV, contract, 5_000).add(TY, contract, 5_000).add(US, contract, 3_000);
    universe.add(SW2, FACE_1M, 2_000).add(SW5, FACE_1M, 2_000).add(SW10, FACE_1M, 2_000).add(SW30, FACE_1M, 1_000);
    universe.add(RX, contract, 5_000).add(ESTR10, FACE_1M, 2_000);
    costs.set(TU, 4.0, 1.0, 0.002).set(FV, 6.0, 1.0, 0.002).set(TY, 8.0, 1.0, 0.002).set(US, 16.0, 2.0, 0.004);
    costs.set(SW2, 19.0, 0.0, 0.01).set(SW5, 45.0, 0.0, 0.02).set(SW10, 82.0, 0.0, 0.03).set(SW30, 170.0, 0.0, 0.05);
    costs.set(RX, 10.0, 1.0, 0.002).set(ESTR10, 85.0, 0.0, 0.03);
    refreshAll(clock.epochNanos());
    final HitRatePreset preset;
    try {
      preset = HitRatePreset.shippedDefault();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    desk =
        new Desk(
            new Desk.Inputs(
                directory,
                sensitivities,
                fair,
                book,
                limits,
                universe,
                costs,
                gatewayOverride == null ? gateway : gatewayOverride,
                control,
                hedgeOverride == null ? hedgeSink : hedgeOverride,
                clock,
                config,
                tierTable(1),
                preset,
                hedgerConfig,
                ClobQuoter.config()));
  }

  static RiskLimits standardLimits() {
    return new RiskLimits()
        .defaultMaxPosition(500 * FACE_1M)
        .bucketPv01(250_000.0)
        .netPv01(400_000.0)
        .grossPv01(1_500_000.0)
        .dailyLoss(5_000_000.0)
        .quoteRatePerSecond(2_000.0)
        .orderRatePerSecond(500.0)
        .messageRatePerSecond(5_000.0)
        .maxOrderSize(200 * FACE_1M)
        .fatFingerQuantity(250 * FACE_1M)
        .fatFingerFraction(0.05)
        .priceCollarBp(15.0);
  }

  /** Tier 1 tight, tier 2 default, tier 3 subject, tier 4 blocked; clients 1 to 4. */
  static TierTable tierTable(final long version) {
    return new TierTable(
        version,
        List.of(
            new ClientTier(1, 0.8, 500 * FACE_1M, true, false),
            new ClientTier(2, 1.0, 250 * FACE_1M, true, false),
            new ClientTier(3, 1.5, 100 * FACE_1M, false, false),
            new ClientTier(4, 1.0, FACE_1M, false, true)),
        Map.of(1, 0, 2, 1, 3, 2, 4, 3),
        1);
  }

  private void add(final Instrument inst, final double initialLevel) {
    final int i = directory.add(inst);
    level[i] = initialLevel;
  }

  /** Re-stamps every fair value and book at {@code now}. */
  void refreshAll(final long now) {
    for (final int id : ALL) {
      refresh(id, now);
    }
  }

  /** Re-stamps one instrument's fair value and, for bonds and futures, its book. */
  void refresh(final int id, final long now) {
    final int i = directory.indexOf(id);
    final Instrument inst = directory.byId(id);
    fair.set(id, Decimal9.round(level[i]), now);
    if (inst.type() != ProductType.SWAP) {
      final double half = 1.0 / 128.0;
      book.set(id, Decimal9.round(level[i] - half), 50 * FACE_1M, Decimal9.round(level[i] + half), 50 * FACE_1M, now);
    }
  }

  /** Moves every curve of {@code ccy} by {@code bp}, repricing fair values and the risk engine. */
  void shift(final CurrencyCode ccy, final double bp) {
    sensitivities.shiftCurve(ccy, bp);
    final long now = clock.epochNanos();
    for (final int id : ALL) {
      final Instrument inst = directory.byId(id);
      if (inst.currency() != ccy) {
        continue;
      }
      final int i = directory.indexOf(id);
      if (inst.type() == ProductType.SWAP) {
        level[i] += bp * 0.01;
      } else {
        level[i] -= bp * desk.risk().quotePerBp(id);
      }
      refresh(id, now);
    }
    desk.risk().onCurveMove(ccy);
    for (final int id : ALL) {
      if (directory.byId(id).currency() == ccy) {
        desk.risk().onFairValue(id, fair.fairMid(id));
      }
    }
  }

  double levelOf(final int id) {
    return level[directory.indexOf(id)];
  }
}
