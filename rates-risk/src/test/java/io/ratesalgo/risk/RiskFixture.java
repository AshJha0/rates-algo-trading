package io.ratesalgo.risk;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.InMemoryFairValueSource;
import io.ratesalgo.api.InMemoryInstrumentDirectory;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.ParametricSensitivityProvider;
import io.ratesalgo.api.RecordingOutboundSink;
import io.ratesalgo.common.time.ManualClock;

/** Four USD instruments and one EUR bond over the stubs, and a risk engine. */
final class RiskFixture {

  static final int BOND10 = 1;
  static final int FUT10 = 2;
  static final int SWAP10 = 3;
  static final int BOND2 = 4;
  static final int BUND = 5;
  static final int[] ALL = {BOND10, FUT10, SWAP10, BOND2, BUND};
  static final long M = 100_000_000L; // one million in minor units

  final ManualClock clock = new ManualClock(1_767_268_800_000_000_000L);
  final InMemoryInstrumentDirectory directory = new InMemoryInstrumentDirectory();
  final ParametricSensitivityProvider sensitivities;
  final InMemoryFairValueSource fair = new InMemoryFairValueSource();
  final RecordingOutboundSink alerts = new RecordingOutboundSink();
  final KillSwitchController kill;
  final RiskLimits limits;
  final RiskEngine risk;

  RiskFixture(final RiskLimits limits) {
    directory.add(Instrument.bond(BOND10, "B10", CurrencyCode.USD, 10.0, 25 * M));
    directory.add(Instrument.future(FUT10, "F10", CurrencyCode.USD, 7.0, 50 * M));
    directory.add(Instrument.swap(SWAP10, "S10", CurrencyCode.USD, 10.0, 8.2, FUT10, 0.9, 50 * M));
    directory.add(Instrument.bond(BOND2, "B2", CurrencyCode.USD, 2.0, 25 * M));
    directory.add(Instrument.bond(BUND, "DBR", CurrencyCode.EUR, 10.0, 25 * M));
    sensitivities = new ParametricSensitivityProvider(directory);
    sensitivities.defineAtTenor(BOND10, -8.2e-4, 0.002);
    sensitivities.defineAtTenor(FUT10, -6.5e-4, 0.002);
    sensitivities.defineAtTenor(SWAP10, 8.2e-4, 0.002);
    sensitivities.defineAtTenor(BOND2, -1.9e-4, 0.001);
    sensitivities.defineAtTenor(BUND, -8.6e-4, 0.002);
    fair.set(BOND10, Decimal9.round(98.25), clock.epochNanos());
    fair.set(FUT10, Decimal9.round(111.10), clock.epochNanos());
    fair.set(SWAP10, Decimal9.round(3.85), clock.epochNanos());
    fair.set(BOND2, Decimal9.round(99.75), clock.epochNanos());
    fair.set(BUND, Decimal9.round(99.10), clock.epochNanos());
    this.limits = limits;
    kill = new KillSwitchController(alerts, clock);
    risk = new RiskEngine(directory, sensitivities, fair, limits, kill, alerts, clock);
  }

  RiskFixture() {
    this(new RiskLimits());
  }

  long fairMid(final int id) {
    return fair.fairMid(id);
  }
}
