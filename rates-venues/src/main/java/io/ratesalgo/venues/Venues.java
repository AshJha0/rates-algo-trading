package io.ratesalgo.venues;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.refdata.RefData;
import io.ratesalgo.venues.clob.ClobConfig;
import io.ratesalgo.venues.clob.ClobSimulator;
import io.ratesalgo.venues.d2c.D2cConfig;
import io.ratesalgo.venues.d2c.D2cRfqSimulator;
import io.ratesalgo.venues.feed.FeedChannel;
import io.ratesalgo.venues.sef.SefConfig;
import io.ratesalgo.venues.sef.SefSimulator;
import io.ratesalgo.venues.sim.Instrument;
import io.ratesalgo.venues.sim.Instruments;
import io.ratesalgo.venues.sim.VenueSimulator;
import java.time.LocalDate;
import java.util.List;

/**
 * Builds the three simulators with their default presets and reference data instruments. The
 * engine counterparties are {@code RALGO} (this desk) and {@code RALGO2} (a second session, used
 * by self-match and multi-participant tests).
 */
public final class Venues {

  /** Engine CompIDs every simulator accepts. */
  public static final List<String> ENGINES = List.of("RALGO", "RALGO2");

  /** Trading date of the fixtures used by the simulators: 2026-11-02. */
  public static final LocalDate TRADE_DATE = LocalDate.of(2026, 11, 2);

  /** 2026-11-02T13:30:00Z, which is 08:30 in New York: the start time the scripts use. */
  public static final long T0 = 1_793_629_800_000_000_000L;

  private Venues() {}

  /** On-the-run Treasuries on {@link #TRADE_DATE}. */
  public static List<Instrument> treasuries() {
    return Instruments.treasuries(RefData.loadDefault(), TRADE_DATE);
  }

  /** USD SOFR swap templates. */
  public static List<Instrument> swaps() {
    return Instruments.sofrSwaps(RefData.loadDefault());
  }

  /** Creates a simulator of a kind: {@code d2c}, {@code clob} or {@code sef}. */
  public static VenueSimulator create(
      final String kind, final Clock clock, final long seed, final FeedChannel feed) {
    return switch (kind) {
      case "d2c" -> {
        final List<Instrument> all = new java.util.ArrayList<>(treasuries());
        all.addAll(swaps());
        yield new D2cRfqSimulator(D2cConfig.defaults(), all, ENGINES, clock, seed);
      }
      case "clob" -> new ClobSimulator("CLOB", ClobConfig.defaults(), treasuries(), ENGINES, feed, clock, seed);
      case "sef" -> new SefSimulator(SefConfig.defaults(), swaps(), ENGINES, feed, clock, seed);
      default -> throw new IllegalArgumentException("unknown venue kind " + kind);
    };
  }
}
