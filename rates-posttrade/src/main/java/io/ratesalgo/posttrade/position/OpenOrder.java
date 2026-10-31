package io.ratesalgo.posttrade.position;

/**
 * An order the journal says is still working.
 *
 * @param venueId venue
 * @param clOrdId our order id
 * @param venueOrderId the venue's id, 0 if never acknowledged
 * @param instrumentId instrument
 * @param leavesQuantity quantity still working
 * @param cumQuantity quantity filled so far
 */
public record OpenOrder(
    int venueId, long clOrdId, long venueOrderId, int instrumentId, long leavesQuantity, long cumQuantity) {}
