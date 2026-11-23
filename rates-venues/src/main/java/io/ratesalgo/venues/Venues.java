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

  /**
   * The start time the scripts use: 2026-11-02T14:30:00Z, which is 09:30 in New York. Daylight
   * saving ended on 1 November 2026, so New York is five hours behind UTC on this date; earlier
   * comments read this value as 13:30Z and 08:30. The value is unchanged.
   */
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

  /**
   * The venues of one simulator v2 session, not yet started: a D2C RFQ venue, one Treasury order
   * book per share in the parameter file (two at the committed values) and a futures order book.
   * All of them hold the same session, so there is one market.
   *
   * @param session the shared session
   * @param d2c RFQ venue for swaps and Treasuries
   * @param treasuryBooks Treasury order-book venues, each showing its share of the model's book
   * @param futuresBook the futures order book
   */
  public record SimV2(
      io.ratesalgo.venues.sim.SimV2Session session,
      D2cRfqSimulator d2c,
      List<ClobSimulator> treasuryBooks,
      ClobSimulator futuresBook) {

    public SimV2 {
      treasuryBooks = List.copyOf(treasuryBooks);
    }

    /** Starts the RFQ flow and the model's liquidity on every book. */
    public void startFlow() {
      d2c.startAutoFlow();
      for (final ClobSimulator c : treasuryBooks) {
        c.startLiquidity();
      }
      futuresBook.startLiquidity();
    }

    /** Runs every event due at the clock's time on every venue. */
    public void onTime() {
      d2c.onTime();
      for (final ClobSimulator c : treasuryBooks) {
        c.onTime();
      }
      futuresBook.onTime();
    }

    /** Monotonic time of the next event on any venue. */
    public long nextEventNanos() {
      long next = Math.min(d2c.nextEventNanos(), futuresBook.nextEventNanos());
      for (final ClobSimulator c : treasuryBooks) {
        next = Math.min(next, c.nextEventNanos());
      }
      return next;
    }
  }

  /**
   * Builds the simulator v2 venues on a session that opens at the clock's current time (07:00 New
   * York of the simulated day). {@code feeds} gives one feed channel per order-book venue:
   * Treasury books first, then the futures book.
   */
  public static SimV2 simV2(
      final io.ratesalgo.sim.MarketModel model, final Clock clock, final List<FeedChannel> feeds) {
    final io.ratesalgo.sim.SimParams p = model.spec().params();
    final io.ratesalgo.venues.sim.SimV2Session session =
        new io.ratesalgo.venues.sim.SimV2Session(
            model, io.ratesalgo.venues.sim.SimV2Session.instruments(model.spec(), RefData.loadDefault(), TRADE_DATE), clock);
    final ClobConfig config = ClobConfig.preset("sim_v2");
    final List<ClobSimulator> treasuryBooks = new java.util.ArrayList<>();
    if (feeds.size() != p.treasuryVenues() + 1) {
      throw new IllegalArgumentException("one feed channel per order-book venue is required: " + (p.treasuryVenues() + 1));
    }
    for (int v = 0; v < p.treasuryVenues(); v++) {
      treasuryBooks.add(
          new ClobSimulator(
              simV2TreasuryVenueId(v), "CLOB" + (v + 1), config, session, session.instruments(io.ratesalgo.sim.SimInstrument.Kind.TREASURY),
              p.treasuryVenueShare(v), ENGINES, feeds.get(v), clock));
    }
    final ClobSimulator futures =
        new ClobSimulator(
            simV2FuturesVenueId(p.treasuryVenues()), "FUT", config, session, session.instruments(io.ratesalgo.sim.SimInstrument.Kind.FUTURE), 1.0, ENGINES,
            feeds.get(p.treasuryVenues()), clock);
    return new SimV2(session, new D2cRfqSimulator(session, ENGINES, clock), treasuryBooks, futures);
  }

  /** Feed venue id of simulator v2 Treasury order book {@code v}: 102, 103, ... */
  public static int simV2TreasuryVenueId(final int v) {
    return ClobSimulator.VENUE_ID + v;
  }

  /** Feed venue id of the simulator v2 futures order book: the one after the Treasury books'. */
  public static int simV2FuturesVenueId(final int treasuryVenues) {
    return ClobSimulator.VENUE_ID + treasuryVenues;
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
