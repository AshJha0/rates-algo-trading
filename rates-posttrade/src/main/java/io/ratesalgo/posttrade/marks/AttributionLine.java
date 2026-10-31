package io.ratesalgo.posttrade.marks;

import io.ratesalgo.posttrade.trade.PositionKey;
import java.io.Reader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * One line of the back-tester's P&amp;L attribution (input format pinned in API_SPEC.md Section
 * 17.5; P5 produces it). CSV header {@code date,book,account,instrument_id,component,amount_minor};
 * {@code component} is {@code realised}, {@code unrealised} or {@code carry}; amounts are whole
 * minor units. Lines starting with {@code #} carry provenance (run id, journal hash) and are
 * skipped.
 *
 * @param date business date
 * @param key book, account, instrument
 * @param component which part of P&amp;L
 * @param amountMinor the amount, whole minor units
 */
public record AttributionLine(LocalDate date, PositionKey key, PnlComponent component, long amountMinor) {

  /** Required header line. */
  public static final String HEADER = "date,book,account,instrument_id,component,amount_minor";

  /** Reads an attribution file. */
  public static List<AttributionLine> read(final Reader source, final String name) {
    final List<AttributionLine> out = new ArrayList<>();
    for (final String[] f : Csv.read(source, HEADER, name)) {
      out.add(
          new AttributionLine(
              LocalDate.parse(f[0]),
              new PositionKey(Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3])),
              PnlComponent.parse(f[4]),
              Long.parseLong(f[5])));
    }
    return out;
  }

  /** The CSV line for this attribution, without a newline. */
  public String toCsv() {
    return date + "," + key.book() + "," + key.account() + "," + key.instrumentId() + ","
        + component.fileName() + "," + amountMinor;
  }

  /** Attribution lines for official P&amp;L, three per position, as the back-tester must write them. */
  public static List<AttributionLine> fromPnl(final List<PnlLine> lines) {
    final List<AttributionLine> out = new ArrayList<>();
    for (final PnlLine l : lines) {
      for (final PnlComponent c : PnlComponent.values()) {
        out.add(new AttributionLine(l.date(), l.key(), c, l.amount(c)));
      }
    }
    return out;
  }
}
