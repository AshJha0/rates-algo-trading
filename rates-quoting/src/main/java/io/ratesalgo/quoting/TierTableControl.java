package io.ratesalgo.quoting;

/**
 * Control-plane review of the tier table: one person proposes a new version, a different person
 * approves it, and only then does it become active. Versions must increase. Each step is a
 * control-plane command journaled by the event core (P2).
 */
public final class TierTableControl {

  private TierTable active;
  private TierTable pending;
  private int proposer;

  /** Starts with an approved initial table. */
  public TierTableControl(final TierTable initial) {
    this.active = initial;
  }

  /** Proposes a new version. */
  public void propose(final TierTable table, final int proposerId) {
    if (table.version() <= active.version()) {
      throw new IllegalArgumentException(
          "version " + table.version() + " not above active " + active.version());
    }
    pending = table;
    proposer = proposerId;
  }

  /** Approves the pending version; returns false if the approver is the proposer. */
  public boolean approve(final long version, final int approverId) {
    if (pending == null || pending.version() != version) {
      throw new IllegalStateException("no pending tier table version " + version);
    }
    if (approverId == proposer) {
      return false;
    }
    active = pending;
    pending = null;
    return true;
  }

  public TierTable active() {
    return active;
  }

  /** The pending version, or -1. */
  public long pendingVersion() {
    return pending == null ? -1L : pending.version();
  }
}
