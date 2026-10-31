package io.ratesalgo.analytics.swap;

import io.ratesalgo.analytics.date.BusinessDayConvention;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.date.HolidayCalendar;
import io.ratesalgo.analytics.date.ImmDates;
import io.ratesalgo.analytics.date.Schedule;
import io.ratesalgo.analytics.date.StubRule;
import java.util.Arrays;

/**
 * The definition of a fixed versus compounded overnight rate swap: both schedules and, for every
 * floating period, the daily compounding grid. Built once (it allocates); {@link OisSwapPricer}
 * prices it without allocating.
 *
 * <p>The daily grid follows ISDA 2021 Definitions Section 7.6 (Overnight Rate Compounding) and is
 * built exactly as QuantLib 1.44 {@code OvernightIndexedCoupon} builds it, so that lookback,
 * lockout and observation shift agree with the golden file:
 *
 * <ul>
 *   <li>value dates: every fixing-calendar business day from the period start (moved back to a
 *       fixing day if needed) to the period end (moved forward);
 *   <li>with a lookback of L days, each observation date is moved back L fixing days;
 *   <li>with a lockout of K days, the last K observations repeat the one K days before the end;
 *   <li>day i accrues over [interest date i, interest date i+1), or over the shifted observation
 *       period when the observation shift applies.
 * </ul>
 */
public final class OisSwap {

  private final SwapConvention convention;
  private final int startDate;
  private final int endDate;
  private final double notional;
  private final double fixedRate;
  private final boolean payer;

  // fixed leg
  final int[] fixedStart;
  final int[] fixedEnd;
  final int[] fixedPay;
  final double[] fixedTau;

  // floating periods
  final int[] floatStart;
  final int[] floatEnd;
  final int[] floatPay;
  final double[] floatAccrual;
  final double[] floatRateTau;
  final int[] dayOffset;

  // daily grid, flattened over periods
  final int[] fixingDate;
  final int[] fixingMaturity;
  final double[] fixingTau;
  final double[] dayTau;
  final boolean[] telescopic;
  final int[] valueStart;
  final int[] valueEnd;

  private OisSwap(
      final SwapConvention convention,
      final int start,
      final int end,
      final double notional,
      final double fixedRate,
      final boolean payer) {
    this.convention = convention;
    this.notional = notional;
    this.fixedRate = fixedRate;
    this.payer = payer;
    final HolidayCalendar cal = convention.scheduleCalendar();
    final BusinessDayConvention bdc = convention.convention();
    final Schedule fixed =
        Schedule.generate(
            start, end, convention.fixedPeriodMonths(), cal, bdc, bdc, StubRule.SHORT_FRONT,
            false);
    final Schedule flt =
        Schedule.generate(
            start, end, convention.floatPeriodMonths(), cal, bdc, bdc, StubRule.SHORT_FRONT,
            false);
    this.startDate = fixed.date(0);
    this.endDate = fixed.date(fixed.size() - 1);
    final HolidayCalendar payCal = convention.paymentCalendar();
    final int lag = convention.paymentLag();
    final int nf = fixed.periodCount();
    fixedStart = new int[nf];
    fixedEnd = new int[nf];
    fixedPay = new int[nf];
    fixedTau = new double[nf];
    for (int i = 0; i < nf; i++) {
      fixedStart[i] = fixed.start(i);
      fixedEnd[i] = fixed.end(i);
      fixedPay[i] =
          payCal.advanceBusinessDays(fixedEnd[i], lag, BusinessDayConvention.FOLLOWING);
      fixedTau[i] = convention.fixedDayCount().yearFraction(fixedStart[i], fixedEnd[i]);
    }
    final int np = flt.periodCount();
    floatStart = new int[np];
    floatEnd = new int[np];
    floatPay = new int[np];
    floatAccrual = new double[np];
    floatRateTau = new double[np];
    dayOffset = new int[np + 1];
    final HolidayCalendar fixCal = convention.fixingCalendar();
    int total = 0;
    for (int p = 0; p < np; p++) {
      final int a = fixCal.adjust(flt.start(p), BusinessDayConvention.PRECEDING);
      final int b = fixCal.adjust(flt.end(p), BusinessDayConvention.FOLLOWING);
      total += fixCal.businessDaysBetween(a, b + 1) - 1;
    }
    fixingDate = new int[total];
    fixingMaturity = new int[total];
    fixingTau = new double[total];
    dayTau = new double[total];
    telescopic = new boolean[total];
    valueStart = new int[total];
    valueEnd = new int[total];
    final int lookback = convention.lookbackDays();
    final int lockout = convention.lockoutDays();
    final boolean shift = convention.observationShift() && lookback > 0;
    int off = 0;
    for (int p = 0; p < np; p++) {
      final int s = flt.start(p);
      final int e = flt.end(p);
      floatStart[p] = s;
      floatEnd[p] = e;
      floatPay[p] = payCal.advanceBusinessDays(e, lag, BusinessDayConvention.FOLLOWING);
      floatAccrual[p] = convention.floatDayCount().yearFraction(s, e);
      final int a = fixCal.adjust(s, BusinessDayConvention.PRECEDING);
      final int b = fixCal.adjust(e, BusinessDayConvention.FOLLOWING);
      final int n = fixCal.businessDaysBetween(a, b + 1) - 1;
      final int[] value = new int[n + 1];
      int d = a;
      for (int i = 0; i <= n; i++) {
        value[i] = d;
        d = fixCal.advanceBusinessDays(d, 1);
      }
      final int[] interest = Arrays.copyOf(value, n + 1);
      interest[0] = s;
      interest[n] = e;
      final int[] fix = new int[n];
      if (lookback == 0) {
        System.arraycopy(value, 0, fix, 0, n);
      } else {
        for (int i = 0; i <= n; i++) {
          final int tmp = fixCal.advanceBusinessDays(value[i], -lookback);
          if (i < n) {
            fix[i] = tmp;
          }
          value[i] = tmp;
        }
      }
      if (lockout != 0) {
        if (lockout < 0 || lockout >= n) {
          throw new IllegalArgumentException(
              "lockout " + lockout + " outside (0, " + n + ") for period " + Dates.format(s));
        }
        final int lockDate = fix[n - 1 - lockout];
        for (int i = n - lockout; i < n; i++) {
          fix[i] = lockDate;
        }
      }
      final int[] accrualDates = shift ? value : interest;
      dayOffset[p] = off;
      for (int i = 0; i < n; i++) {
        final int k = off + i;
        fixingDate[k] = fix[i];
        fixingMaturity[k] = fixCal.advanceBusinessDays(fix[i], 1);
        fixingTau[k] = convention.floatDayCount().yearFraction(fix[i], fixingMaturity[k]);
        dayTau[k] = convention.floatDayCount().yearFraction(accrualDates[i], accrualDates[i + 1]);
        valueStart[k] = fix[i];
        valueEnd[k] = fixingMaturity[k];
        telescopic[k] =
            accrualDates[i] == fix[i] && accrualDates[i + 1] == fixingMaturity[k];
      }
      off += n;
      floatRateTau[p] =
          shift
              ? convention.floatDayCount().yearFraction(value[0], value[n])
              : convention.floatDayCount().yearFraction(interest[0], interest[n]);
    }
    dayOffset[np] = off;
  }

