package io.ratesalgo.venues.md;

/**
 * The composite of one instrument.
 *
 * @param instrumentId instrument
 * @param bid best valid bid across fresh sources
 * @param ask best valid ask across fresh sources
 * @param sources number of fresh sources contributing
 * @param updatedNanos monotonic time of the last change
 */
public record Composite(int instrumentId, long bid, long ask, int sources, long updatedNanos) {}
