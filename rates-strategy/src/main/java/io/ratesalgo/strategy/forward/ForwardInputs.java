package io.ratesalgo.strategy.forward;

import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.InstrumentSet;
import io.ratesalgo.backtest.util.Hashing;
import io.ratesalgo.backtest.util.Yaml;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

/**
 * One business day of real rates, read from the input file written by {@code
 * ratesresearch.forward.fetch} (schema {@code forward_inputs/1}).
 *
 * @param asOf the Treasury curve date
 * @param ustParPct Treasury par yields in percent by tenor key (2Y, 10Y, ...)
 * @param sofrPct the latest SOFR fixing on or before {@code asOf}, percent
 * @param swapPct the SOFR OIS swap marks in percent by tenor key (2Y, 5Y, 10Y, 30Y)
 * @param swapSource the source of each swap mark ({@code dtcc_median} or {@code ust_plus_spread})
 * @param sha256 SHA-256 of the file's text, written into the ledger
 * @param file the file name, relative to the inputs directory
 */
public record ForwardInputs(
    LocalDate asOf,
    Map<String, Double> ustParPct,
    double sofrPct,
    Map<String, Double> swapPct,
    Map<String, String> swapSource,
    String sha256,
    String file) {

  public ForwardInputs {
    ustParPct = Map.copyOf(ustParPct);
    swapPct = Map.copyOf(swapPct);
    swapSource = Map.copyOf(swapSource);
  }

  /** Swap tenor key per instrument of {@link InstrumentSet#usdDefault()}. */
  static final Map<Integer, String> SWAP_KEYS =
      Map.of(InstrumentSet.SOFR_2Y, "2Y", InstrumentSet.SOFR_5Y, "5Y", InstrumentSet.SOFR_10Y, "10Y",
          InstrumentSet.SOFR_30Y, "30Y");

  /** Reads {@code dir/<date>.json}. */
  public static ForwardInputs load(final Path dir, final LocalDate date) {
    final Path path = dir.resolve(date + ".json");
    final String where = path.toString();
    final Map<String, Object> doc = Yaml.loadMap(path);
    if (!"forward_inputs/1".equals(Yaml.string(doc, "schema", where))) {
      throw new IllegalArgumentException(where + ": unknown schema");
    }
    final LocalDate asOf = LocalDate.parse(Yaml.string(doc, "as_of", where));
    if (!asOf.equals(date)) {
      throw new IllegalArgumentException(where + ": as_of " + asOf + " is not " + date);
    }
    final Map<String, Double> ust = new TreeMap<>();
    for (final Map.Entry<String, Object> e : Yaml.map(doc, "ust_par_pct", where).entrySet()) {
      ust.put(e.getKey(), Yaml.toDouble(e.getValue(), where + ".ust_par_pct." + e.getKey()));
    }
    final Map<String, Double> swaps = new TreeMap<>();
    final Map<String, String> sources = new TreeMap<>();
    final Map<String, Object> s = Yaml.map(doc, "swaps_pct", where);
    for (final String key : SWAP_KEYS.values()) {
      final Map<String, Object> mark = Yaml.map(s, key, where + ".swaps_pct");
      swaps.put(key, Yaml.number(mark, "rate", where + ".swaps_pct." + key));
      sources.put(key, Yaml.string(mark, "source", where + ".swaps_pct." + key));
    }
    for (final String key : new String[] {"2Y", "10Y"}) {
      if (!ust.containsKey(key)) {
        throw new IllegalArgumentException(where + ": Treasury " + key + " par yield missing");
      }
    }
    final double sofr = Yaml.number(Yaml.map(doc, "sofr", where), "pct", where + ".sofr");
    return new ForwardInputs(asOf, ust, sofr, swaps, sources, Hashing.sha256TextFile(path), date + ".json");
  }

  /**
   * The desk's mid of every instrument at this close: swaps at their marks (percent); the two
   * Treasuries and the note future as model prices, a price of 100 (future: {@code futurePrice0})
   * plus the instrument's price change per bp times the par-yield move in bp from the reference
   * yield (the bond's declared coupon, the future's {@code futureRefYieldPct}).
   */
  public Map<Integer, Double> mids(
      final InstrumentSet instruments,
      final Map<Integer, Double> couponPct,
      final double futurePrice0,
      final double futureRefYieldPct) {
    final Map<Integer, Double> out = new TreeMap<>();
    for (final Map.Entry<Integer, String> e : SWAP_KEYS.entrySet()) {
      out.put(e.getKey(), swapPct.get(e.getValue()));
    }
    final Instrument ust2 = instruments.get(InstrumentSet.UST_2Y);
    final Instrument ust10 = instruments.get(InstrumentSet.UST_10Y);
    final Instrument ty = instruments.get(InstrumentSet.TY_FUTURE);
    out.put(ust2.id(), 100.0 + ust2.priceChangePerBp() * (ustParPct.get("2Y") - couponPct.get(ust2.id())) * 100.0);
    out.put(ust10.id(), 100.0 + ust10.priceChangePerBp() * (ustParPct.get("10Y") - couponPct.get(ust10.id())) * 100.0);
    out.put(ty.id(), futurePrice0 + ty.priceChangePerBp() * (ustParPct.get("10Y") - futureRefYieldPct) * 100.0);
    return out;
  }
}
