package io.ratesalgo.quoting;

import io.ratesalgo.api.BookSource;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.FairValueSource;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.ProductType;
import io.ratesalgo.risk.RiskEngine;

/**
 * Fair value for quoting (API_SPEC.md Section 12.5).
 *
 * <ul>
 *   <li>Bonds and futures: the microprice of the composite book, {@code (bid * askSize + ask *
 *       bidSize) / (bidSize + askSize)}, which leans toward the side with less size. Without a
 *       two-sided book, the pricing source's fair mid.
 *   <li>Swaps: the pricing source's curve-implied par rate, plus {@code beta} times the futures
 *       signal: the move in futures-implied yield, in bp, between the futures mid and its
 *       microprice. A futures microprice above mid means yields are about to fall, so the swap's
 *       fair rate is lowered.
 *   <li>Every product: plus the installed {@link FairValueSignal}, zero by default.
 * </ul>
 *
 * Each computation records the oldest and newest stamp of the inputs it used; the engine refuses
 * to quote when the oldest is beyond the maximum age. Allocation-free.
 */
public final class FairValueEngine {

  private final FairValueSource source;
  private final BookSource book;
  private final RiskEngine risk;
  private FairValueSignal signal = FairValueSignal.NONE;
  private ValidationRecord validation;

  private double fair;
  private long oldestNanos;
  private long newestNanos;

  public FairValueEngine(final FairValueSource source, final BookSource book, final RiskEngine risk) {
    this.source = source;
    this.book = book;
    this.risk = risk;
  }

  /** Installs a signal if its validation record permits it at {@code nowNanos}. */
  public boolean install(final FairValueSignal newSignal, final ValidationRecord record, final long nowNanos) {
    if (record == null || !record.permits(nowNanos)) {
      return false;
    }
    signal = newSignal;
    validation = record;
    return true;
  }

  /** Removes any installed signal. */
  public void uninstall() {
    signal = FairValueSignal.NONE;
    validation = null;
  }

  /** The record of the installed signal, or null. */
  public ValidationRecord validation() {
    return validation;
  }

  /** Computes fair value; false when no input exists. Read the result with the getters. */
  public boolean compute(final Instrument inst, final long nowNanos) {
    final int id = inst.id();
    oldestNanos = Long.MAX_VALUE;
    newestNanos = Long.MIN_VALUE;
    boolean have = false;
    if (inst.type() != ProductType.SWAP && microprice(id)) {
      have = true;
    }
    if (!have) {
      final long mid = source.fairMid(id);
      final long ref = source.referenceNanos(id);
      if (mid == FairValueSource.NO_VALUE || ref == FairValueSource.NO_VALUE) {
        return false;
      }
      fair = Decimal9.toDouble(mid);
      stamp(ref);
    }
    final double qpb = risk.quotePerBp(id);
    if (inst.type() == ProductType.SWAP && inst.signalInstrumentId() != 0) {
      final int f = inst.signalInstrumentId();
      final double base = fair;
      if (microprice(f)) {
        final double micro = fair;
        final double mid = 0.5 * (Decimal9.toDouble(book.bestBid(f)) + Decimal9.toDouble(book.bestAsk(f)));
        final double yieldBp = -(micro - mid) / risk.quotePerBp(f);
        fair = base + inst.signalBeta() * yieldBp * qpb;
      } else {
        fair = base;
      }
    }
    fair += signal.adjustmentBp(id, nowNanos) * qpb;
    return true;
  }

  private boolean microprice(final int id) {
    final long bid = book.bestBid(id);
    final long ask = book.bestAsk(id);
    final long bs = book.bidSize(id);
    final long as = book.askSize(id);
    if (bid == FairValueSource.NO_VALUE || ask == FairValueSource.NO_VALUE || bs <= 0 || as <= 0 || bid >= ask) {
      return false;
    }
    final double b = Decimal9.toDouble(bid);
    final double a = Decimal9.toDouble(ask);
    final double wb = (double) bs;
    final double wa = (double) as;
    fair = (b * wa + a * wb) / (wa + wb);
    stamp(book.updateNanos(id));
    return true;
  }

  private void stamp(final long nanos) {
    oldestNanos = Math.min(oldestNanos, nanos);
    newestNanos = Math.max(newestNanos, nanos);
  }

  /** Fair value in quote units from the last computation. */
  public double fair() {
    return fair;
  }

  /** Oldest input stamp of the last computation. */
  public long oldestNanos() {
    return oldestNanos;
  }

  /** Newest input stamp of the last computation (the Quote's referenceNanos). */
  public long newestNanos() {
    return newestNanos;
  }
}
