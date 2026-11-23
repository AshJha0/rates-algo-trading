package io.ratesalgo.quoting;

import io.ratesalgo.api.CurrencyCode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Quoting parameters (API_SPEC.md Section 12.1). Every default is a declared assumption named in
 * the table there with its unit. Changes go through the control plane.
 */
public final class QuotingConfig {

  private static final int MAX_EVENTS = 8;
  private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
  /** E3 holdout spread multiplier for the five minutes after 08:30 New York (data/presets.yaml). */
  static final double E3_RELEASE_MULTIPLIER = 2.355175216;

  long firmTtlNanos = 5_000_000_000L;
  long maxInputAgeNanos = 2_000_000_000L;
  long heartbeatTimeoutNanos = 3_000_000_000L;
  long requoteThrottleNanos = 250_000_000L;
  double minHalfSpreadBp = 0.10;
  double maxHalfSpreadBp = 10.0;
  double kappa = 2e-6;
  double maxSkewBp = 2.0;
  double maxImproveBp = 0.25;
  double baselineVolBp = 6.0;
  double volWideningPerBp = 0.05;
  double winnersCurseBp = 0.10;
  double learningRate = 0.01;
  long minQuoteQuantity = 100_000_000L;
  int maxLiveQuotes = 1024;
  int duplicateWindow = 4096;
  long markoutHorizonNanos = 60_000_000_000L;
  private final long[] eventStart = new long[MAX_EVENTS];
  private final long[] eventEnd = new long[MAX_EVENTS];
  private final double[] eventMultiplier = new double[MAX_EVENTS];
  private int eventCount;
  private long[] releases = usReleaseSchedule(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31));
  long releasePullBeforeNanos = 60_000_000_000L;
  long releasePullAfterNanos = 30_000_000_000L;
  long releaseWidenAfterNanos = 300_000_000_000L;
  double releaseMultiplier = E3_RELEASE_MULTIPLIER;
  double fairMoveWithdrawBp = 0.25;
  double fairMoveWithdrawFraction;
  double acceptPriceToleranceBp;
  boolean rejectMismatchedDone;
  boolean coverLearning;
  boolean widenAfterClamp;
  final int[] volReference = new int[CurrencyCode.COUNT];
  long volSampleNanos = 60_000_000_000L;
  long volDayNanos = 8L * 3_600_000_000_000L;
  double volDecay = 0.97;
  int volWarmupSamples = 30;
  StrategyConfig strategy = new StrategyConfig();

  /**
   * The default release calendar: 08:30 and 10:00 New York on every weekday from {@code from} to
   * {@code to} inclusive except 1 January and 25 December, as sorted epoch nanoseconds. A declared
   * assumption (PNL_AUDIT item 9): not every weekday carries a release at both times, and the
   * cost of pulling on a quiet day is 90 seconds of quoting.
   */
  public static long[] usReleaseSchedule(final LocalDate from, final LocalDate to) {
    final List<Long> out = new ArrayList<>();
    final LocalTime[] times = {LocalTime.of(8, 30), LocalTime.of(10, 0)};
    for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
      final DayOfWeek w = d.getDayOfWeek();
      final MonthDay md = MonthDay.from(d);
      if (w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY
          || md.equals(MonthDay.of(1, 1)) || md.equals(MonthDay.of(12, 25))) {
        continue;
      }
      for (final LocalTime t : times) {
        final Instant i = d.atTime(t).atZone(NEW_YORK).toInstant();
        out.add(i.getEpochSecond() * 1_000_000_000L + i.getNano());
      }
    }
    final long[] a = new long[out.size()];
    for (int i = 0; i < a.length; i++) {
      a[i] = out.get(i);
    }
    return a;
  }

  /** Validity of a firm RFQ quote. */
  public QuotingConfig firmTtlNanos(final long v) {
    firmTtlNanos = positive(v, "firmTtlNanos");
    return this;
  }

  /** Oldest input a quote may be priced from; also the age that pulls live quotes. */
  public QuotingConfig maxInputAgeNanos(final long v) {
    maxInputAgeNanos = positive(v, "maxInputAgeNanos");
    return this;
  }

  /** Silence from a heartbeat source after which every quote is pulled. */
  public QuotingConfig heartbeatTimeoutNanos(final long v) {
    heartbeatTimeoutNanos = positive(v, "heartbeatTimeoutNanos");
    return this;
  }

  /** Minimum interval between quotes to the same client on the same instrument. */
  public QuotingConfig requoteThrottleNanos(final long v) {
    requoteThrottleNanos = Math.max(0L, v);
    return this;
  }

  public QuotingConfig minHalfSpreadBp(final double v) {
    minHalfSpreadBp = nonNegative(v, "minHalfSpreadBp");
    return this;
  }

  public QuotingConfig maxHalfSpreadBp(final double v) {
    maxHalfSpreadBp = nonNegative(v, "maxHalfSpreadBp");
    return this;
  }

  /** Inventory risk aversion per unit of currency (Section 12.4). */
  public QuotingConfig kappa(final double v) {
    kappa = nonNegative(v, "kappa");
    return this;
  }

  /** Bound on the inventory skew, bp of value. */
  public QuotingConfig maxSkewBp(final double v) {
    maxSkewBp = nonNegative(v, "maxSkewBp");
    return this;
  }

  /** Furthest a quote may sit on the client's side of fair, bp of value. */
  public QuotingConfig maxImproveBp(final double v) {
    maxImproveBp = nonNegative(v, "maxImproveBp");
    return this;
  }

  /** Daily realised volatility (bp) at which no volatility widening applies. */
  public QuotingConfig baselineVolBp(final double v) {
    baselineVolBp = nonNegative(v, "baselineVolBp");
    return this;
  }

  /** Half-spread added per bp of daily realised volatility above the baseline. */
  public QuotingConfig volWideningPerBp(final double v) {
    volWideningPerBp = nonNegative(v, "volWideningPerBp");
    return this;
  }

  /** Scale of the winner's-curse term, bp per unit of sqrt(2 ln n). */
  public QuotingConfig winnersCurseBp(final double v) {
    winnersCurseBp = nonNegative(v, "winnersCurseBp");
    return this;
  }

  /** Step size of the hit-rate model's feedback update. */
  public QuotingConfig learningRate(final double v) {
    learningRate = nonNegative(v, "learningRate");
    return this;
  }

  /** Smallest quantity worth quoting, minor units; sizes round down to a multiple of it. */
  public QuotingConfig minQuoteQuantity(final long v) {
    minQuoteQuantity = positive(v, "minQuoteQuantity");
    return this;
  }

  public QuotingConfig maxLiveQuotes(final int v) {
    maxLiveQuotes = (int) positive(v, "maxLiveQuotes");
    return this;
  }

  /** Number of recent RFQ ids remembered for duplicate detection. */
  public QuotingConfig duplicateWindow(final int v) {
    duplicateWindow = (int) positive(v, "duplicateWindow");
    return this;
  }

  /** Delay after a fill at which its markout is measured for the toxicity table. */
  public QuotingConfig markoutHorizonNanos(final long v) {
    markoutHorizonNanos = positive(v, "markoutHorizonNanos");
    return this;
  }

  /** A scheduled event window (epoch nanoseconds) during which half-spreads are multiplied. */
  public QuotingConfig addEvent(final long startNanos, final long endNanos, final double multiplier) {
    if (eventCount == MAX_EVENTS || endNanos <= startNanos || !(multiplier >= 1.0)) {
      throw new IllegalArgumentException(
          "event " + startNanos + ".." + endNanos + " x" + multiplier + " (" + eventCount + " held)");
    }
    eventStart[eventCount] = startNanos;
    eventEnd[eventCount] = endNanos;
    eventMultiplier[eventCount] = multiplier;
    eventCount++;
    return this;
  }

  /** Largest event or release multiplier in force at {@code nowNanos}, or 1. */
  public double eventMultiplier(final long nowNanos) {
    double m = releaseMultiplier(nowNanos);
    for (int i = 0; i < eventCount; i++) {
      if (nowNanos >= eventStart[i] && nowNanos < eventEnd[i]) {
        m = Math.max(m, eventMultiplier[i]);
      }
    }
    return m;
  }

  /**
   * Scheduled release times, epoch nanoseconds (sorted on entry; an empty array turns the release
   * guard off). Quotes are pulled and RFQs passed from {@link #releasePullNanos before} each
   * release to {@code after} it; for {@link #releaseWidening} after it half-spreads are multiplied.
   */
  public QuotingConfig releaseCalendar(final long[] releaseNanos) {
    final long[] a = releaseNanos.clone();
    Arrays.sort(a);
    releases = a;
    return this;
  }

  /** The pull window around each release: {@code beforeNanos} before to {@code afterNanos} after. */
  public QuotingConfig releasePullNanos(final long beforeNanos, final long afterNanos) {
    if (beforeNanos < 0 || afterNanos < 0) {
      throw new IllegalArgumentException("release pull " + beforeNanos + ", " + afterNanos);
    }
    releasePullBeforeNanos = beforeNanos;
    releasePullAfterNanos = afterNanos;
    return this;
  }

  /** Half-spread multiplier from the release to {@code afterNanos} after it (outside the pull). */
  public QuotingConfig releaseWidening(final long afterNanos, final double multiplier) {
    if (afterNanos < 0 || !(multiplier >= 1.0)) {
      throw new IllegalArgumentException("release widening " + afterNanos + " x" + multiplier);
    }
    releaseWidenAfterNanos = afterNanos;
    releaseMultiplier = multiplier;
    return this;
  }

  /**
   * Adverse fair-value move, bp of value, at which a live quote is withdrawn before its time to
   * live; 0 turns the check off.
   */
  public QuotingConfig fairMoveWithdrawBp(final double v) {
    fairMoveWithdrawBp = nonNegative(v, "fairMoveWithdrawBp");
    return this;
  }

  /**
   * Opt-in (review 2026-11-23, Q7): withdraw a live quote once fair value has moved against it by
   * this fraction of the distance it was quoted at, in place of the absolute {@link
   * #fairMoveWithdrawBp}. 0.5 pulls a quote when half its edge is gone. The distance is floored at
   * {@code minHalfSpreadBp}, so a quote skewed through fair is not pulled on the first tick. 0, the
   * default, keeps the absolute threshold.
   */
  public QuotingConfig fairMoveWithdrawFraction(final double v) {
    fairMoveWithdrawFraction = nonNegative(v, "fairMoveWithdrawFraction");
    return this;
  }

  /**
   * Largest difference, bp of value, between the price a Done reports and the price we quoted
   * before the Done is flagged as a price mismatch. 0, the default, flags any difference: a venue
   * trades an RFQ at the quoted price or not at all.
   */
  public QuotingConfig acceptPriceToleranceBp(final double v) {
    acceptPriceToleranceBp = nonNegative(v, "acceptPriceToleranceBp");
    return this;
  }

  /**
   * What a mismatched Done does. Off (default): it is flagged and counted, and booked at the
   * venue's price so the risk engine is not blind to a position the venue believes we have. On: it
   * is flagged and not booked, for a desk that disputes such a trade with the venue at once.
   */
  public QuotingConfig rejectMismatchedDone(final boolean v) {
    rejectMismatchedDone = v;
    return this;
  }

  /**
   * Opt-in (review Q3): feed the gap between our winning price and the disclosed cover to {@link
   * SpreadModel#observeCoverGap}, so the optimiser averages over the learned cover spread. Off by
   * default: it changes the spread the back-tested desk quotes.
   */
  public QuotingConfig coverLearning(final boolean v) {
    coverLearning = v;
    return this;
  }

  /**
   * Opt-in (review Q11): apply the event, release and volatility widening after the half-spread
   * clamp instead of before it, so a cap set for a quiet market does not cancel the widening. The
   * quoted half-spread can then exceed {@code maxHalfSpreadBp} by the widening and no more. Off by
   * default: it changes what the back-tested desk quotes around releases.
   */
  public QuotingConfig widenAfterClamp(final boolean v) {
    widenAfterClamp = v;
    return this;
  }

  /**
   * Opt-in (review Q11): estimate a currency's daily realised volatility from the fair value of
   * {@code instrumentId} and feed it to the engine, which scales the winner's-curse term and the
   * volatility widening by it. The engine samples the fair value every {@link
   * #volatilitySampling sample interval} from its poll. Instrument 0 (the default) turns it off
   * and leaves volatility at the baseline.
   */
  public QuotingConfig volatilityReference(final CurrencyCode currency, final int instrumentId) {
    volReference[currency.index()] = instrumentId;
    return this;
  }

  /**
   * How realised volatility is estimated: a sample every {@code sampleNanos}, an exponentially
   * weighted variance with weight {@code decay} on the past, scaled to a trading day of {@code
   * dayNanos}, and reported only after {@code warmupSamples} samples. The estimate is floored at
   * the baseline, so a quiet market never tightens a quote.
   */
  public QuotingConfig volatilitySampling(
      final long sampleNanos, final long dayNanos, final double decay, final int warmupSamples) {
    if (sampleNanos <= 0 || dayNanos < sampleNanos || !(decay > 0.0 && decay < 1.0) || warmupSamples < 1) {
      throw new IllegalArgumentException(
          "volatility sampling " + sampleNanos + ", " + dayNanos + ", " + decay + ", " + warmupSamples);
    }
    volSampleNanos = sampleNanos;
    volDayNanos = dayNanos;
    volDecay = decay;
    volWarmupSamples = warmupSamples;
    return this;
  }

  /**
   * The Round 5 strategy capabilities (docs/research/STRATEGY_V2.md); every switch off by
   * default. {@code null} restores the default.
   */
  public QuotingConfig strategy(final StrategyConfig v) {
    strategy = v == null ? new StrategyConfig() : v;
    return this;
  }

  /** The strategy capabilities in force. */
  public StrategyConfig strategy() {
    return strategy;
  }

  /** Whether any currency has a volatility reference instrument. */
  boolean volatilityEstimated() {
    for (final int id : volReference) {
      if (id != 0) {
        return true;
      }
    }
    return false;
  }

  /** Number of scheduled releases held. */
  public int releases() {
    return releases.length;
  }

  /** True from the pull start before a release to the pull end after it. Allocation-free. */
  public boolean inReleasePull(final long nowNanos) {
    final int i = lastReleaseAtOrBefore(nowNanos + releasePullBeforeNanos);
    return i >= 0 && nowNanos - releases[i] < releasePullAfterNanos;
  }

  /** Release multiplier if {@code nowNanos} is within the widening period after a release, else 1. */
  double releaseMultiplier(final long nowNanos) {
    final int i = lastReleaseAtOrBefore(nowNanos);
    return i >= 0 && nowNanos - releases[i] < releaseWidenAfterNanos ? releaseMultiplier : 1.0;
  }

  private int lastReleaseAtOrBefore(final long t) {
    int lo = 0;
    int hi = releases.length - 1;
    int found = -1;
    while (lo <= hi) {
      final int mid = (lo + hi) >>> 1;
      if (releases[mid] <= t) {
        found = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return found;
  }

  public long firmTtlNanos() {
    return firmTtlNanos;
  }

  public long maxInputAgeNanos() {
    return maxInputAgeNanos;
  }

  public long minQuoteQuantity() {
    return minQuoteQuantity;
  }

  public double kappa() {
    return kappa;
  }

  public double maxSkewBp() {
    return maxSkewBp;
  }

  public double maxImproveBp() {
    return maxImproveBp;
  }

  public double minHalfSpreadBp() {
    return minHalfSpreadBp;
  }

  public double maxHalfSpreadBp() {
    return maxHalfSpreadBp;
  }

  private static long positive(final long v, final String name) {
    if (v <= 0) {
      throw new IllegalArgumentException(name + " must be positive: " + v);
    }
    return v;
  }

  private static double nonNegative(final double v, final String name) {
    if (!(v >= 0.0)) {
      throw new IllegalArgumentException(name + " must be non-negative: " + v);
    }
    return v;
  }
}
