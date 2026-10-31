/**
 * Market data quality and composite layer (API_SPEC.md Section 11.5): several quote sources per
 * bond arbitrated into a composite; crossed, locked, non-positive and outlying quotes rejected;
 * staleness tracked per source; fixing republications handled; every rejection journaled as a
 * {@code MarketDataReject} message.
 */
package io.ratesalgo.venues.md;
