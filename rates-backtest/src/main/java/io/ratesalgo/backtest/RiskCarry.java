package io.ratesalgo.backtest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * How long the desk carries client risk before a hedge trades, measured from the fills of one
 * run.
 *
 * <pre>
 *   carry(i) = time of the first hedge fill at or after client fill i  -  time of client fill i
 * </pre>
 *
 * Every client fill counts once, whatever its size. A client fill with no hedge fill after it in
 * the session was netted by later client flow or held to the close; it has no carry time and is
 * counted in {@code unhedgedClientFills} instead. The clock starts when the client's acceptance is
 * booked, so the carry time includes the hedge trigger's wait (threshold, netting hold) and the
 * order latency to the venue. It is a time to the next hedge, not proof that the hedge offset that
 * particular fill: with netting, a hedge covers the net of many fills.
 */
public final class RiskCarry {

  private final long clientFills;
  private final long unhedgedClientFills;
  private final double[] carrySeconds;

  public RiskCarry(final long clientFills, final long unhedgedClientFills, final double[] carrySeconds) {
    this.clientFills = clientFills;
    this.unhedgedClientFills = unhedgedClientFills;
    this.carrySeconds = carrySeconds.clone();
    Arrays.sort(this.carrySeconds);
  }

  public long clientFills() {
    return clientFills;
  }

  public long unhedgedClientFills() {
    return unhedgedClientFills;
  }

  /** Carry time of each hedged client fill, seconds, ascending (a copy). */
  public double[] carrySeconds() {
    return carrySeconds.clone();
  }

  /** Measures the carry times of {@code fills} (any order). */
  public static RiskCarry of(final List<FillRecord> fills) {
    final List<FillRecord> sorted = new ArrayList<>(fills);
    sorted.sort((a, b) -> Long.compare(a.timeNanos(), b.timeNanos()));
    final long[] hedgeTimes = sorted.stream().filter(FillRecord::hedge).mapToLong(FillRecord::timeNanos).toArray();
    final List<Double> carry = new ArrayList<>();
    long clients = 0;
    long unhedged = 0;
    for (final FillRecord f : sorted) {
      if (f.hedge()) {
        continue;
      }
      clients++;
      int k = Arrays.binarySearch(hedgeTimes, f.timeNanos());
      if (k < 0) {
        k = -k - 1;
      } else {
        while (k > 0 && hedgeTimes[k - 1] == f.timeNanos()) {
          k--;
        }
      }
      if (k >= hedgeTimes.length) {
        unhedged++;
      } else {
        carry.add((hedgeTimes[k] - f.timeNanos()) / 1e9);
      }
    }
    final double[] c = carry.stream().mapToDouble(Double::doubleValue).sorted().toArray();
    return new RiskCarry(clients, unhedged, c);
  }

  /** The {@code q} quantile (0 to 1) of the carry times by linear interpolation; NaN if none. */
  public double quantile(final double q) {
    if (carrySeconds.length == 0) {
      return Double.NaN;
    }
    final double pos = q * (carrySeconds.length - 1);
    final int lo = (int) Math.floor(pos);
    final int hi = Math.min(lo + 1, carrySeconds.length - 1);
    return carrySeconds[lo] + (pos - lo) * (carrySeconds[hi] - carrySeconds[lo]);
  }

  /** Mean carry time in seconds; NaN if none. */
  public double mean() {
    return carrySeconds.length == 0 ? Double.NaN : Arrays.stream(carrySeconds).average().orElse(Double.NaN);
  }
}
