package io.ratesalgo.app.sim;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.sim.MarketModel;
import io.ratesalgo.sim.MarketSpec;
import io.ratesalgo.sim.SessionSeeds;
import io.ratesalgo.sim.SimParams;
import io.ratesalgo.venues.Venues;
import io.ratesalgo.venues.clob.ClobSimulator;
import io.ratesalgo.venues.d2c.D2cRfqSimulator;
import io.ratesalgo.venues.feed.FeedChannel;
import io.ratesalgo.venues.feed.InProcessFeedChannel;
import io.ratesalgo.venues.sim.SimV2Session;
import io.ratesalgo.venues.sim.VenueHarness;
import java.util.ArrayList;
import java.util.List;

/**
 * The simulator v2 venues started in this process on free local ports, on a shared clock: the D2C
 * RFQ venue, the Treasury order books and the futures order book of one session of the shared
 * market model (docs/research/SIM_V2_DESIGN.md section 10), each order book with its feed on an
 * in-process channel.
 *
 * <p>This is the simulator v2 counterpart of {@link SimulatedVenues}. It needs no
 * {@link ClobLiquidity}: the books' liquidity, the other takers and the impact of the desk's own
 * orders come from the model, and the step size of the driver no longer changes the market. The
 * trading process is not wired to it yet; {@link SimulatedVenues} stays the default so that the
 * recorded smoke figures do not change.
 */
public final class SimulatedVenuesV2 implements AutoCloseable {

  private final Clock clock;
  private final Venues.SimV2 venues;
  private final List<InProcessFeedChannel> feeds = new ArrayList<>();

  /**
   * Builds and starts the venues for the session with this seed. The session opens at the clock's
   * current time, which stands for 07:00 New York; the day type follows from the seed.
   */
  public SimulatedVenuesV2(final Clock clock, final long seed) {
    this.clock = clock;
    final MarketSpec spec = new MarketSpec(SimParams.loadDefault());
    final MarketModel model = SessionSeeds.session(spec, seed);
    for (int v = 0; v <= spec.params().treasuryVenues(); v++) {
      feeds.add(new InProcessFeedChannel());
    }
    this.venues = Venues.simV2(model, clock, new ArrayList<FeedChannel>(feeds));
    venues.d2c().start(VenueHarness.freePort());
    for (final ClobSimulator c : venues.treasuryBooks()) {
      c.start(VenueHarness.freePort());
    }
    venues.futuresBook().start(VenueHarness.freePort());
  }

  /** Starts the model's RFQ flow and its liquidity on every book. */
  public void startFlow() {
    venues.startFlow();
  }

  /** Runs every event due at the current time on every venue. */
  public void onTime() {
    venues.onTime();
  }

  /**
   * Moves a {@link SimClock} under every venue's lock, so a venue thread never observes the move
   * half way through handling a message.
   */
  public void advance(final SimClock sim, final long nanos) {
    Runnable move = () -> sim.advance(nanos);
    for (final ClobSimulator c : books()) {
      final Runnable inner = move;
      move = () -> c.locked(inner);
    }
    final Runnable all = move;
    venues.d2c().locked(all);
  }

  /** The session the venues share. */
  public SimV2Session session() {
    return venues.session();
  }

  public D2cRfqSimulator d2c() {
    return venues.d2c();
  }

  /** The Treasury order-book venues, in share order. */
  public List<ClobSimulator> treasuryBooks() {
    return venues.treasuryBooks();
  }

  /** The futures order-book venue. */
  public ClobSimulator futuresBook() {
    return venues.futuresBook();
  }

  /** Every order-book venue: the Treasury books, then the futures book. */
  public List<ClobSimulator> books() {
    final List<ClobSimulator> all = new ArrayList<>(venues.treasuryBooks());
    all.add(venues.futuresBook());
    return all;
  }

  /** The feed channel of order-book venue {@code v}, in the order of {@link #books()}. */
  public InProcessFeedChannel feed(final int v) {
    return feeds.get(v);
  }

  public Clock clock() {
    return clock;
  }

  @Override
  public void close() {
    venues.d2c().close();
    for (final ClobSimulator c : books()) {
      c.close();
    }
  }
}
