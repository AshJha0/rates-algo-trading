package io.ratesalgo.posttrade.report;

import io.ratesalgo.journal.JournalReader;
import io.ratesalgo.posttrade.marks.AttributionLine;
import io.ratesalgo.posttrade.marks.EndOfDayPnl;
import io.ratesalgo.posttrade.marks.FixtureCarrySource;
import io.ratesalgo.posttrade.marks.FixtureMarkSource;
import io.ratesalgo.posttrade.marks.PnlLine;
import io.ratesalgo.posttrade.position.InstrumentCatalogue;
import io.ratesalgo.posttrade.position.InstrumentReference;
import io.ratesalgo.posttrade.position.PositionKeeper;
import io.ratesalgo.posttrade.records.RecordArchive;
import io.ratesalgo.posttrade.surveillance.QuoteStuffingDetector;
import io.ratesalgo.posttrade.surveillance.SelfTradeDetector;
import io.ratesalgo.posttrade.surveillance.SurveillanceAlert;
import io.ratesalgo.posttrade.trade.BookAssignment;
import io.ratesalgo.posttrade.trade.FixedBookMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The end-of-day report generator process (deploy/report/Dockerfile). Reads a trading journal,
 * books it with the instrument and book reference files, writes official P&amp;L in the
 * attribution format, archives the order records in the retention layout, and writes the
 * surveillance alerts.
 *
 * <pre>
 * EodReportMain --journal J --date YYYY-MM-DD --instruments I.csv --books B.csv
 *               --marks M.csv --carry C.csv --out DIR [--retention-years N]
 * </pre>
 *
 * <p>Exit status 0 on success, 2 on bad arguments, 3 when an official mark is missing.
 */
public final class EodReportMain {

  private static final Pattern COMMA = Pattern.compile(",");

  private EodReportMain() {}

  public static void main(final String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  /** Runs the report; returns the exit status. */
  public static int run(final String[] args, final PrintStream out, final PrintStream err) {
    final Map<String, String> a = new HashMap<>();
    for (int i = 0; i + 1 < args.length; i += 2) {
      if (!args[i].startsWith("--")) {
        err.println("unexpected argument " + args[i]);
        return 2;
      }
      a.put(args[i].substring(2), args[i + 1]);
    }
    for (final String required : List.of("journal", "date", "instruments", "books", "marks", "carry", "out")) {
      if (!a.containsKey(required)) {
        err.println("missing --" + required);
        return 2;
      }
    }
    try {
      final LocalDate date = LocalDate.parse(a.get("date"));
      final InstrumentCatalogue catalogue = readInstruments(Path.of(a.get("instruments")));
      final FixedBookMapper books = readBooks(Path.of(a.get("books")));
      final PositionKeeper keeper = new PositionKeeper(books, catalogue);
      final QuoteStuffingDetector stuffing = new QuoteStuffingDetector(1_000_000_000L, 50);
      final SelfTradeDetector selfTrade = new SelfTradeDetector(60_000_000_000L);
      try (JournalReader reader = JournalReader.open(Path.of(a.get("journal")))) {
        while (reader.next((s, t, id, v, b, o, l) -> {
          keeper.onFrame(s, t, id, v, b, o, l);
          stuffing.onFrame(s, t, id, v, b, o, l);
          selfTrade.onFrame(s, t, id, v, b, o, l);
        })) {
          // reading
        }
      }
      final FixtureMarkSource marks;
      final FixtureCarrySource carry;
      try (Reader m = Files.newBufferedReader(Path.of(a.get("marks")), StandardCharsets.UTF_8);
          Reader c = Files.newBufferedReader(Path.of(a.get("carry")), StandardCharsets.UTF_8)) {
        marks = FixtureMarkSource.read(m, a.get("marks"));
        carry = FixtureCarrySource.read(c, a.get("carry"));
      }
      final List<PnlLine> pnl;
      try {
        pnl = new EndOfDayPnl(marks, carry, catalogue).compute(date, keeper);
      } catch (final io.ratesalgo.posttrade.marks.MissingMarkException e) {
        err.println(e.getMessage());
        return 3;
      }
      final Path outDir = Path.of(a.get("out"));
      Files.createDirectories(outDir);
      try (Writer w = Files.newBufferedWriter(outDir.resolve("official-pnl-" + date + ".csv"), StandardCharsets.UTF_8)) {
        w.write(AttributionLine.HEADER + "\n");
        for (final AttributionLine l : AttributionLine.fromPnl(pnl)) {
          w.write(l.toCsv() + "\n");
        }
      }
      final int retention = Integer.parseInt(a.getOrDefault("retention-years", "5"));
      new RecordArchive(outDir.resolve("records"), retention).append(date, keeper.orderRecords().stream()
          .filter(r -> r.tradingDate().equals(date.toString())).toList());
      final List<SurveillanceAlert> alerts = new ArrayList<>(stuffing.alerts());
      alerts.addAll(selfTrade.alerts());
      try (Writer w = Files.newBufferedWriter(outDir.resolve("surveillance-" + date + ".csv"), StandardCharsets.UTF_8)) {
        w.write("detector,instrument_id,journal_sequence,timestamp_nanos,detail\n");
        for (final SurveillanceAlert al : alerts) {
          w.write(al.detector() + "," + al.instrumentId() + "," + al.journalSequence() + ","
              + al.timestampNanos() + "," + al.detail().replace(',', ';') + "\n");
        }
      }
      out.println("eod " + date + ": positions=" + pnl.size() + " records=" + keeper.orderRecords().size()
          + " alerts=" + alerts.size());
      return 0;
    } catch (final IOException e) {
      err.println("io error: " + e.getMessage());
      return 2;
    } catch (final RuntimeException e) {
      err.println("error: " + e.getMessage());
      return 2;
    }
  }

  /** Reads {@code instrument_id,identifier,currency,price_notation,multiplier}. */
  public static InstrumentCatalogue readInstruments(final Path file) throws IOException {
    final InstrumentCatalogue c = new InstrumentCatalogue();
    for (final String[] f : rows(file, "instrument_id,identifier,currency,price_notation,multiplier")) {
      c.add(new InstrumentReference(Integer.parseInt(f[0]), f[1], f[2], f[3], new BigDecimal(f[4])));
    }
    return c;
  }

  /** Reads {@code instrument_id,book,account,algorithm_id}; instrument 0 is the default. */
  public static FixedBookMapper readBooks(final Path file) throws IOException {
    BookAssignment fallback = null;
    final Map<Integer, BookAssignment> byInstrument = new HashMap<>();
    for (final String[] f : rows(file, "instrument_id,book,account,algorithm_id")) {
      final BookAssignment b = new BookAssignment(Integer.parseInt(f[1]), Integer.parseInt(f[2]), f[3]);
      if ("0".equals(f[0])) {
        fallback = b;
      } else {
        byInstrument.put(Integer.parseInt(f[0]), b);
      }
    }
    if (fallback == null) {
      throw new IllegalArgumentException(file + ": needs a default row with instrument_id 0");
    }
    final FixedBookMapper m = new FixedBookMapper(fallback);
    byInstrument.forEach(m::with);
    return m;
  }

  private static List<String[]> rows(final Path file, final String header) throws IOException {
    final List<String[]> out = new ArrayList<>();
    try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      final String first = r.readLine();
      if (!header.equals(first)) {
        throw new IllegalArgumentException(file + ": header must be " + header);
      }
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        if (!line.isBlank() && !line.startsWith("#")) {
          out.add(COMMA.split(line.strip(), -1));
        }
      }
    } catch (final UncheckedIOException e) {
      throw e.getCause();
    }
    return out;
  }
}
