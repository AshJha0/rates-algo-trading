package io.ratesalgo.quoting;

/**
 * The evidence a {@link FairValueSignal} must carry before the engine uses it (DR-P3-03). The rule:
 * the out-of-sample window ends before the install time and starts after the fitting window ends;
 * it has at least {@link #MIN_OBSERVATIONS} observations; the signal's out-of-sample t-statistic
 * is at least {@link #MIN_T_STAT}; and the approver is not the author.
 *
 * @param signalName name used in reports
 * @param fitEndNanos end of the data the signal was fitted on
 * @param outOfSampleStartNanos start of the held-out window
 * @param outOfSampleEndNanos end of the held-out window
 * @param observations held-out observations
 * @param tStat held-out t-statistic of the signal's predictive coefficient
 * @param authorId who built the signal
 * @param approverId who reviewed the out-of-sample result
 */
public record ValidationRecord(
    String signalName,
    long fitEndNanos,
    long outOfSampleStartNanos,
    long outOfSampleEndNanos,
    long observations,
    double tStat,
    int authorId,
    int approverId) {

  /** Minimum held-out observations. */
  public static final long MIN_OBSERVATIONS = 250;

  /** Minimum held-out t-statistic. */
  public static final double MIN_T_STAT = 2.0;

  /** Whether the rule allows installing the signal at {@code nowNanos}. */
  public boolean permits(final long nowNanos) {
    return outOfSampleStartNanos >= fitEndNanos
        && outOfSampleEndNanos > outOfSampleStartNanos
        && outOfSampleEndNanos <= nowNanos
        && observations >= MIN_OBSERVATIONS
        && Math.abs(tStat) >= MIN_T_STAT
        && approverId != authorId;
  }
}
