package io.ratesalgo.analytics.curve;

/**
 * Interpolation of a discount curve between pillars. Every scheme works on y(t) = ln P(0, t), the
 * log discount factor, and beyond the last pillar every scheme extends the last segment's average
 * forward rate (flat-forward extrapolation).
 *
 * <ul>
 *   <li>{@link #LINEAR_LOG_DF}: y linear between pillars, so the instantaneous forward is a step
 *       function. Local: a pillar moves only its two neighbouring segments. Matches QuantLib {@code
 *       PiecewiseLogLinearDiscount}, which the golden tests use.
 *   <li>{@link #MONOTONE_CONVEX}: Hagan and West (2006, Applied Mathematical Finance 13(2)) on the
 *       discrete forwards, without the positivity amendment so negative EUR rates are kept.
 *       Forwards are continuous and stay within the range of the neighbouring discrete forwards.
 *   <li>{@link #TENSION_SPLINE}: exponential tension spline on y with natural end conditions
 *       (Schweikert 1966; Renka 1987, SIAM J. Sci. Stat. Comput. 8(3)), tension p per year. As p
 *       tends to zero it is the natural cubic spline; as p grows it tends to linear on y.
 * </ul>
 */
public enum Interpolation {
  LINEAR_LOG_DF,
  MONOTONE_CONVEX,
  TENSION_SPLINE;

  /** True if moving one pillar changes the curve only on its two adjacent segments. */
  public boolean isLocal() {
    return this == LINEAR_LOG_DF;
  }
}
