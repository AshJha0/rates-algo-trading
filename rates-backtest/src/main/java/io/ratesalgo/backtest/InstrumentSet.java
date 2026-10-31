package io.ratesalgo.backtest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The instruments a back-test knows, keyed by id.
 *
 * <p>{@link #usdDefault()} is the synthetic USD universe used until the P1 securities master is
 * wired: four SOFR OIS swaps, two Treasuries and the 10-year note future. Annuities and durations
 * are round figures for a 4 percent flat curve (LEARN.md Section 1 uses the same 10-year annuity
 * of about 8.11); they are inputs to the linear valuation, not outputs of a pricer.
 */
public final class InstrumentSet {

  public static final int SOFR_2Y = 101;
  public static final int SOFR_5Y = 102;
  public static final int SOFR_10Y = 103;
  public static final int SOFR_30Y = 104;
  public static final int UST_2Y = 201;
  public static final int UST_10Y = 202;
  public static final int TY_FUTURE = 301;

  /** One million in minor units (cents). */
  public static final long MILLION = 100_000_000L;

  private final Map<Integer, Instrument> byId;

  public InstrumentSet(final List<Instrument> instruments) {
    final TreeMap<Integer, Instrument> map = new TreeMap<>();
    for (final Instrument i : instruments) {
      if (map.put(i.id(), i) != null) {
        throw new IllegalArgumentException("duplicate instrument id " + i.id());
      }
    }
    this.byId = Collections.unmodifiableMap(map);
  }

  public static InstrumentSet usdDefault() {
    final List<Instrument> list = new ArrayList<>();
    list.add(swap(SOFR_2Y, "SOFR 2Y", 2.0, 1.92));
    list.add(swap(SOFR_5Y, "SOFR 5Y", 5.0, 4.52));
    list.add(swap(SOFR_10Y, "SOFR 10Y", 10.0, 8.11));
    list.add(swap(SOFR_30Y, "SOFR 30Y", 30.0, 17.29));
    // Treasuries: priceChangePerBp = -modified duration * price * 1e-4 at price 100.
    list.add(
        new Instrument(
            UST_2Y, "UST 2Y", Instrument.Kind.BOND, 2.0, 1e-4, -0.0190, 3_906_250L, MILLION, 0.0425));
    list.add(
        new Instrument(
            UST_10Y, "UST 10Y", Instrument.Kind.BOND, 10.0, 1e-4, -0.0815, 7_812_500L, MILLION, 0.0425));
    // TY: 100,000 face per contract, tick 1/64 point; duration of the CTD about 6.4.
    list.add(
        new Instrument(
            TY_FUTURE,
            "TY",
            Instrument.Kind.FUTURE,
            10.0,
            1e-4,
            -0.0715,
            15_625_000L,
            10_000_000L,
            0.0));
    return new InstrumentSet(list);
  }

  private static Instrument swap(final int id, final String name, final double tenor, final double annuity) {
    // quoted in percent; one basis point is 0.01 of price; tick 0.0001 percent (0.01 bp)
    return new Instrument(
        id, name, Instrument.Kind.SWAP, tenor, annuity * 1e-4, 0.01, 100_000L, MILLION, 0.0);
  }

  public Instrument get(final int id) {
    final Instrument i = byId.get(id);
    if (i == null) {
      throw new IllegalArgumentException("unknown instrument id " + id);
    }
    return i;
  }

  public boolean contains(final int id) {
    return byId.containsKey(id);
  }

  /** Instruments in ascending id order. */
  public List<Instrument> all() {
    return List.copyOf(byId.values());
  }
}
