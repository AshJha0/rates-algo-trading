package io.ratesalgo.analytics.curve;

import io.ratesalgo.analytics.swap.OisSwap;
import io.ratesalgo.analytics.swap.OisSwapPricer;
import io.ratesalgo.analytics.swap.SwapConvention;

/**
 * Leave-one-out par rates of the build swaps of a {@link SofrCurve}: what the curve says a swap
 * is worth when its own quote is not among the inputs.
 *
 * <p>Plain idea. A swap that is both quoted and a curve input reprices to its own quote, so the
 * curve has no opinion about it. Take that swap out, rebuild from the others, and price it: the
 * result depends on every quote but its own. A stale or skewed quote then cannot become its own
 * fair value through the curve.
 *
 * <p>Formal statement. For build swap k, let C_k be the curve bootstrapped from the build set
 * without k (same interpolation, one pillar fewer). The leave-one-out rate is L_k = par rate of
 * swap k on C_k. By construction dL_k / dq_k = 0.
 *
 * <p>Cost. Rebuilding one curve per swap on every market data round would cost several
 * bootstraps. Instead {@link #reference} does the exact rebuilds at a reference set of quotes and
 * stores L_k and its gradient dL_k / dq_j for j other than k; {@link #value} returns the first
 * order value at the curve's current quotes. The par rate is close to linear in the quotes, so
 * the error is second order in the move since the reference; {@code
 * SofrCurveTest.leaveOneOutLinearisationError} bounds it for a 10 bp move and the binding calls
 * {@link #reference} again before {@link #moveSinceReference} reaches its limit. {@link #exact}
 * is the full computation for one swap.
 *
 * <p>{@link #value} and {@link #moveSinceReference} do not allocate. Single-threaded.
 */
public final class SofrLeaveOneOut {

  private final SofrCurve full;
  private final int n;
  private final int[] inputOfSwap;
  private final int[] swapOfInput;
  private final CurveBuilder[] builders;
  private final OisSwap[] swaps;
  private final int[][] inputOfBuilderIndex;
  private final double[][] sensitivity;
  private final double[] referenceValue;
  private final double[] referenceQuotes;
  private final boolean[] built;
  private final double[] ordered;
  private final double[] row;
  private final double[] work;
  private final OisSwapPricer pricer = new OisSwapPricer();
  private long references;

  /** Prepares one reduced curve per build swap of {@code full} and takes the first reference. */
  public SofrLeaveOneOut(final SofrCurve full) {
    this.full = full;
    this.n = full.inputCount();
    int count = 0;
    swapOfInput = new int[n];
    for (int i = 0; i < n; i++) {
      swapOfInput[i] = full.kind(i) == SofrCurve.Kind.SWAP ? count++ : -1;
    }
    inputOfSwap = new int[count];
    builders = new CurveBuilder[count];
    swaps = new OisSwap[count];
    inputOfBuilderIndex = new int[count][n - 1];
    sensitivity = new double[count][n];
    referenceValue = new double[count];
    referenceQuotes = new double[n];
    built = new boolean[count];
    ordered = new double[n];
    row = new double[n];
    work = new double[n];
    for (int i = 0; i < n; i++) {
      final int k = swapOfInput[i];
      if (k < 0) {
        continue;
      }
      inputOfSwap[k] = i;
      // its own instrument objects: the reduced curve must not see the full curve's quote
      // changes before it is told to re-solve
      final SofrCurve.Layout layout =
          new SofrCurve.Layout(full.valuationDate(), full.spec(), full.quote(i));
      builders[k] = layout.builder(full.valuationDate(), full.spec(), full.fixings(), i);
      for (int b = 0; b < n - 1; b++) {
        final CurveInstrument inst = builders[k].instruments().get(b);
        inputOfBuilderIndex[k][b] = layout.instruments.indexOf(inst);
      }
      swaps[k] =
          OisSwap.spot(
              SwapConvention.USD_SOFR, full.valuationDate(), full.tenorMonths(i), 1.0, 0.0, true);
    }
    reference();
  }

  /**
   * Rebuilds every reduced curve exactly at the full curve's current quotes and stores the values
   * and gradients {@link #value} extrapolates from.
   *
   * @throws IllegalStateException if a reduced curve cannot be solved; the previous reference of
   *     the swaps not yet reached is kept
   */
  public void reference() {
    for (int k = 0; k < builders.length; k++) {
      solve(k);
      referenceValue[k] =
          pricer.parRate(swaps[k], builders[k].curve(), full.fixings(), full.valuationDate());
      pricer.parRateGradient(
          swaps[k], builders[k].curve(), full.fixings(), full.valuationDate(), row, work);
      builders[k].quoteSensitivities(row);
      java.util.Arrays.fill(sensitivity[k], 0.0);
      for (int b = 0; b < n - 1; b++) {
        sensitivity[k][inputOfBuilderIndex[k][b]] = row[b];
      }
    }
    for (int i = 0; i < n; i++) {
      referenceQuotes[i] = full.quote(i);
    }
    references++;
  }

  private void solve(final int k) {
    final CurveBuilder b = builders[k];
    for (int j = 0; j < n - 1; j++) {
      ordered[j] = full.quote(inputOfBuilderIndex[k][j]);
    }
    if (built[k]) {
      b.rebuildAll(ordered);
      return;
    }
    for (int j = 0; j < n - 1; j++) {
      b.instruments().get(j).setQuote(ordered[j]);
    }
    b.build(CurveBuilder.Method.AUTO);
    built[k] = true;
  }

  /** Number of build swaps. */
  public int swapCount() {
    return builders.length;
  }

  /** Input index (in the full curve) of build swap k. */
  public int inputOfSwap(final int k) {
    return inputOfSwap[k];
  }

  /** Index among the build swaps of a full-curve input, or -1 if the input is not a swap. */
  public int swapOfInput(final int input) {
    return swapOfInput[input];
  }

  /** Leave-one-out par rate of build swap k at the full curve's current quotes, first order. */
  public double value(final int k) {
    double v = referenceValue[k];
    final double[] s = sensitivity[k];
    for (int i = 0; i < n; i++) {
      v += s[i] * (full.quote(i) - referenceQuotes[i]);
    }
    return v;
  }

  /** Leave-one-out par rate of build swap k at the current quotes, by a full rebuild. */
  public double exact(final int k) {
    solve(k);
    return pricer.parRate(swaps[k], builders[k].curve(), full.fixings(), full.valuationDate());
  }

  /**
   * dL_k / dq_j in rate per rate: how much of input j's move reaches the leave-one-out rate of
   * build swap k. Zero for j = k.
   */
  public double weight(final int k, final int input) {
    return sensitivity[k][input] * full.quotePerRate(input);
  }

  /** Largest move of any input since the last {@link #reference}, in rate (decimal). */
  public double moveSinceReference() {
    double max = 0.0;
    for (int i = 0; i < n; i++) {
      max =
          Math.max(max, Math.abs((full.quote(i) - referenceQuotes[i]) / full.quotePerRate(i)));
    }
    return max;
  }

  /** Exact references taken so far, the first included. */
  public long references() {
    return references;
  }
}
