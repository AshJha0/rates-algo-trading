package io.ratesalgo.refdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RefDataTest {

  private static final RefData REF = RefData.loadDefault();

  @Test
  void loadsEveryFixtureWithChecksums() {
    assertEquals(6, REF.checksums().size());
    assertEquals(14, REF.securities().bonds().size());
    assertEquals(7, REF.securities().swaps().size());
    assertEquals(5, REF.securities().futures().size());
    assertEquals(5, REF.parties().ofType(PartyType.CLIENT).size());
    assertEquals(14, REF.auctions().all().size());
  }

  @Test
  void onTheRunRollsAtIssueAndWhenIssuedStartsAtAnnouncement() {
    final SecuritiesMaster m = REF.securities();
    final Bond oct = m.bondByIsin("US91282CZB08").orElseThrow(); // 2Y issued 2026-09-30
    final Bond nov = m.bondByIsin("US91282CZC80").orElseThrow(); // 2Y announced 10-22, issued 11-02
    assertEquals(BondStatus.NOT_ANNOUNCED, m.status(nov, LocalDate.of(2026, 10, 21)));
    assertEquals(BondStatus.WHEN_ISSUED, m.status(nov, LocalDate.of(2026, 10, 22)));
    assertTrue(m.isTradable(nov, LocalDate.of(2026, 10, 22)));
    assertFalse(m.isTradable(nov, LocalDate.of(2026, 10, 21)));
    assertEquals(oct, m.onTheRun("UST", "2Y", LocalDate.of(2026, 11, 1)).orElseThrow());
    assertEquals(nov, m.onTheRun("UST", "2Y", LocalDate.of(2026, 11, 2)).orElseThrow());
    assertEquals(BondStatus.OFF_THE_RUN, m.status(oct, LocalDate.of(2026, 11, 2)));
    assertEquals(BondStatus.ON_THE_RUN, m.status(nov, LocalDate.of(2026, 11, 2)));
    assertEquals(BondStatus.MATURED, m.status(oct, LocalDate.of(2028, 9, 30)));
    assertTrue(m.onTheRun("UST", "7Y", LocalDate.of(2026, 11, 2)).isEmpty());
    assertTrue(m.onTheRun("UST", "2Y", LocalDate.of(2026, 1, 2)).isEmpty());
  }

  @Test
  void lookupsByIdAndCode() {
    final SecuritiesMaster m = REF.securities();
    assertEquals("US91282CZA25", m.bond(1001).orElseThrow().isin());
    assertEquals(3_625_000_000L, m.bond(1001).orElseThrow().couponMantissa());
    assertTrue(m.swap(2003).orElseThrow().madeAvailableToTrade());
    assertFalse(m.swap(2005).orElseThrow().madeAvailableToTrade());
    assertEquals(15_625_000L, m.futureBySymbol("TYZ6").orElseThrow().tickSizeMantissa());
    assertEquals(3003, m.future(3003).orElseThrow().instrumentId());
    assertTrue(m.bond(9).isEmpty());
    assertEquals(PartyType.FCM, REF.parties().byCode("FCM-1").orElseThrow().type());
    assertEquals(2, REF.parties().byId(2004).orElseThrow().tier());
  }

  /**
   * Tick sizes against the exchange (review 2026-11-23, A11). The 2-Year T-Note trades in eighths
   * of a 32nd, 0.00390625 points or 7.8125 dollars a contract, since 2019-01-13 (CME SER-8171; the
   * CME 2-Year T-Note contract specifications; CBOT Submission 20-119 cites it when giving the
   * 3-Year the same tick). The fixture had the old quarter of a 32nd. The 5-Year is a quarter of
   * a 32nd, the 10-Year half a 32nd, the Bond one 32nd.
   */
  @Test
  void treasuryFuturesTicksFollowTheContractSpecifications() {
    final SecuritiesMaster m = REF.securities();
    assertEquals(3_906_250L, m.futureBySymbol("TUZ6").orElseThrow().tickSizeMantissa());
    assertEquals(200_000L, m.futureBySymbol("TUZ6").orElseThrow().contractSize());
    assertEquals(7_812_500L, m.futureBySymbol("FVZ6").orElseThrow().tickSizeMantissa());
    assertEquals(15_625_000L, m.futureBySymbol("TYZ6").orElseThrow().tickSizeMantissa());
    assertEquals(31_250_000L, m.futureBySymbol("USZ6").orElseThrow().tickSizeMantissa());
    // one tick is 7.8125 dollars on the 2-Year and the 5-Year alike: tick times contract size
    assertEquals(7.8125, 3_906_250L * 1e-9 / 100.0 * 200_000L, 1e-12);
    assertEquals(7.8125, 7_812_500L * 1e-9 / 100.0 * 100_000L, 1e-12);
  }

  /**
   * Dated dates (review 2026-11-23, A5): the Treasury dates a note on its nominal date and issues
   * it on the next business day (31 CFR 356.2, "dated date"), so the notes maturing on 31 October
   * and issued on Monday 2026-11-02 are dated Saturday 2026-10-31.
   */
  @Test
  void weekendDatedNotesCarryTheirNominalDatedDate() {
    final SecuritiesMaster m = REF.securities();
    for (final int id : new int[] {1003, 1006}) {
      final Bond b = m.bond(id).orElseThrow();
      assertEquals(LocalDate.of(2026, 10, 31), b.datedDate(), b.isin());
      assertEquals(LocalDate.of(2026, 11, 2), b.issueDate(), b.isin());
      assertEquals(LocalDate.of(2027, 4, 30), b.firstCouponDate(), b.isin());
    }
    for (final int id : new int[] {1008, 1010}) {
      assertEquals(LocalDate.of(2026, 11, 15), m.bond(id).orElseThrow().datedDate());
      assertEquals(LocalDate.of(2026, 11, 16), m.bond(id).orElseThrow().issueDate());
    }
    // issued on its nominal date, or with a genuinely short first coupon: dated on the issue date
    for (final int id : new int[] {1001, 1002, 1004, 1005, 1011, 1013}) {
      final Bond b = m.bond(id).orElseThrow();
      assertEquals(b.issueDate(), b.datedDate(), b.isin());
    }
  }

  /**
   * Good Friday 2026-04-03 is a business day for US government securities (review 2026-11-23, A6):
   * SIFMA recommends an early close at 12:00 Eastern, not a full close, because the employment
   * report is released that day (sifma.org holiday schedule, read 2026-11-23). In 2027 Good Friday
   * is 26 March and a full close by the standing rule.
   */
  @Test
  void goodFriday2026IsAnEarlyCloseNotAHolidayForUsGovernmentSecurities() {
    final CalendarSource c = REF.calendars();
    assertTrue(c.isBusinessDay("US-GS", LocalDate.of(2026, 4, 3)));
    assertFalse(c.isBusinessDay("UK", LocalDate.of(2026, 4, 3)));
    assertFalse(c.isBusinessDay("TARGET", LocalDate.of(2026, 4, 3)));
    assertFalse(c.isBusinessDay("US-GS", LocalDate.of(2027, 3, 26)));
    assertEquals(LocalDate.of(2026, 4, 3), c.addBusinessDays("US-GS", LocalDate.of(2026, 4, 2), 1));
  }

  @Test
  void aDateOutsideTheFixtureYearsSaysWhatIsCoveredAndHowToExtendIt() {
    final CalendarSource c = REF.calendars();
    final RefDataException e =
        assertThrows(RefDataException.class, () -> c.isBusinessDay("US-GS", LocalDate.of(2028, 1, 3)));
    for (final String part : new String[] {"'US-GS'", "2026 to 2027", "2028-01-03", "gen_refdata_fixtures.py"}) {
      assertTrue(e.getMessage().contains(part), e.getMessage());
    }
    // addBusinessDays walks into the next year and must fail the same way, not run on
    assertThrows(RefDataException.class, () -> c.addBusinessDays("UK", LocalDate.of(2027, 12, 31), 1));
  }

  @Test
  void auctionCalendar() {
    final AuctionCalendar a = REF.auctions();
    assertEquals(10, a.forMarket("UST").size());
    assertEquals(
        "US91282CZH77", a.next("UST", LocalDate.of(2026, 11, 1)).orElseThrow().isin());
    assertEquals(1, a.on(LocalDate.of(2026, 11, 11)).size());
    assertTrue(a.next("BUND", LocalDate.of(2027, 2, 1)).isEmpty());
  }

  @Test
  void calendars() {
    final CalendarSource c = REF.calendars();
    assertFalse(c.isBusinessDay("US-GS", LocalDate.of(2026, 11, 26)));
    assertTrue(c.isBusinessDay("UK", LocalDate.of(2026, 11, 26)));
    assertFalse(c.isBusinessDay("US-GS", LocalDate.of(2026, 11, 28)));
    assertEquals(
        LocalDate.of(2026, 11, 30), c.addBusinessDays("US-GS", LocalDate.of(2026, 11, 25), 2));
    assertEquals(LocalDate.of(2026, 11, 27), c.addBusinessDays("US-GS", LocalDate.of(2026, 11, 26), 0));
    assertThrows(IllegalArgumentException.class, () -> c.addBusinessDays("UK", LocalDate.of(2026, 1, 2), -1));
    assertThrows(RefDataException.class, () -> c.isHoliday("XX", LocalDate.of(2026, 1, 1)));
  }

  /** DR-R2R-06: 2027 is covered, and a date in an uncovered year is refused, not a business day. */
  @Test
  void calendarsCover2027AndRefuseUncoveredYears() {
    final CalendarSource c = REF.calendars();
    assertFalse(c.isBusinessDay("US-GS", LocalDate.of(2027, 1, 18)), "MLK day");
    for (final String cal : new String[] {"US-GS", "UK", "TARGET"}) {
      assertFalse(c.isBusinessDay(cal, LocalDate.of(2027, 3, 26)), cal + " Good Friday");
    }
    assertFalse(c.isBusinessDay("UK", LocalDate.of(2027, 3, 29)));
    assertFalse(c.isBusinessDay("TARGET", LocalDate.of(2027, 3, 29)));
    assertFalse(c.isBusinessDay("UK", LocalDate.of(2027, 5, 3)));
    assertFalse(c.isBusinessDay("US-GS", LocalDate.of(2027, 12, 24)), "Christmas observed");
    assertTrue(c.isBusinessDay("US-GS", LocalDate.of(2027, 12, 31)));
    assertThrows(RefDataException.class, () -> c.isHoliday("US-GS", LocalDate.of(2028, 1, 3)));
    assertThrows(RefDataException.class, () -> c.isHoliday("UK", LocalDate.of(2025, 12, 31)));
  }

  @Test
  void identifiers() {
    assertTrue(Identifiers.isValidIsin("US0378331005"));
    assertTrue(Identifiers.isValidIsin("GB0002634946"));
    assertFalse(Identifiers.isValidIsin("US0378331006"));
    assertFalse(Identifiers.isValidIsin("U10378331005"));
    assertFalse(Identifiers.isValidIsin("US03783310a5"));
    assertFalse(Identifiers.isValidIsin("US037833100X"));
    assertFalse(Identifiers.isValidIsin(null));
    assertTrue(Identifiers.isValidCusip("037833100"));
    assertTrue(Identifiers.isValidCusip("91282CZA2"));
    assertFalse(Identifiers.isValidCusip("91282CZA3"));
    assertFalse(Identifiers.isValidCusip("91282CZ-2"));
    assertFalse(Identifiers.isValidCusip("short"));
    assertTrue(Identifiers.isValidCusip("12*@#0004"));
    assertFalse(Identifiers.isValidCusip("12*@#000X"));
  }

  @Test
  void editedFixtureFailsChecksum(@TempDir final Path tmp) throws IOException {
    final Path dir = copyFixtures(tmp);
    Files.writeString(dir.resolve("parties.csv"), "9999,X,X,CLIENT,1\n", StandardOpenOption.APPEND);
    final RefDataException e = assertThrows(RefDataException.class, () -> RefData.load(dir));
    assertTrue(e.getMessage().contains("checksum mismatch for parties.csv"), e.getMessage());
  }

  @Test
  void badRowsAreRejectedWithFileAndLine(@TempDir final Path tmp) throws IOException {
    assertLoadFails(tmp.resolve("a"), "bonds.csv", "US91282CZA25", "US91282CZA26", "invalid ISIN");
    assertLoadFails(tmp.resolve("b"), "bonds.csv", ",3.625,", ",3.6251234567,", "at most 9 places");
    assertLoadFails(tmp.resolve("c"), "bonds.csv", ",US-GS,2026-08-20", ",XX,2026-08-20", "unknown calendar");
    assertLoadFails(tmp.resolve("d"), "parties.csv", ",CLIENT,1", ",ALIEN,1", "unknown party type");
    assertLoadFails(tmp.resolve("e"), "futures.csv", "3001,", "x3001,", "not an integer");
    assertLoadFails(tmp.resolve("f"), "auctions.csv", "2026-08-25,", "2026-08-26,", "disagree");
    assertLoadFails(tmp.resolve("g"), "swap_templates.csv", ",true,440", ",yes,440", "true or false");
    assertLoadFails(tmp.resolve("h"), "holidays.csv", "2026-11-26", "2026-11-x6", "ISO date");
    assertLoadFails(tmp.resolve("i"), "parties.csv", "15,DLR-E", "14,DLR-E", "duplicate party");
    assertLoadFails(tmp.resolve("j"), "futures.csv", "3001,", "1001,", "duplicate instrument id");
    assertLoadFails(tmp.resolve("k"), "bonds.csv", "1001,US", "1001,,US", "columns");
    assertLoadFails(tmp.resolve("l"), "bonds.csv", "2027-04-30,2026-10-31,2026-11-02", "2027-04-30,2026-11-03,2026-11-02",
        "dated date");
    assertLoadFails(tmp.resolve("m"), "bonds.csv", "2027-04-30,2026-10-31,2026-11-02", "2027-04-30,2026-10-01,2026-11-02",
        "dated date");
  }

  @Test
  void manifestMustListEveryFile(@TempDir final Path tmp) throws IOException {
    final Path dir = copyFixtures(tmp);
    final Path manifest = dir.resolve(Manifest.FILE_NAME);
    final String text = Files.readString(manifest, StandardCharsets.UTF_8);
    final String without =
        String.join(
            "\n", text.lines().filter(l -> !l.endsWith("futures.csv")).toList());
    Files.writeString(manifest, without, StandardCharsets.UTF_8);
    assertThrows(RefDataException.class, () -> RefData.load(dir));
    Files.writeString(manifest, "bad line\n", StandardCharsets.UTF_8);
    assertThrows(RefDataException.class, () -> Manifest.verify(dir));
    assertThrows(RefDataException.class, () -> Manifest.verify(tmp.resolve("missing")));
  }

  private static void assertLoadFails(
      final Path tmp, final String file, final String from, final String to, final String message)
      throws IOException {
    final Path dir = copyFixtures(tmp);
    final Path f = dir.resolve(file);
    final String text = Files.readString(f, StandardCharsets.UTF_8);
    assertTrue(text.contains(from), file + " lacks " + from);
    Files.writeString(f, text.replaceFirst(java.util.regex.Pattern.quote(from), to), StandardCharsets.UTF_8);
    rewriteManifest(dir);
    final RefDataException e = assertThrows(RefDataException.class, () -> RefData.load(dir));
    assertTrue(e.getMessage().contains(message), e.getMessage());
  }

  private static Path copyFixtures(final Path tmp) throws IOException {
    Files.createDirectories(tmp);
    final Path src = RefData.locateDefault();
    try (var files = Files.list(src)) {
      for (final Path p : files.toList()) {
        Files.copy(p, tmp.resolve(p.getFileName()));
      }
    }
    return tmp;
  }

  private static void rewriteManifest(final Path dir) throws IOException {
    final StringBuilder sb = new StringBuilder();
    try (var files = Files.list(dir)) {
      for (final Path p : files.sorted().toList()) {
        if (!p.getFileName().toString().equals(Manifest.FILE_NAME)) {
          sb.append(Manifest.sha256(p)).append("  ").append(p.getFileName()).append('\n');
        }
      }
    }
    Files.writeString(dir.resolve(Manifest.FILE_NAME), sb.toString(), StandardCharsets.UTF_8);
  }
}
