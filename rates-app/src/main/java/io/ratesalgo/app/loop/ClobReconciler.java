package io.ratesalgo.app.loop;

import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.router.ReconcileReport;
import io.ratesalgo.router.SmartOrderRouter;
import io.ratesalgo.router.VenueOrderStatus;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects what the CLOB says about our orders after its session came back, and hands it to the
 * routers ({@link SmartOrderRouter#reconcile}) when the venue has finished answering.
 *
 * <p>The window opens on the session's RECONCILING status and closes on RECONCILED. Every
 * execution report for a router's child in between is a line of the venue's answer: the reply to
 * the OrderMassStatusRequest, or a live report that says the same thing later. The routers then
 * settle each child against that list by their own rule: a child the venue lists is brought to the
 * venue's state, a child it does not list frees nothing and is sent a cancel, and an order the
 * venue works that no router holds is cancelled.
 *
 * <p>Everything here is driven by journaled inputs, so replay and a restart reproduce it. It runs
 * on the loop thread but only around a reconnect, and it allocates, which is why it is not part of
 * {@link DeskGroup}: the hot-path rules keep collections out of that class.
 */
final class ClobReconciler {

  private final List<VenueOrderStatus> statuses = new ArrayList<>();
  private final List<VenueOrderStatus> forRouter = new ArrayList<>();
  private boolean open;
  private long runs;
  private long checked;
  private long missingAtVenue;
  private long orphansCancelled;
  private long releasedQuantity;

  /** The session is back and the venue has been asked: start a fresh list. */
  void begin() {
    statuses.clear();
    open = true;
  }

  /** The session dropped before the venue finished: the list is worthless. */
  void abandon() {
    statuses.clear();
    open = false;
  }

  /** True between RECONCILING and RECONCILED. */
  boolean isOpen() {
    return open;
  }

  /** One report for a router child while the window is open; the latest report for an order wins. */
  void onReport(final long clOrdId, final OrdStatus status, final long cumQuantity) {
    if (open) {
      statuses.add(new VenueOrderStatus(clOrdId, status, cumQuantity));
    }
  }

  /** The venue has finished: each router settles its children against its part of the list. */
  void finish(final int venueId, final SmartOrderRouter[] routers) {
    if (!open) {
      return;
    }
    open = false;
    runs++;
    for (int j = 0; j < routers.length; j++) {
      forRouter.clear();
      for (final VenueOrderStatus s : statuses) {
        if (AppIds.isRouterOrder(s.clOrdId()) && AppIds.routerOf(s.clOrdId()) == j) {
          forRouter.add(s);
        }
      }
      final ReconcileReport report = routers[j].reconcile(venueId, forRouter);
      checked += report.checked();
      missingAtVenue += report.missingAtVenue();
      orphansCancelled += report.orphansCancelled();
      releasedQuantity += report.releasedQuantity();
    }
    statuses.clear();
  }

  /** Reconciliations completed. */
  long runs() {
    return runs;
  }

  /** Children checked against the venue, over every run. */
  long checked() {
    return checked;
  }

  /** Children the venue did not list; each was sent a cancel and frees nothing until answered. */
  long missingAtVenue() {
    return missingAtVenue;
  }

  /** Orders the venue worked that no router held; each was cancelled. */
  long orphansCancelled() {
    return orphansCancelled;
  }

  /** Quantity the venue confirmed would never fill, released for re-routing. */
  long releasedQuantity() {
    return releasedQuantity;
  }
}
