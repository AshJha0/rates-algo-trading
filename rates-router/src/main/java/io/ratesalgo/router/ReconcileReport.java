package io.ratesalgo.router;

/**
 * Outcome of a mass status reconciliation for one venue.
 *
 * @param checked our non-terminal child orders on the venue that were compared
 * @param missingAtVenue of those, orders the venue had no record of (treated as cancelled)
 * @param closed orders the venue reported cancelled or rejected that we still had open
 * @param orphansCancelled orders the venue reported working that we did not know as live; a
 *     cancel was sent for each
 * @param releasedQuantity quantity released for re-routing
 */
public record ReconcileReport(
    int checked, int missingAtVenue, int closed, int orphansCancelled, long releasedQuantity) {}
