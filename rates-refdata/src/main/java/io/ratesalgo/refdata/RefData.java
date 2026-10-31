package io.ratesalgo.refdata;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads one fixture version: verifies {@code MANIFEST.sha256}, parses every file, validates
 * identifiers and dates, and cross-checks the auction calendar against the securities master.
 */
public final class RefData {

  /** Fixture directory relative to the repository root. */
  public static final String DEFAULT_RELATIVE_DIR = "data/refdata/v1";

  private final SecuritiesMaster securities;
  private final PartyMaster parties;
  private final AuctionCalendar auctions;
  private final CalendarSource calendars;
  private final Map<String, String> checksums;

  private RefData(
      final SecuritiesMaster securities,
      final PartyMaster parties,
      final AuctionCalendar auctions,
      final CalendarSource calendars,
      final Map<String, String> checksums) {
    this.securities = securities;
    this.parties = parties;
    this.auctions = auctions;
    this.calendars = calendars;
    this.checksums = Map.copyOf(checksums);
  }

  /** Finds {@link #DEFAULT_RELATIVE_DIR} from the working directory or any parent. */
  public static Path locateDefault() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null) {
      final Path candidate = dir.resolve(DEFAULT_RELATIVE_DIR);
      if (Files.isRegularFile(candidate.resolve(Manifest.FILE_NAME))) {
        return candidate;
      }
      dir = dir.getParent();
    }
    throw new RefDataException(
        "cannot find " + DEFAULT_RELATIVE_DIR + " from " + Path.of("").toAbsolutePath());
  }

  /** Loads the default fixture version. */
  public static RefData loadDefault() {
    return load(locateDefault());
  }

  /** Loads and validates a fixture directory. */
  public static RefData load(final Path dir) {
    final Map<String, String> sums = Manifest.verify(dir);
    for (final String required :
        List.of(
            "bonds.csv",
            "swap_templates.csv",
            "futures.csv",
            "parties.csv",
            "auctions.csv",
            "holidays.csv")) {
      if (!sums.containsKey(required)) {
        throw new RefDataException(Manifest.FILE_NAME + " does not list " + required);
      }
    }
    final FixtureCalendarSource calendars = FixtureCalendarSource.load(dir.resolve("holidays.csv"));
    final List<Bond> bonds = bonds(Csv.read(dir.resolve("bonds.csv")), calendars);
    final SecuritiesMaster master =
        new SecuritiesMaster(
            bonds,
            swaps(Csv.read(dir.resolve("swap_templates.csv")), calendars),
            futures(Csv.read(dir.resolve("futures.csv"))));
    final PartyMaster parties = new PartyMaster(parties(Csv.read(dir.resolve("parties.csv"))));
    final List<Auction> auctionList = auctions(Csv.read(dir.resolve("auctions.csv")));
    for (final Auction a : auctionList) {
      final Bond b =
          master
              .bondByIsin(a.isin())
              .orElseThrow(() -> new RefDataException("auction for unknown ISIN " + a.isin()));
      if (!b.auctionDate().equals(a.auctionDate())
          || !b.announcementDate().equals(a.announcementDate())
          || !b.issueDate().equals(a.issueDate())) {
        throw new RefDataException("auction dates disagree with bonds.csv for " + a.isin());
      }
    }
    return new RefData(master, parties, new AuctionCalendar(auctionList), calendars, sums);
  }

  private static List<Bond> bonds(final Csv csv, final CalendarSource calendars) {
    final List<Bond> out = new ArrayList<>();
    for (int r = 0; r < csv.size(); r++) {
      final String isin = csv.get(r, "isin");
      final String cusip = csv.get(r, "cusip");
      if (!Identifiers.isValidIsin(isin)) {
        throw new RefDataException(csv.where(r) + ": invalid ISIN " + isin);
      }
      if (!cusip.isEmpty()
          && (!Identifiers.isValidCusip(cusip) || !isin.substring(2, 11).equals(cusip))) {
        throw new RefDataException(csv.where(r) + ": invalid CUSIP " + cusip + " for " + isin);
      }
      final String calendar = csv.get(r, "calendar");
      if (!calendars.knows(calendar)) {
        throw new RefDataException(csv.where(r) + ": unknown calendar " + calendar);
      }
      final Bond b =
          new Bond(
              Fields.integer(csv, r, "id"),
              isin,
              cusip,
              csv.get(r, "market"),
              csv.get(r, "currency"),
              csv.get(r, "tenor"),
              Fields.decimal9(csv, r, "coupon"),
              Fields.integer(csv, r, "frequency"),
              Fields.date(csv, r, "maturity"),
              Fields.date(csv, r, "firstCouponDate"),
              Fields.date(csv, r, "issueDate"),
              Fields.date(csv, r, "firstSettlementDate"),
              calendar,
              Fields.date(csv, r, "announcementDate"),
              Fields.date(csv, r, "auctionDate"),
              Fields.date(csv, r, "onTheRunFromDate"));
      if (b.announcementDate().isAfter(b.auctionDate())
          || b.auctionDate().isAfter(b.issueDate())
          || !b.firstCouponDate().isAfter(b.issueDate())
          || !b.maturity().isAfter(b.firstCouponDate())) {
        throw new RefDataException(
            csv.where(r) + ": dates out of order (announce <= auction <= issue < first coupon < maturity)");
      }
      if (b.frequency() != 1 && b.frequency() != 2) {
        throw new RefDataException(csv.where(r) + ": frequency must be 1 or 2");
      }
      out.add(b);
    }
    return out;
  }

  private static List<SwapTemplate> swaps(final Csv csv, final CalendarSource calendars) {
    final List<SwapTemplate> out = new ArrayList<>();
    for (int r = 0; r < csv.size(); r++) {
      final String calendar = csv.get(r, "calendar");
      if (!calendars.knows(calendar)) {
        throw new RefDataException(csv.where(r) + ": unknown calendar " + calendar);
      }
      out.add(
          new SwapTemplate(
              Fields.integer(csv, r, "id"),
              csv.get(r, "market"),
              csv.get(r, "currency"),
              csv.get(r, "index"),
              csv.get(r, "tenor"),
              Fields.integer(csv, r, "fixedFrequency"),
              csv.get(r, "fixedDayCount"),
              Fields.integer(csv, r, "floatFrequency"),
              csv.get(r, "floatDayCount"),
              Fields.integer(csv, r, "paymentLagDays"),
              calendar,
              Fields.integer(csv, r, "spotLagDays"),
              Fields.bool(csv, r, "madeAvailableToTrade"),
              Fields.longValue(csv, r, "blockThreshold")));
    }
    return out;
  }

  private static List<FuturesContract> futures(final Csv csv) {
    final List<FuturesContract> out = new ArrayList<>();
    for (int r = 0; r < csv.size(); r++) {
      out.add(
          new FuturesContract(
              Fields.integer(csv, r, "id"),
              csv.get(r, "symbol"),
              csv.get(r, "exchange"),
              csv.get(r, "underlying"),
              csv.get(r, "currency"),
              Fields.longValue(csv, r, "contractSize"),
              Fields.decimal9(csv, r, "tickSize"),
              Fields.integer(csv, r, "notionalCoupon"),
              csv.get(r, "deliveryMonth"),
              Fields.date(csv, r, "firstDeliveryDate"),
              Fields.date(csv, r, "lastTradingDate")));
    }
    return out;
  }

  private static List<Party> parties(final Csv csv) {
    final List<Party> out = new ArrayList<>();
    for (int r = 0; r < csv.size(); r++) {
      final PartyType type;
      try {
        type = PartyType.valueOf(csv.get(r, "type"));
      } catch (IllegalArgumentException e) {
        throw new RefDataException(csv.where(r) + ": unknown party type " + csv.get(r, "type"), e);
      }
      out.add(
          new Party(
              Fields.integer(csv, r, "id"),
              csv.get(r, "code"),
              csv.get(r, "name"),
              type,
              Fields.integer(csv, r, "tier")));
    }
    return out;
  }

  private static List<Auction> auctions(final Csv csv) {
    final List<Auction> out = new ArrayList<>();
    for (int r = 0; r < csv.size(); r++) {
      out.add(
          new Auction(
              csv.get(r, "market"),
              csv.get(r, "tenor"),
              csv.get(r, "isin"),
              Fields.date(csv, r, "announcementDate"),
              Fields.date(csv, r, "auctionDate"),
              Fields.date(csv, r, "issueDate")));
    }
    return out;
  }

  /** The securities master. */
  public SecuritiesMaster securities() {
    return securities;
  }

  /** The party master. */
  public PartyMaster parties() {
    return parties;
  }

  /** The auction calendar. */
  public AuctionCalendar auctions() {
    return auctions;
  }

  /** The holiday calendars. */
  public CalendarSource calendars() {
    return calendars;
  }

  /** File name to sha256 for every fixture loaded. */
  public Map<String, String> checksums() {
    return checksums;
  }
}
