package io.ratesalgo.analytics.curve;

import java.util.Arrays;

/**
 * Linear on the first nodes, a smooth scheme (monotone convex or tension spline) on the rest.
 *
 * <p>Plain idea: the overnight rate moves at central bank meetings and is flat between them, so
 * the front of an OIS curve is a step function of the forward rate, which is what linear log
 * discount factors between meeting dates give. Further out nothing is known about steps and a
 * step forward would be an artefact of where the pillars sit; there the forward should be
 * continuous, which the smooth scheme gives.
 *
 * <p>Formal statement: with nodes 0..N and the join at node s, y(x) is the linear interpolant of
 * nodes 0..s for x at or before t_s, and the smooth interpolant of nodes s..N (built as
 * if node s were its first node) for x after t_s. Both reprice every node, so y is continuous;
 * the forward -dy/dx may jump at t_s. With s = 0 this is the smooth scheme; with s at or
 * past the last active node it is the linear one, which is what the sequential bootstrap sees
 * while only step pillars are active.
 *
 * <p>The gradient is the gradient of whichever interpolant applies, placed at its nodes; {@code
 * StepThenSmoothInterpolatorTest} checks it against central differences.
 */
final class StepThenSmoothInterpolator extends Interpolator {

  private final int split;
  private final LinearInterpolator step;
  private final Interpolator convex;
  private final double[] convexT;
  private final double[] convexY;
  private final double[] convexGradient;
  private int count;
  private int convexCount;
  private double splitTime;

  /**
   * Creates the interpolator.
   *
   * @param capacity largest node count
   * @param split index of the node where the smooth section starts (the number of step pillars)
   * @param smooth interpolator of the smooth section, sized for {@code capacity} nodes
   */
  StepThenSmoothInterpolator(final int capacity, final int split, final Interpolator smooth) {
    this.split = split;
    this.step = new LinearInterpolator(capacity);
    this.convex = smooth;
    this.convexT = new double[capacity];
    this.convexY = new double[capacity];
    this.convexGradient = new double[capacity];
  }

  @Override
  void update(final double[] t, final double[] y, final int n) {
    count = n;
    final int stepCount = Math.min(n, split + 1);
    if (stepCount >= 2) {
      step.update(t, y, stepCount);
    }
    convexCount = n - split;
    if (convexCount >= 2) {
      System.arraycopy(t, split, convexT, 0, convexCount);
      System.arraycopy(y, split, convexY, 0, convexCount);
      convex.update(convexT, convexY, convexCount);
    }
    splitTime = t[Math.min(split, n - 1)];
  }

  private boolean inStep(final double x) {
    return convexCount < 2 || (split > 0 && x <= splitTime);
  }

  @Override
  double value(final double x) {
    return inStep(x) ? step.value(x) : convex.value(x);
  }

  @Override
  void gradient(final double x, final double[] out) {
    Arrays.fill(out, 0, count, 0.0);
    if (inStep(x)) {
      step.gradient(x, out);
      return;
    }
    convex.gradient(x, convexGradient);
    System.arraycopy(convexGradient, 0, out, split, convexCount);
  }
}
