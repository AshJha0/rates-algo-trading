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
import io.ratesalgo.venues.sim.AcceptorOptions;
import io.ratesalgo.venues.sim.SimSequencer;
import io.ratesalgo.venues.sim.VenueSimulator;
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
 * orders come from the model, and the step size of the driver no longer changes the market.
 * {@link V2DeskProcess} and the trading process under a simulator v2 profile run on it; the
 * recorded profile still starts {@link SimulatedVenues}, so the recorded smoke figures do not
 * change.
 *
 * <p>The venues share one market model, so they share one {@link SimSequencer}: their FIX threads
 * queue what arrives and the thread that drives the venues ({@link #advance}, {@link #onTime},
 * {@link #drain}) is the only one that touches the model. A driver must call {@link #drain}, or
 * no logon and no order is ever handled.
 */
public final class SimulatedVenuesV2 implements AutoCloseable {

  private final Clock clock;
  private final Venues.SimV2 venues;
  private final List<InProcessFeedChannel> feeds = new ArrayList<>();
  private final SimSequencer sequencer = new SimSequencer();

  /**
   * Builds and starts the venues for the session with this seed. The session opens at the clock's
   * current time, which stands for 07:00 New York; the day type follows from the seed.
   */
  public SimulatedVenuesV2(final Clock clock, final long seed) {
    this(clock, seed, new AcceptorOptions[] {
      AcceptorOptions.PLAIN, AcceptorOptions.PLAIN, AcceptorOptions.PLAIN, AcceptorOptions.PLAIN
    });
  }

  /**
   * The same venues with acceptor settings, one per venue in the order D2C, Treasury books,
   * futures book: TLS, logon credentials and the directory in which a venue keeps its sequence
   * numbers, sent messages and id generation, so that a restarted process meets venues that
   * resume their sessions and never reuse an execution id.
   */
  public SimulatedVenuesV2(final Clock clock, final long seed, final AcceptorOptions[] acceptors) {
    this.clock = clock;
    final MarketSpec spec = new MarketSpec(SimParams.loadDefault());
    final MarketModel model = SessionSeeds.session(spec, seed);
    for (int v = 0; v <= spec.params().treasuryVenues(); v++) {
      feeds.add(new InProcessFeedChannel());
    }
    if (acceptors.length != feeds.size() + 1) {
      throw new IllegalArgumentException("one acceptor setting per venue is required: " + (feeds.size() + 1));
    }
    this.venues = Venues.simV2(model, clock, new ArrayList<FeedChannel>(feeds), sequencer);
    final List<VenueSimulator> all = simulators();
    int started = 0;
    try {
      for (; started < all.size(); started++) {
        all.get(started).start(VenueHarness.freePort(), acceptors[started]);
      }
    } finally {
      if (started < all.size()) {
        // a venue refused to start: stop the ones that did
        for (int v = 0; v < started; v++) {
          all.get(v).close();
        }
      }
    }
  }

  /** Every venue in sequencing order: the D2C venue, the Treasury books, the futures book. */
  public List<VenueSimulator> simulators() {
    final List<VenueSimulator> all = new ArrayList<>();
    all.add(venues.d2c());
    all.addAll(books());
    return all;
  }

  /**
   * Handles what the venues' FIX threads have queued, venue by venue in the order of {@link
   * #simulators} and each venue's messages in arrival order. The driving thread only. Returns
   * the number of logons, logouts and messages handled.
   */
  public int drain() {
    return sequencer.drain();
  }

  /** Logons, logouts and messages queued by the FIX threads and not yet handled. */
  public long pending() {
    return sequencer.pending();
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

  /**
   * Runs the venues alone for {@code nanos} of session time, one grid second at a time: the
   * market before the desk connects. The feed frames of that time are discarded, as a
   * subscriber that was not there never saw them; a feed binding starts from a snapshot.
   */
  public void runAlone(final SimClock sim, final long nanos) {
    final long step = io.ratesalgo.sim.MarketSpec.STEP_NANOS;
    for (long done = 0; done < nanos; done += step) {
      advance(sim, Math.min(step, nanos - done));
      onTime();
      for (final InProcessFeedChannel feed : feeds) {
        while (feed.poll((buffer, offset, length) -> true, 4096) > 0) {
          // every frame
        }
      }
    }
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
