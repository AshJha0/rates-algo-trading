package io.ratesalgo.refdata;

import java.time.LocalDate;

/**
 * One government bond in the securities master.
 *
 * @param instrumentId id used on the wire (API_SPEC.md Section 0)
 * @param isin ISO 6166 identifier, check digit validated at load
 * @param cusip CUSIP for US issues, empty otherwise
 * @param market UST, GILT or BUND
 * @param currency ISO 4217
 * @param tenor original tenor bucket such as 10Y, used for on-the-run selection
 * @param couponMantissa annual coupon in percent as a mantissa with exponent -9
 * @param frequency coupons per year
 * @param maturity maturity date
 * @param firstCouponDate first coupon payment date
 * @param datedDate date interest accrues from. The Treasury dates a note on its nominal date and
 *     issues it on the next business day when that date is not one, so this can be a day or two
 *     before {@code issueDate}; the first coupon is then a full one and the buyer pays the accrued
 * @param issueDate issue date: the first settlement of the auction
 * @param firstSettlementDate first date a trade may settle
 * @param calendar holiday calendar name for {@link CalendarSource}
 * @param announcementDate auction announcement; when-issued trading starts here
 * @param auctionDate auction date
 * @param onTheRunFromDate the date this bond becomes on the run, normally its issue date
 */
public record Bond(
    int instrumentId,
    String isin,
    String cusip,
    String market,
    String currency,
    String tenor,
    long couponMantissa,
    int frequency,
    LocalDate maturity,
    LocalDate firstCouponDate,
    LocalDate datedDate,
    LocalDate issueDate,
    LocalDate firstSettlementDate,
    String calendar,
    LocalDate announcementDate,
    LocalDate auctionDate,
    LocalDate onTheRunFromDate) {}
