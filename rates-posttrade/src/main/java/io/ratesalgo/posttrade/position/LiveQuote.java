package io.ratesalgo.posttrade.position;

/**
 * A quote the journal says may still be live at its venue.
 *
 * @param venueId venue
 * @param quoteId our quote id
 * @param rfqId the request it answers
 * @param instrumentId instrument
 * @param validUntilNanos expiry, epoch nanoseconds
 */
public record LiveQuote(int venueId, long quoteId, long rfqId, int instrumentId, long validUntilNanos) {}
