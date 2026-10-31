package io.ratesalgo.refdata;

import java.time.LocalDate;

/**
 * One scheduled auction.
 *
 * @param market UST, GILT or BUND
 * @param tenor tenor bucket
 * @param isin security auctioned
 * @param announcementDate announcement date
 * @param auctionDate auction date
 * @param issueDate issue (settlement) date
 */
public record Auction(
    String market,
    String tenor,
    String isin,
    LocalDate announcementDate,
    LocalDate auctionDate,
    LocalDate issueDate) {}
