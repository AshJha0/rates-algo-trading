package io.ratesalgo.strategy;

/**
 * Feature 1: hold client risk for a while so opposing flow can net it, and hedge only the
 * residual beyond a risk budget.
 *
 * <pre>
 *   X = net DV01 of the ladder (swaps, bonds and futures together)
 *   |X| &gt; hard                        hedge X - sign(X) * soft        at once
 *   |X| &gt; soft and age &gt;= hold        hedge X - sign(X) * retain      after the holding time
 *   otherwise                         hold
 * </pre>
 *
 * where {@code age} is the time since the latest client fill. Hedging to {@code retain} rather
 * than to zero keeps a little risk on the book for the next opposing client to take off at no
 * cost. Parameters: {@code soft} and {@code hard} in DV01 (major currency per bp), {@code hold}
 * in nanoseconds, {@code retain} in DV01 with {@code 0 <= retain <= soft <= hard}.
 */
public final class NettingHedgeBudget implements HedgeBudget {

  private final double softDv01;
  private final double hardDv01;
  private final double retainDv01;
  private final long holdNanos;

  public NettingHedgeBudget(
      final double softDv01, final double hardDv01, final double retainDv01, final long holdNanos) {
    if (!(softDv01 > 0) || !(hardDv01 >= softDv01) || !(retainDv01 >= 0 && retainDv01 <= softDv01)
        || holdNanos < 0) {
      throw new IllegalArgumentException(
          "netting budget needs 0 <= retain <= soft <= hard and hold >= 0: soft " + softDv01 + " hard "
              + hardDv01 + " retain " + retainDv01 + " hold " + holdNanos);
    }
    this.softDv01 = softDv01;
    this.hardDv01 = hardDv01;
    this.retainDv01 = retainDv01;
    this.holdNanos = holdNanos;
  }

  @Override
  public double excessDv01(final RiskLadder ladder, final long nanosSinceClientFill) {
    final double net = ladder.net();
    final double a = Math.abs(net);
    if (a > hardDv01) {
      return net - Math.signum(net) * softDv01;
    }
    if (a > softDv01 && nanosSinceClientFill >= holdNanos) {
      return net - Math.signum(net) * retainDv01;
    }
    return 0.0;
  }

  @Override
  public long holdNanos() {
    return holdNanos;
  }

  public double softDv01() {
    return softDv01;
  }

  public double hardDv01() {
    return hardDv01;
  }
}
