package io.ratesalgo.app.pricing;

import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.fix.gateway.InstrumentMap;
import io.ratesalgo.refdata.Bond;
import io.ratesalgo.refdata.FuturesContract;
import io.ratesalgo.refdata.RefData;
import io.ratesalgo.refdata.SwapTemplate;
import io.ratesalgo.sim.MarketSpec;
import io.ratesalgo.sim.SimInstrument;
import io.ratesalgo.sim.SimParams;
import io.ratesalgo.venues.Venues;
import io.ratesalgo.venues.sim.Instrument;
import io.ratesalgo.venues.sim.SimV2Session;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The instruments the trading process trades: the USD SOFR swap templates and the on-the-run
 * Treasuries the venue simulators list on {@link Venues#TRADE_DATE}, joined to the securities
 * master for tenors, coupons and dates. One source for the pricing binding, the gateways'
 * instrument maps and the post-trade catalogue, so the three cannot disagree on an id.
 */
public final class UsdUniverse {

  /**
   * One swap template.
   *
   * @param instrumentId wire id
   * @param securityId FIX SecurityID on the venues
   * @param tenor tenor code such as 10Y
   * @param tenorMonths tenor in months
   * @param initialMid the simulators' starting par rate, percent as a Decimal9 mantissa
   */
  public record SwapSpec(int instrumentId, String securityId, String tenor, int tenorMonths, long initialMid) {}

  /**
   * One Treasury.
   *
   * @param instrumentId wire id
   * @param securityId ISIN, the FIX SecurityID
   * @param bond the securities master row
   * @param initialMid the simulators' starting clean price per 100 as a Decimal9 mantissa
   * @param tick price increment as a mantissa
   */
  public record BondSpec(int instrumentId, String securityId, Bond bond, long initialMid, long tick) {}

  /**
   * One Treasury future.
   *
   * @param instrumentId wire id from the securities master
   * @param securityId exchange symbol, the FIX SecurityID
   * @param contract the securities master row: contract size, tick, delivery dates
   * @param initialMid the simulators' starting price per 100 as a Decimal9 mantissa
   * @param tick price increment as a mantissa, the contract's own
   * @param nodeYears maturity at which the contract's rate risk sits, years
   * @param priceChangePerBp change of the price per 100 for +1 bp of yield at that maturity
   *     (negative), as the market model defines the contract; not a cheapest-to-deliver calculation
   * @param contractMinor face of one contract in minor units: the desk's quantity for one contract
   * @param venueLotMinor quantity one contract has on the venue's wire, minor units (the venue
   *     counts contracts, the desk counts face, API_SPEC.md Section 0)
   */
  public record FutureSpec(
      int instrumentId,
      String securityId,
      FuturesContract contract,
      long initialMid,
      long tick,
      double nodeYears,
      double priceChangePerBp,
      long contractMinor,
      long venueLotMinor) {

    /** Desk minor units per venue minor unit: what a venue quantity is multiplied by on the way in. */
    public long deskPerVenue() {
      return contractMinor / venueLotMinor;
    }
  }

  /** Instrument id of the 1-year SOFR swap of the simulator v2 universe; no venue lists it. */
  public static final int SWAP_1Y_ID = 9_012;

  private static final long MINOR_PER_UNIT = 100L;

  private final LocalDate tradeDate;
  private final List<SwapSpec> swaps;
  private final List<BondSpec> bonds;
  private final List<FutureSpec> futures;

  private UsdUniverse(final LocalDate tradeDate, final List<SwapSpec> swaps, final List<BondSpec> bonds) {
    this(tradeDate, swaps, bonds, List.of());
  }

  private UsdUniverse(
      final LocalDate tradeDate, final List<SwapSpec> swaps, final List<BondSpec> bonds, final List<FutureSpec> futures) {
    this.tradeDate = tradeDate;
    this.swaps = List.copyOf(swaps);
    this.bonds = List.copyOf(bonds);
    this.futures = List.copyOf(futures);
  }

  /**
   * The simulator v2 universe on {@link Venues#TRADE_DATE}: a SOFR swap at every risk tenor, the
   * on-the-run Treasuries and the Treasury future of the shared market model, with each
   * instrument's own tick. Ids are the venues' ({@link SimV2Session#instruments}): the reference
   * data's where it has the instrument, the session's synthetic ids for the 3, 7 and 20-year
   * swaps. The model has no 1-year swap; it is added under {@link #SWAP_1Y_ID} so that every risk
   * tenor has a priced instrument, opening at the 2-year rate as the model's front end does
   * (SimV2CurveRound). No venue lists it, so it is never quoted.
   */
  public static UsdUniverse simV2() {
    final RefData ref = RefData.loadDefault();
    final MarketSpec spec = new MarketSpec(SimParams.loadDefault());
    final List<Instrument> venue = SimV2Session.instruments(spec, ref, Venues.TRADE_DATE);
    final List<SwapSpec> swaps = new ArrayList<>();
    final List<BondSpec> bonds = new ArrayList<>();
    final List<FutureSpec> futures = new ArrayList<>();
    for (int i = 0; i < venue.size(); i++) {
      final Instrument v = venue.get(i);
      final SimInstrument s = spec.instrument(i);
      switch (s.kind()) {
        case SWAP -> {
          final int tenorMonths = (int) Math.round(12.0 * s.nodeYears());
          if (swaps.isEmpty() && tenorMonths > 12) {
            swaps.add(new SwapSpec(SWAP_1Y_ID, "USD-SOFR-1Y", "1Y", 12, v.initialMid()));
          }
          swaps.add(new SwapSpec(v.instrumentId(), v.securityId(), (tenorMonths / 12) + "Y", tenorMonths, v.initialMid()));
        }
        case TREASURY ->
            bonds.add(
                new BondSpec(
                    v.instrumentId(), v.securityId(), ref.securities().bond(v.instrumentId()).orElseThrow(),
                    v.initialMid(), v.tick()));
        case FUTURE -> {
          FuturesContract contract = null;
          for (final FuturesContract f : ref.securities().futures()) {
            if (f.instrumentId() == v.instrumentId()) {
              contract = f;
            }
          }
          if (contract == null) {
            throw new IllegalStateException("the reference data has no contract for " + s.key());
          }
          if (contract.tickSizeMantissa() != v.tick() || contract.contractSize() != Math.round(s.unitFaceUsd())) {
            throw new IllegalStateException(
                contract.symbol() + ": the market model and the reference data disagree on tick or contract size");
          }
          futures.add(
              new FutureSpec(
                  v.instrumentId(), v.securityId(), contract, v.initialMid(), v.tick(), s.nodeYears(),
                  s.priceChangePerBp(), contract.contractSize() * MINOR_PER_UNIT,
                  Math.round(s.minQuantity()) * MINOR_PER_UNIT));
        }
      }
    }
    return new UsdUniverse(Venues.TRADE_DATE, swaps, bonds, futures);
  }

  /** The simulators' universe on {@link Venues#TRADE_DATE}, from the default reference data. */
  public static UsdUniverse standard() {
    final RefData ref = RefData.loadDefault();
    final List<SwapSpec> swaps = new ArrayList<>();
    for (final Instrument i : Venues.swaps()) {
      final SwapTemplate t = ref.securities().swap(i.instrumentId()).orElseThrow();
      swaps.add(new SwapSpec(i.instrumentId(), i.securityId(), t.tenor(), months(t.tenor()), i.initialMid()));
    }
    final List<BondSpec> bonds = new ArrayList<>();
    for (final Instrument i : Venues.treasuries()) {
      final Bond b = ref.securities().bond(i.instrumentId()).orElseThrow();
      bonds.add(new BondSpec(i.instrumentId(), i.securityId(), b, i.initialMid(), i.tick()));
    }
    return new UsdUniverse(Venues.TRADE_DATE, swaps, bonds);
  }

  /** Months in a tenor code of years ({@code 10Y}) or months ({@code 6M}). */
  static int months(final String tenor) {
    final int n = Integer.parseInt(tenor.substring(0, tenor.length() - 1));
    return switch (tenor.charAt(tenor.length() - 1)) {
      case 'Y' -> 12 * n;
      case 'M' -> n;
      default -> throw new IllegalArgumentException("tenor " + tenor);
    };
  }

  public LocalDate tradeDate() {
    return tradeDate;
  }

  /** The trade date as analytics epoch days. */
  public int valuationDate() {
    return Dates.of(tradeDate.getYear(), tradeDate.getMonthValue(), tradeDate.getDayOfMonth());
  }

  public List<SwapSpec> swaps() {
    return swaps;
  }

  public List<BondSpec> bonds() {
    return bonds;
  }

  /** The Treasury futures; none in the standard universe. */
  public List<FutureSpec> futures() {
    return futures;
  }

  /** Every instrument id: swaps, then bonds, then futures. */
  public int[] instrumentIds() {
    final int[] ids = new int[swaps.size() + bonds.size() + futures.size()];
    int k = 0;
    for (final SwapSpec s : swaps) {
      ids[k++] = s.instrumentId();
    }
    for (final BondSpec b : bonds) {
      ids[k++] = b.instrumentId();
    }
    for (final FutureSpec f : futures) {
      ids[k++] = f.instrumentId();
    }
    return ids;
  }

  /** Future instrument ids, in universe order. */
  public int[] futureIds() {
    final int[] ids = new int[futures.size()];
    for (int j = 0; j < ids.length; j++) {
      ids[j] = futures.get(j).instrumentId();
    }
    return ids;
  }

  /** The future with this id, or null. */
  public FutureSpec future(final int instrumentId) {
    for (final FutureSpec f : futures) {
      if (f.instrumentId() == instrumentId) {
        return f;
      }
    }
    return null;
  }

  /** Bond instrument ids, in universe order. */
  public int[] bondIds() {
    final int[] ids = new int[bonds.size()];
    for (int j = 0; j < ids.length; j++) {
      ids[j] = bonds.get(j).instrumentId();
    }
    return ids;
  }

  /** The bonds' price increment (one tick for every Treasury on the CLOB). */
  public long bondTick() {
    return bonds.get(0).tick();
  }

  /** A gateway instrument map of every instrument (each venue ignores what it does not list). */
  public InstrumentMap instrumentMap() {
    final InstrumentMap map = new InstrumentMap();
    for (final SwapSpec s : swaps) {
      map.register(s.instrumentId(), s.securityId());
    }
    for (final BondSpec b : bonds) {
      map.register(b.instrumentId(), b.securityId());
    }
    for (final FutureSpec f : futures) {
      map.register(f.instrumentId(), f.securityId());
    }
    return map;
  }

  /** The swap with this id, or null. */
  public SwapSpec swap(final int instrumentId) {
    for (final SwapSpec s : swaps) {
      if (s.instrumentId() == instrumentId) {
        return s;
      }
    }
    return null;
  }

  /** The bond with this id, or null. */
  public BondSpec bond(final int instrumentId) {
    for (final BondSpec b : bonds) {
      if (b.instrumentId() == instrumentId) {
        return b;
      }
    }
    return null;
  }
}
