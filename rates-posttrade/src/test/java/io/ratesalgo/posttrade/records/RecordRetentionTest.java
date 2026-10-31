package io.ratesalgo.posttrade.records;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.Replayer;
import io.ratesalgo.posttrade.Fixtures;
import io.ratesalgo.posttrade.chaos.SyntheticDay;
import io.ratesalgo.posttrade.position.PositionKeeper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Acceptance: every order and trade record is retrievable by venue order id within the retention
 * layout (API_SPEC.md Section 17.4).
 */
class RecordRetentionTest {

  @TempDir Path dir;

  @Test
  void everyRecordIsRetrievableByVenueOrderIdFromTheArchive() {
    final SyntheticDay.Result day =
        SyntheticDay.generate(dir.resolve("day.raj"), 11L, SyntheticDay.Params.DEFAULT);
    final PositionKeeper keeper = new PositionKeeper(Fixtures.books(), Fixtures.catalogue());
    new Replayer(new ManualClock(0)).replay(day.journal(), keeper);
    final RecordArchive archive = new RecordArchive(dir.resolve("records"), 5);
    archive.append(day.date(), keeper.orderRecords());

    assertTrue(Files.exists(dir.resolve("records/2026/10/30/order-records.csv")));
    final Map<Long, Integer> expected = new TreeMap<>();
    for (final OrderRecord r : keeper.orderRecords()) {
      if (r.venueOrderId() != 0) {
        expected.merge(r.venueOrderId(), 1, Integer::sum);
      }
    }
    assertEquals(SyntheticDay.Params.DEFAULT.orders(), expected.size());
    for (final Map.Entry<Long, Integer> e : expected.entrySet()) {
      final List<OrderRecord> found = archive.findByVenueOrderId(e.getKey());
      assertEquals(e.getValue(), found.size(), "venue order " + e.getKey());
      assertEquals(keeper.recordsByVenueOrderId(e.getKey()), found);
    }
    // the archived file round-trips every record exactly
    assertEquals(keeper.orderRecords(), archive.read(day.date()));
  }

  @Test
  void retentionKeepsFiveYearsAndPurgesOlder() {
    final RecordArchive archive = new RecordArchive(dir.resolve("r"), 5);
    final LocalDate old = LocalDate.of(2020, 10, 29);
    final LocalDate kept = LocalDate.of(2021, 11, 1);
    archive.append(old, List.of(record(old, 1)));
    archive.append(kept, List.of(record(kept, 2)));
    assertEquals(List.of(old, kept), archive.dates());
    assertEquals(1, archive.findByVenueOrderId(1).size());
    assertEquals(List.of(old), archive.purgeExpired(LocalDate.of(2026, 10, 30)));
    assertTrue(archive.findByVenueOrderId(1).isEmpty());
    assertEquals(1, archive.findByVenueOrderId(2).size());
    assertEquals(LocalDate.of(2021, 10, 30), archive.retainedFrom(LocalDate.of(2026, 10, 30)));
    assertTrue(archive.read(old).isEmpty());
  }

  @Test
  void archiveRefusesMisfiledRecordsAndBadRetention() {
    final RecordArchive archive = new RecordArchive(dir.resolve("m"), 7);
    final LocalDate d = LocalDate.of(2026, 10, 30);
    assertThrows(IllegalArgumentException.class, () -> archive.append(d, List.of(record(d.minusDays(1), 3))));
    assertThrows(IllegalArgumentException.class, () -> new RecordArchive(dir, 4));
    assertThrows(IllegalArgumentException.class, () -> new RecordArchive(dir, 8));
    assertTrue(new RecordArchive(dir.resolve("none"), 5).dates().isEmpty());
  }

  @Test
  void csvRoundTripAndFieldChecks() throws Exception {
    final OrderRecord r = record(LocalDate.of(2026, 10, 30), 9);
    assertEquals(r, OrderRecord.fromCsv(r.toCsv()));
    assertEquals(26, OrderRecord.CSV_HEADER.split(",").length);
    assertThrows(IllegalArgumentException.class, () -> OrderRecord.fromCsv("1,2"));
    final OrderRecord comma = new OrderRecord(1, "2026-10-30", "T3", "NEWO", "BUYI", "DEAL", false, "A,B", 1, 1,
        "V", "LMT", 0, "USD", "PERC", 0, 0, 0, 0, "", "GDAY", "X", 1, 0, 1, "");
    assertThrows(IllegalStateException.class, comma::toCsv);
    assertEquals("", OrderRecord.isoTime(0));
    final Path bad = dir.resolve("bad/2026/10/30");
    Files.createDirectories(bad);
    Files.writeString(bad.resolve(RecordArchive.FILE_NAME), "wrong header\n");
    assertThrows(IllegalStateException.class, () -> new RecordArchive(dir.resolve("bad"), 5).read(LocalDate.of(2026, 10, 30)));
    assertFalse(archiveHasDates(dir.resolve("absent")));
  }

  private static boolean archiveHasDates(final Path p) {
    return !new RecordArchive(p, 5).dates().isEmpty();
  }

  private static OrderRecord record(final LocalDate date, final long venueOrderId) {
    return new OrderRecord(1, date.toString(), "T3", "FILL", "SELL", "DEAL", true, "ALG-1", 7, venueOrderId,
        "VENUE-1", "LMT", 99_500_000_001L, "USD", "PERC", 0, 100, 0, 1_793_350_800_000_000_123L, "55", "GDAY",
        "US0000000000", 1001, 0, 12, "1:E:55");
  }
}
