package io.ratesalgo.analytics.risk;

import io.ratesalgo.analytics.curve.CurveBuilder;
import io.ratesalgo.analytics.curve.CurveInstrument;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * Key-rate DV01 ladders against the inputs of a bootstrapped curve, two ways.
 *
 * <ul>
 *   <li>{@link #fromGradient}: algorithmic differentiation. The pricer supplies dV / d ln P at
 *       each pillar; the bootstrap Jacobian J (implied quotes in ln P) maps it to dV / d quote by
 *       solving J^T x = g. Key-rate i is x_i times the quote change per basis point of rate. The
 *       parallel DV01 is the sum, so the key-rate sum equals the parallel DV01 to rounding (the
 *       test asserts 1e-9 relative).
 *   <li>{@link #byBumping}: each quote moved by plus and minus {@code bump} in rate (one basis
 *       point by default), the curve rebuilt incrementally, the position revalued; central
 *       difference scaled to one basis point. The parallel DV01 bumps every quote together.
 *       Agrees with the gradient to relative 1e-4 or better at a one basis point bump (central
 *       differences remove the convexity term).
 * </ul>
 */
public final class KeyRateRisk {

  /** One basis point in decimal rate. */
  public static final double BP = 1e-4;

  private KeyRateRisk() {}

  /**
   * Ladder from the gradient of a value with respect to ln P at the curve's pillars. The gradient
   * array is overwritten.
   */
  public static Dv01Ladder fromGradient(
      final CurveBuilder builder, final String currency, final double[] gradient) {
    builder.quoteSensitivities(gradient);
    final List<CurveInstrument> inst = builder.instruments();
    final double[] kr = new double[inst.size()];
    final List<String> tenors = new ArrayList<>(inst.size());
    double parallel = 0.0;
    for (int i = 0; i < kr.length; i++) {
      kr[i] = gradient[i] * inst.get(i).quotePerRate() * BP;
      parallel += kr[i];
      tenors.add(inst.get(i).tenor());
    }
    return new Dv01Ladder(currency, tenors, kr, parallel);
  }

  /**
   * Ladder by central bump-and-rebuild. {@code value} must revalue the position on the builder's
   * curve each time it is called. The curve is restored to its original quotes afterwards.
   */
  public static Dv01Ladder byBumping(
      final CurveBuilder builder,
      final String currency,
      final DoubleSupplier value,
      final double bump) {
    final List<CurveInstrument> inst = builder.instruments();
    final int n = inst.size();
    final double[] base = new double[n];
    final List<String> tenors = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      base[i] = inst.get(i).quote();
      tenors.add(inst.get(i).tenor());
    }
    final double scale = BP / (2.0 * bump);
    final double[] kr = new double[n];
    for (int i = 0; i < n; i++) {
      final double dq = bump * inst.get(i).quotePerRate();
      builder.rebuild(i, base[i] + dq);
      final double up = value.getAsDouble();
      builder.rebuild(i, base[i] - dq);
      final double down = value.getAsDouble();
      builder.rebuild(i, base[i]);
      kr[i] = (up - down) * scale;
    }
    final double parallel = (shiftAll(builder, base, bump, value)
        - shiftAll(builder, base, -bump, value)) * scale;
    for (int i = 0; i < n; i++) {
      inst.get(i).setQuote(base[i]);
    }
    builder.build(CurveBuilder.Method.AUTO);
    return new Dv01Ladder(currency, tenors, kr, parallel);
  }

  private static double shiftAll(
      final CurveBuilder builder, final double[] base, final double bump,
      final DoubleSupplier value) {
    final List<CurveInstrument> inst = builder.instruments();
    for (int i = 0; i < base.length; i++) {
      inst.get(i).setQuote(base[i] + bump * inst.get(i).quotePerRate());
    }
    builder.build(CurveBuilder.Method.AUTO);
    return value.getAsDouble();
  }
}
