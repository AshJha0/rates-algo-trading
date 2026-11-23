package io.ratesalgo.venues.sim;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.refdata.FuturesContract;
import io.ratesalgo.refdata.RefData;
import io.ratesalgo.refdata.SwapTemplate;
import io.ratesalgo.sim.MarketModel;
import io.ratesalgo.sim.MarketSpec;
import io.ratesalgo.sim.SimInstrument;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One simulator v2 session as the venue simulators use it: the shared {@link MarketModel}, the
 * venue instruments that stand for the model's universe, and the venue clock time at which the
 * session opened. The D2C simulator and every order-book simulator of a run hold the same
 * session, so an order on any book moves the same market (docs/research/SIM_V2_DESIGN.md
 * section 10).
 *
 * <p>The only things that differ from the back-test are transport: FIX sessions and a venue clock
 * instead of in-process calls.
 */
public final class SimV2Session {

  /** Instrument id base for model swaps that have no template in the reference data. */
  public static final int SYNTHETIC_ID_BASE = 9000;

  private final MarketModel model;
  private final MarketSpec spec;
  private final Clock clock;
  private final long openNanoTime;
  private final List<Instrument> instruments;
  private final Map<String, Integer> indexBySecurityId = new HashMap<>();

  /**
   * Opens the session at the clock's current time.
   *
   * @param venueInstruments one venue instrument per model instrument, in the model's order
   */
  public SimV2Session(final MarketModel model, final List<Instrument> venueInstruments, final Clock clock) {
    this.model = model;
    this.spec = model.spec();
    this.clock = clock;
    this.openNanoTime = clock.nanoTime();
    this.instruments = List.copyOf(venueInstruments);
    if (instruments.size() != spec.instruments()) {
      throw new IllegalArgumentException("one venue instrument per model instrument is required");
    }
    for (int i = 0; i < instruments.size(); i++) {
      indexBySecurityId.put(instruments.get(i).securityId(), i);
    }
  }

  /**
   * Venue instruments for the model's universe, in the model's order. Treasuries are the
   * on-the-run issues of the date, swaps the USD SOFR templates, the future the contract on the
   * matching underlying; a model swap tenor without a template gets a synthetic instrument, so
   * the RFQ flow is the model's flow whatever the reference data lists. Ticks and starting mids
   * are the model's.
   */
  public static List<Instrument> instruments(final MarketSpec spec, final RefData ref, final LocalDate date) {
    final List<Instrument> out = new ArrayList<>();
    for (int i = 0; i < spec.instruments(); i++) {
      final SimInstrument s = spec.instrument(i);
      final String tenor = s.key().substring(s.key().indexOf('-') + 1);
      final long mid = mantissa(s.initialMid());
      final long tick = mantissa(s.tick());
      switch (s.kind()) {
        case SWAP -> {
          SwapTemplate template = null;
          for (final SwapTemplate t : ref.securities().swaps()) {
            if ("USD-SOFR".equals(t.market()) && tenor.equals(t.tenor())) {
              template = t;
            }
          }
          out.add(
              template == null
                  ? new Instrument(SYNTHETIC_ID_BASE + s.id(), "USD-SOFR-" + tenor, "swap", mid, tick, "USD", false, 0)
                  : new Instrument(
                      template.instrumentId(), "USD-SOFR-" + tenor, "swap", mid, tick, template.currency(),
                      template.madeAvailableToTrade(), template.blockThreshold()));
        }
        case TREASURY -> {
          final var bond =
              ref.securities()
                  .onTheRun("UST", tenor, date)
                  .orElseThrow(() -> new IllegalArgumentException("no on-the-run " + s.key() + " on " + date));
          out.add(new Instrument(bond.instrumentId(), bond.isin(), "bond", mid, tick, bond.currency(), false, 0));
        }
        case FUTURE -> {
          FuturesContract contract = null;
          for (final FuturesContract f : ref.securities().futures()) {
            if (f.symbol().startsWith(s.key()) && contract == null) {
              contract = f;
            }
          }
          out.add(
              contract == null
                  ? new Instrument(SYNTHETIC_ID_BASE + s.id(), s.key(), "future", mid, tick, "USD", false, 0)
                  : new Instrument(contract.instrumentId(), contract.symbol(), "future", mid, tick, contract.currency(), false, 0));
        }
      }
    }
    return out;
  }

  /** A quoted value as a price mantissa (exponent -9): the boundary conversion from the model's doubles. */
  public static long mantissa(final double quoted) {
    return Math.round(quoted * 1e9);
  }

  /** The shared market model. */
  public MarketModel model() {
    return model;
  }

  /** Nanoseconds since the session opened, on the venue clock. */
  public long sessionNanos() {
    return clock.nanoTime() - openNanoTime;
  }

  /** Venue clock time of a session time. */
  public long nanoTimeOf(final long sessionNanos) {
    return openNanoTime + sessionNanos;
  }

  /** Model index of a venue SecurityID, or -1. */
  public int index(final String securityId) {
    final Integer i = indexBySecurityId.get(securityId);
    return i == null ? -1 : i;
  }

  /** The venue instrument of a model index. */
  public Instrument instrument(final int index) {
    return instruments.get(index);
  }

  /** Venue instruments of one model kind, in the model's order. */
  public List<Instrument> instruments(final SimInstrument.Kind kind) {
    final List<Instrument> out = new ArrayList<>();
    for (int i = 0; i < instruments.size(); i++) {
      if (spec.instrument(i).kind() == kind) {
        out.add(instruments.get(i));
      }
    }
    return out;
  }

  /** The instruments clients send RFQs in: swaps and Treasuries. */
  public List<Instrument> rfqInstruments() {
    final List<Instrument> out = new ArrayList<>(instruments(SimInstrument.Kind.TREASURY));
    out.addAll(instruments(SimInstrument.Kind.SWAP));
    return out;
  }

  /** The unrounded latent mid of a model instrument now, as a mantissa. */
  public long latentMid(final int index) {
    return mantissa(model.latentMid(index, sessionNanos()));
  }

  /** The published reference price of a model instrument now: the latent mid rounded to its tick. */
  public long referencePrice(final int index) {
    return mantissa(model.referencePrice(index, sessionNanos()));
  }
}
