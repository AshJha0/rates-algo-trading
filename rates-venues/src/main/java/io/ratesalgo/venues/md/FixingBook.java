package io.ratesalgo.venues.md;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.messages.sbe.MdRejectReason;
import java.util.HashMap;
import java.util.Map;

/**
 * Published fixings by index and date, with republication handling. An administrator may
 * republish a fixing after an error until a cutoff on the same day (for SOFR the New York Fed
 * republishes by 14:30 New York time when an error exceeds one basis point). A republication
 * with a new value before the cutoff replaces the fixing and is counted; after the cutoff it is
 * rejected as late; an identical republication is recorded as unchanged. Both rejections are
 * journaled.
 */
public final class FixingBook {

  private final Clock clock;
  private final RejectJournal journal;
  private final Map<String, long[]> fixings = new HashMap<>();
  private long republications;

  /** Creates a book. */
  public FixingBook(final Clock clock, final RejectJournal journal) {
    this.clock = clock;
    this.journal = journal;
  }

  /**
   * Records a fixing publication.
   *
   * @param indexId curve or index id (journaled as the instrument)
   * @param yyyymmdd fixing date
   * @param value rate mantissa
   * @param cutoffEpochNanos republication cutoff for this date
   * @return true if the value is now the fixing
   */
  public boolean publish(final int indexId, final int yyyymmdd, final long value, final long cutoffEpochNanos) {
    final String key = indexId + ":" + yyyymmdd;
    final long[] old = fixings.get(key);
    final long now = clock.epochNanos();
    if (old == null) {
      fixings.put(key, new long[] {value, 0});
      return true;
    }
    if (old[0] == value) {
      journal.reject(0, indexId, MdRejectReason.FixingRepublicationUnchanged, value, value, old[0], yyyymmdd, now, now);
      return false;
    }
    if (now > cutoffEpochNanos) {
      journal.reject(0, indexId, MdRejectReason.FixingRepublicationLate, value, value, old[0], yyyymmdd, now, now);
      return false;
    }
    old[0] = value;
    old[1]++;
    republications++;
    return true;
  }

  /** The fixing for an index and date, or 0 if none. */
  public long fixing(final int indexId, final int yyyymmdd) {
    final long[] f = fixings.get(indexId + ":" + yyyymmdd);
    return f == null ? 0 : f[0];
  }

  /** Republications accepted. */
  public long republications() {
    return republications;
  }
}
