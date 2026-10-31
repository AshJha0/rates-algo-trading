package io.ratesalgo.posttrade.records;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Order records on disk in the retention layout of API_SPEC.md Section 17.4:
 * {@code <root>/<yyyy>/<mm>/<dd>/order-records.csv}, one directory per trading date, kept for
 * {@code retentionYears} (five by default, the MiFID record-keeping period; up to seven when the
 * regulator asks).
 *
 * <p>Writes append to the day's file and never rewrite it. Lookups scan the dated directories;
 * a production archive would keep an index, the layout is the contract.
 */
public final class RecordArchive {

  /** File name inside each dated directory. */
  public static final String FILE_NAME = "order-records.csv";

  private final Path root;
  private final int retentionYears;

  public RecordArchive(final Path root, final int retentionYears) {
    if (retentionYears < 5 || retentionYears > 7) {
      throw new IllegalArgumentException("retention must be 5 to 7 years: " + retentionYears);
    }
    this.root = root;
    this.retentionYears = retentionYears;
  }

  /** The directory for one trading date. */
  public Path directoryFor(final LocalDate date) {
    return root.resolve(String.format(java.util.Locale.ROOT, "%04d", date.getYear()))
        .resolve(String.format(java.util.Locale.ROOT, "%02d", date.getMonthValue()))
        .resolve(String.format(java.util.Locale.ROOT, "%02d", date.getDayOfMonth()));
  }

  /** Appends records for {@code date}; writes the header when the file is new. */
  public void append(final LocalDate date, final List<OrderRecord> records) {
    final Path dir = directoryFor(date);
    final Path file = dir.resolve(FILE_NAME);
    try {
      Files.createDirectories(dir);
      final boolean fresh = !Files.exists(file);
      try (BufferedWriter w =
          Files.newBufferedWriter(
              file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
        if (fresh) {
          w.write(OrderRecord.CSV_HEADER);
          w.write('\n');
        }
        for (final OrderRecord r : records) {
          if (!r.tradingDate().equals(date.toString())) {
            throw new IllegalArgumentException(
                "record " + r.recordSequence() + " dated " + r.tradingDate() + " filed under " + date);
          }
          w.write(r.toCsv());
          w.write('\n');
        }
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Every record for one date, in file order. */
  public List<OrderRecord> read(final LocalDate date) {
    final Path file = directoryFor(date).resolve(FILE_NAME);
    final List<OrderRecord> out = new ArrayList<>();
    if (!Files.exists(file)) {
      return out;
    }
    try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      final String header = r.readLine();
      if (!OrderRecord.CSV_HEADER.equals(header)) {
        throw new IllegalStateException("unexpected header in " + file);
      }
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        if (!line.isEmpty()) {
          out.add(OrderRecord.fromCsv(line));
        }
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    return out;
  }

  /** Every archived trading date, ascending. */
  public List<LocalDate> dates() {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (Stream<Path> s = Files.find(root, 3, (p, a) -> a.isDirectory() && root.relativize(p).getNameCount() == 3)) {
      return s.map(p -> root.relativize(p))
          .map(rel -> LocalDate.of(
              Integer.parseInt(rel.getName(0).toString()),
              Integer.parseInt(rel.getName(1).toString()),
              Integer.parseInt(rel.getName(2).toString())))
          .sorted()
          .toList();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Every record, across all retained dates, with this venue order id, in date then file order. */
  public List<OrderRecord> findByVenueOrderId(final long venueOrderId) {
    final List<OrderRecord> out = new ArrayList<>();
    for (final LocalDate d : dates()) {
      for (final OrderRecord r : read(d)) {
        if (r.venueOrderId() == venueOrderId) {
          out.add(r);
        }
      }
    }
    return out;
  }

  /** The first date that must still be kept on {@code asOf}. */
  public LocalDate retainedFrom(final LocalDate asOf) {
    return asOf.minusYears(retentionYears);
  }

  /**
   * Deletes dated directories older than the retention period.
   *
   * @return the dates deleted
   */
  public List<LocalDate> purgeExpired(final LocalDate asOf) {
    final LocalDate keepFrom = retainedFrom(asOf);
    final List<LocalDate> deleted = new ArrayList<>();
    for (final LocalDate d : dates()) {
      if (d.isBefore(keepFrom)) {
        deleteTree(directoryFor(d));
        deleted.add(d);
      }
    }
    return deleted;
  }

  private static void deleteTree(final Path dir) {
    try (Stream<Path> s = Files.walk(dir)) {
      for (final Path p : s.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
