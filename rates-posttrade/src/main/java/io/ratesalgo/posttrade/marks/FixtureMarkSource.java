package io.ratesalgo.posttrade.marks;

import java.io.Reader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;

/**
 * {@link MarkSource} over a CSV of public closes with header {@code date,instrument_id,mark} and
 * marks written as decimals in the quoting convention (price per 100 or rate in percent). A
 * {@code source} column is not needed: the file names its source in a {@code #} comment.
 */
public final class FixtureMarkSource implements MarkSource {

  /** Required header line. */
  public static final String HEADER = "date,instrument_id,mark";

  private final Map<Key, Long> marks = new HashMap<>();

  /** Reads marks from a CSV source. */
  public static FixtureMarkSource read(final Reader source, final String name) {
    final FixtureMarkSource out = new FixtureMarkSource();
    for (final String[] f : Csv.read(source, HEADER, name)) {
      out.put(
          Integer.parseInt(f[1]), LocalDate.parse(f[0]),
          new BigDecimal(f[2]).movePointRight(9).longValueExact());
    }
    return out;
  }

  /** Adds or replaces one mark, given as a Decimal9 mantissa. */
  public FixtureMarkSource put(final int instrumentId, final LocalDate date, final long mantissa) {
    marks.put(new Key(instrumentId, date), mantissa);
    return this;
  }

  @Override
  public OptionalLong closingMark(final int instrumentId, final LocalDate date) {
    final Long m = marks.get(new Key(instrumentId, date));
    return m == null ? OptionalLong.empty() : OptionalLong.of(m);
  }

  private record Key(int instrumentId, LocalDate date) {}
}
