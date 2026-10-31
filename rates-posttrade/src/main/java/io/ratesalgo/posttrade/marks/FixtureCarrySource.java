package io.ratesalgo.posttrade.marks;

import java.io.Reader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** {@link CarrySource} over a CSV with header {@code date,instrument_id,carry_fraction}. */
public final class FixtureCarrySource implements CarrySource {

  /** Required header line. */
  public static final String HEADER = "date,instrument_id,carry_fraction";

  private final Map<Key, BigDecimal> fractions = new HashMap<>();

  /** Reads carry fractions from a CSV source. */
  public static FixtureCarrySource read(final Reader source, final String name) {
    final FixtureCarrySource out = new FixtureCarrySource();
    for (final String[] f : Csv.read(source, HEADER, name)) {
      out.fractions.put(new Key(Integer.parseInt(f[1]), LocalDate.parse(f[0])), new BigDecimal(f[2]));
    }
    return out;
  }

  @Override
  public BigDecimal carryFraction(final int instrumentId, final LocalDate date) {
    return fractions.getOrDefault(new Key(instrumentId, date), BigDecimal.ZERO);
  }

  private record Key(int instrumentId, LocalDate date) {}
}