  /**
   * A swap with explicit start and unadjusted end dates.
   *
   * @param convention market conventions
   * @param start effective date (a business day on the schedule calendar)
   * @param end unadjusted termination date
   * @param notional notional in currency units
   * @param fixedRate fixed rate, decimal
   * @param payer true if paying fixed and receiving the overnight leg
   */
  public static OisSwap of(
      final SwapConvention convention,
      final int start,
      final int end,
      final double notional,
      final double fixedRate,
      final boolean payer) {
    return new OisSwap(convention, start, end, notional, fixedRate, payer);
  }

  /** A spot-starting swap of the given tenor in months. */
  public static OisSwap spot(
      final SwapConvention convention,
      final int tradeDate,
      final int tenorMonths,
      final double notional,
      final double fixedRate,
      final boolean payer) {
    final int start = convention.spotDate(tradeDate);
    return of(convention, start, Dates.addMonths(start, tenorMonths), notional, fixedRate, payer);
  }

  /**
   * A forward-starting swap: start is spot plus {@code forwardMonths}, moved to the next business
   * day, and the tenor runs from there.
   */
  public static OisSwap forwardStart(
      final SwapConvention convention,
      final int tradeDate,
      final int forwardMonths,
      final int tenorMonths,
      final double notional,
      final double fixedRate,
      final boolean payer) {
    final int start =
        convention
            .scheduleCalendar()
            .adjust(
                Dates.addMonths(convention.spotDate(tradeDate), forwardMonths),
                BusinessDayConvention.FOLLOWING);
    return of(convention, start, Dates.addMonths(start, tenorMonths), notional, fixedRate, payer);
  }

  /**
   * An IMM swap: start on the first IMM date after spot (moved by the convention if it is a
   * holiday), end on the IMM date {@code tenorMonths} later.
   */
  public static OisSwap imm(
      final SwapConvention convention,
      final int tradeDate,
      final int tenorMonths,
      final double notional,
      final double fixedRate,
      final boolean payer) {
    final int imm = ImmDates.next(convention.spotDate(tradeDate));
    final int start = convention.scheduleCalendar().adjust(imm, convention.convention());
    final int endMonth = Dates.addMonths(imm, tenorMonths);
    final int end = ImmDates.thirdWednesday(Dates.year(endMonth), Dates.month(endMonth));
    return of(convention, start, end, notional, fixedRate, payer);
  }

  /** The same swap with another fixed rate. */
  public OisSwap withFixedRate(final double rate) {
    return new OisSwap(convention, startDate, endDate, notional, rate, payer);
  }

  public SwapConvention convention() {
    return convention;
  }

  public int startDate() {
    return startDate;
  }

  /** Adjusted end date of the last period. */
  public int endDate() {
    return endDate;
  }

  /** Last payment date on either leg. */
  public int lastPaymentDate() {
    return Math.max(fixedPay[fixedPay.length - 1], floatPay[floatPay.length - 1]);
  }

  public double notional() {
    return notional;
  }

  public double fixedRate() {
    return fixedRate;
  }

  public boolean isPayer() {
    return payer;
  }

  /** Number of fixed periods. */
  public int fixedPeriodCount() {
    return fixedStart.length;
  }

  /** Number of floating periods. */
  public int floatPeriodCount() {
    return floatStart.length;
  }

  /** Fixed period i: start, end, payment as {start, end, pay}. */
  public int[] fixedPeriod(final int i) {
    return new int[] {fixedStart[i], fixedEnd[i], fixedPay[i]};
  }

  /** Floating period i: start, end, payment as {start, end, pay}. */
  public int[] floatPeriod(final int i) {
    return new int[] {floatStart[i], floatEnd[i], floatPay[i]};
  }

  /** Fixing (observation) dates of floating period p, in compounding order. */
  public int[] fixingDates(final int p) {
    return Arrays.copyOfRange(fixingDate, dayOffset[p], dayOffset[p + 1]);
  }

  /** Accrual weights (year fractions) of each compounding day of floating period p. */
  public double[] compoundingWeights(final int p) {
    return Arrays.copyOfRange(dayTau, dayOffset[p], dayOffset[p + 1]);
  }
}
