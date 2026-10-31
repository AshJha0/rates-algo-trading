package io.ratesalgo.refdata;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Scheduled auctions for Treasuries, Gilts and Bunds. */
public final class AuctionCalendar {

  private final List<Auction> auctions;

  AuctionCalendar(final List<Auction> auctions) {
    final List<Auction> sorted = new ArrayList<>(auctions);
    sorted.sort(Comparator.comparing(Auction::auctionDate).thenComparing(Auction::isin));
    this.auctions = Collections.unmodifiableList(sorted);
  }

  /** Every auction, by auction date. */
  public List<Auction> all() {
    return auctions;
  }

  /** Auctions of one market, by auction date. */
  public List<Auction> forMarket(final String market) {
    final List<Auction> out = new ArrayList<>();
    for (final Auction a : auctions) {
      if (a.market().equals(market)) {
        out.add(a);
      }
    }
    return Collections.unmodifiableList(out);
  }

  /** The first auction of a market on or after a date. */
  public Optional<Auction> next(final String market, final LocalDate onOrAfter) {
    for (final Auction a : auctions) {
      if (a.market().equals(market) && !a.auctionDate().isBefore(onOrAfter)) {
        return Optional.of(a);
      }
    }
    return Optional.empty();
  }

  /** Auctions held on a date, any market. */
  public List<Auction> on(final LocalDate date) {
    final List<Auction> out = new ArrayList<>();
    for (final Auction a : auctions) {
      if (a.auctionDate().equals(date)) {
        out.add(a);
      }
    }
    return Collections.unmodifiableList(out);
  }
}
