package io.ratesalgo.app.pricing;

import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.fix.gateway.InstrumentMap;
import io.ratesalgo.refdata.Bond;
import io.ratesalgo.refdata.RefData;
import io.ratesalgo.refdata.SwapTemplate;
import io.ratesalgo.venues.Venues;
import io.ratesalgo.venues.sim.Instrument;
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

  private final LocalDate tradeDate;
  private final List<SwapSpec> swaps;
  private final List<BondSpec> bonds;

  private UsdUniverse(final LocalDate tradeDate, final List<SwapSpec> swaps, final List<BondSpec> bonds) {
    this.tradeDate = tradeDate;
    this.swaps = List.copyOf(swaps);
    this.bonds = List.copyOf(bonds);
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

  /** Every instrument id: swaps, then bonds. */
  public int[] instrumentIds() {
    final int[] ids = new int[swaps.size() + bonds.size()];
    int k = 0;
    for (final SwapSpec s : swaps) {
      ids[k++] = s.instrumentId();
    }
    for (final BondSpec b : bonds) {
      ids[k++] = b.instrumentId();
    }
    return ids;
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

  /** A gateway instrument map of every instrument (the D2C venue lists both kinds). */
  public InstrumentMap instrumentMap() {
    final InstrumentMap map = new InstrumentMap();
    for (final SwapSpec s : swaps) {
      map.register(s.instrumentId(), s.securityId());
    }
    for (final BondSpec b : bonds) {
      map.register(b.instrumentId(), b.securityId());
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
