package io.ratesalgo.strategy;

import io.ratesalgo.backtest.Instrument;
import java.util.List;
import org.agrona.collections.Int2IntHashMap;

/**
 * The desk's positions and their DV01 ladder: net DV01 and DV01 per tenor bucket, in major
 * currency per 1 bp rise (API_SPEC.md Section 19.1).
 *
 * <p>Swaps, Treasuries and the note future all land in one ladder, so opposing client flow in
 * different products nets before anything is hedged: a client paying fixed in 10-year swaps and
 * another selling 10-year notes to us leave offsetting entries in the 10Y bucket. Buckets are
 * 2Y (tenor up to 3 years), 5Y (up to 7), 10Y (up to 15) and 30Y (longer).
 *
 * <p>Single-threaded. {@link #setPosition}, {@link #recompute} and every read are allocation-free.
 */
public final class RiskLadder {

  /** Number of tenor buckets. */
  public static final int BUCKETS = 4;

  /** Bucket labels in order. */
  private static final String[] LABELS = {"2Y", "5Y", "10Y", "30Y"};

  private final Instrument[] instruments;
  private final int[] bucket;
  private final double[] dv01PerUnit;
  private final long[] position;
  private final Int2IntHashMap index;
  private final double[] bucketDv01 = new double[BUCKETS];
  private double net;

  /** A ladder over {@code instruments}, every position zero. */
  public RiskLadder(final List<Instrument> instruments) {
    if (instruments.isEmpty()) {
      throw new IllegalArgumentException("a ladder needs instruments");
    }
    final int n = instruments.size();
    this.instruments = instruments.toArray(new Instrument[0]);
    this.bucket = new int[n];
    this.dv01PerUnit = new double[n];
    this.position = new long[n];
    this.index = new Int2IntHashMap(Math.max(16, n * 4), 0.5f, -1);
    for (int i = 0; i < n; i++) {
      final Instrument inst = this.instruments[i];
      if (index.put(inst.id(), i) != -1) {
        throw new IllegalArgumentException("duplicate instrument id " + inst.id());
      }
      bucket[i] = bucketOf(inst.tenorYears());
      dv01PerUnit[i] = inst.dv01PerUnit();
    }
  }

  /** The bucket of a tenor in years. */
  public static int bucketOf(final double tenorYears) {
    if (tenorYears <= 3.0) {
      return 0;
    }
    if (tenorYears <= 7.0) {
      return 1;
    }
    return tenorYears <= 15.0 ? 2 : 3;
  }

  /** Label of bucket {@code b}: 2Y, 5Y, 10Y or 30Y. */
  public static String label(final int b) {
    return LABELS[b];
  }

  /** Number of instruments. */
  public int size() {
    return instruments.length;
  }

  /** Dense index of an instrument id, or -1 if the ladder does not know it. */
  public int indexOf(final int instrumentId) {
    return index.get(instrumentId);
  }

  public Instrument instrument(final int index) {
    return instruments[index];
  }

  public int instrumentId(final int index) {
    return instruments[index].id();
  }

  /** Bucket of the instrument at {@code index}. */
  public int bucketOfIndex(final int index) {
    return bucket[index];
  }

  /** DV01 per minor unit of the instrument at {@code index}. */
  public double dv01PerUnit(final int index) {
    return dv01PerUnit[index];
  }

  /** Sets a position in minor units; call {@link #recompute} after the last change. */
  public void setPosition(final int index, final long minorUnits) {
    position[index] = minorUnits;
  }

  public long position(final int index) {
    return position[index];
  }

  /** Recomputes net DV01 and the buckets from the positions. */
  public void recompute() {
    net = 0.0;
    for (int b = 0; b < BUCKETS; b++) {
      bucketDv01[b] = 0.0;
    }
    for (int i = 0; i < position.length; i++) {
      final double d = position[i] * dv01PerUnit[i];
      net += d;
      bucketDv01[bucket[i]] += d;
    }
  }

  /** Net DV01 across every instrument. */
  public double net() {
    return net;
  }

  /** DV01 in bucket {@code b}. */
  public double bucket(final int b) {
    return bucketDv01[b];
  }

  /** Euclidean norm of the bucket vector: curve risk that net DV01 alone hides. */
  public double ladderNorm() {
    double s = 0.0;
    for (int b = 0; b < BUCKETS; b++) {
      s += bucketDv01[b] * bucketDv01[b];
    }
    return Math.sqrt(s);
  }

  /** Change in net DV01 if we trade {@code quantity} of the instrument at {@code index}. */
  public double tradeDv01(final int index, final boolean weBuy, final long quantity) {
    return (weBuy ? quantity : -quantity) * dv01PerUnit[index];
  }
}
