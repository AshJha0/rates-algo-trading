package io.ratesalgo.venues.md;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.messages.sbe.MdRejectReason;
import java.util.HashMap;
import java.util.Map;

/**
 * Arbitrates several quote sources per instrument into a composite best bid and offer.
 *
 * <p>Checks on each source quote, in order (API_SPEC.md Section 11.5): bid or ask not positive;
 * bid above ask (crossed); bid equal to ask (locked); sequence number not above the source's last
 * (out of order); mid further than the band from the reference (outlier). A rejected quote is
 * journaled and leaves the source's previous quote in place. A sequence jump is journaled as a gap
 * and the quote is still used. The composite is the highest bid and lowest ask over sources whose
 * last accepted quote is younger than the staleness limit; a crossed or locked composite is
 * journaled and the previous composite is kept.
 */
public final class CompositeBuilder {

  private final Clock clock;
  private final ReferencePrice reference;
  private final RejectJournal journal;
  private final long outlierBand;
  private final long stalenessNanos;
  private final Map<Integer, Map<Integer, long[]>> quotes = new HashMap<>();
  private final Map<Integer, SourceMetrics> metrics = new HashMap<>();
  private final Map<Integer, Composite> composites = new HashMap<>();

  /**
   * Creates a builder.
   *
   * @param clock time source
   * @param reference reference mids for the outlier check
   * @param journal where rejections go
   * @param outlierBand largest allowed distance of a quote's mid from the reference, mantissa
   * @param stalenessNanos age after which a source no longer contributes
   */
  public CompositeBuilder(
      final Clock clock,
      final ReferencePrice reference,
      final RejectJournal journal,
      final long outlierBand,
      final long stalenessNanos) {
    this.clock = clock;
    this.reference = reference;
    this.journal = journal;
    this.outlierBand = outlierBand;
    this.stalenessNanos = stalenessNanos;
  }

  /** Metrics of a source. */
  public SourceMetrics metrics(final int sourceId) {
    return metrics.computeIfAbsent(sourceId, k -> new SourceMetrics());
  }

  /** The composite of an instrument, or null if none has been formed. */
  public Composite composite(final int instrumentId) {
    return composites.get(instrumentId);
  }

  /** Processes one source quote. Returns true if it was accepted. */
  public boolean onQuote(
      final int sourceId,
      final int instrumentId,
      final long seq,
      final long bid,
      final long ask,
      final long sourceNanos) {
    final long now = clock.nanoTime();
    final SourceMetrics m = metrics(sourceId);
    final long ref = reference.reference(instrumentId);
    MdRejectReason reason = null;
    if (bid <= 0 || ask <= 0) {
      reason = MdRejectReason.NonPositivePrice;
    } else if (bid > ask) {
      reason = MdRejectReason.CrossedQuote;
    } else if (bid == ask) {
      reason = MdRejectReason.LockedQuote;
    } else if (seq <= m.lastSeq) {
      reason = MdRejectReason.OutOfOrder;
    } else if (ref != 0 && Math.abs((bid + ask) / 2 - ref) > outlierBand) {
      reason = MdRejectReason.Outlier;
    }
    if (reason != null) {
      m.rejects++;
      journal.reject(sourceId, instrumentId, reason, bid, ask, ref, seq, sourceNanos, clock.epochNanos());
      return false;
    }
    if (m.lastSeq != 0 && seq != m.lastSeq + 1) {
      journal.reject(sourceId, instrumentId, MdRejectReason.SequenceGap, bid, ask, ref, seq, sourceNanos, clock.epochNanos());
    }
    m.lastSeq = seq;
    m.updates++;
    m.lastUpdateNanos = now;
    m.stale = false;
    quotes.computeIfAbsent(instrumentId, k -> new HashMap<>()).put(sourceId, new long[] {bid, ask, now});
    rebuild(instrumentId);
    return true;
  }

  /** Marks sources stale whose last quote is older than the limit and rebuilds composites. */
  public void checkStaleness() {
    final long now = clock.nanoTime();
    for (final Map.Entry<Integer, Map<Integer, long[]>> inst : quotes.entrySet()) {
      for (final Map.Entry<Integer, long[]> q : inst.getValue().entrySet()) {
        final SourceMetrics m = metrics(q.getKey());
        final long age = now - q.getValue()[2];
        m.maxAgeNanos = Math.max(m.maxAgeNanos, age);
        if (age > stalenessNanos && !m.stale) {
          m.stale = true;
          m.staleTransitions++;
          journal.reject(q.getKey(), inst.getKey(), MdRejectReason.Stale, q.getValue()[0], q.getValue()[1],
              reference.reference(inst.getKey()), m.lastSeq, 0, clock.epochNanos());
        }
      }
      rebuild(inst.getKey());
    }
  }

  private void rebuild(final int instrumentId) {
    final long now = clock.nanoTime();
    long bestBid = 0;
    long bestAsk = Long.MAX_VALUE;
    int sources = 0;
    for (final long[] q : quotes.get(instrumentId).values()) {
      if (now - q[2] > stalenessNanos) {
        continue;
      }
      sources++;
      bestBid = Math.max(bestBid, q[0]);
      bestAsk = Math.min(bestAsk, q[1]);
    }
    if (sources == 0) {
      composites.remove(instrumentId);
      return;
    }
    if (bestBid >= bestAsk) {
      journal.reject(0, instrumentId, bestBid > bestAsk ? MdRejectReason.CrossedComposite : MdRejectReason.LockedComposite,
          bestBid, bestAsk, reference.reference(instrumentId), 0, 0, clock.epochNanos());
      return;
    }
    composites.put(instrumentId, new Composite(instrumentId, bestBid, bestAsk, sources, now));
  }
}
