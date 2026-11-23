package io.ratesalgo.analytics.curve;

import io.ratesalgo.analytics.date.Calendars;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.date.DayCount;
import io.ratesalgo.analytics.date.FomcCalendar;
import io.ratesalgo.analytics.date.HolidayCalendar;
import io.ratesalgo.analytics.date.ImmDates;
import io.ratesalgo.analytics.math.LuSolver;
import io.ratesalgo.analytics.swap.FixingHistory;
import io.ratesalgo.analytics.swap.OisSwap;
import io.ratesalgo.analytics.swap.OisSwapPricer;
import io.ratesalgo.analytics.swap.SwapConvention;
import java.util.ArrayList;
import java.util.List;

/**
 * The USD SOFR curve of pricing v2: the build set of a {@link SofrCurveSpec} bootstrapped on one
 * valuation date, and the map from curve risk to the risk tenors.
 *
 * <p>Plain idea. The curve is built from what trades: the overnight rate, futures at the front,
 * swaps further out. Risk is reported on eight par swap tenors. The two lists are not the same
 * list, so the report needs a rule for turning "the value moves this much when build quote i
 * moves" into "the value moves this much when the 5-year swap rate moves". The rule here is the
 * Jacobian: move the build quotes in the one pattern that raises the 5-year risk swap by a basis
 * point and leaves the other seven risk swaps where they were, and report the value change.
 *
 * <p>Formal statement. Let q be the N build quotes in rate units, rho(q) the R risk par rates and
 * K = d rho / d q (R by N), from the bootstrap Jacobian. Let W (N by R) spread a unit move of risk
 * tenor k onto the build quotes by hat functions of maturity: instrument i with maturity T_i
 * between risk tenors a and b takes (b - T_i)/(b - a) of a and the rest of b, all of the first
 * tenor before it, all of the last after it. The columns of W span the moves the report can see.
 * The move that raises risk rate k alone by one unit is column k of G = W (K W)^-1, since K G = I.
 * For a value V with dV/dq = x, the ladder is d = G^T x, computed as (K W)^T d = W^T x. When every
 * risk tenor is a build swap, K W is the identity and the ladder is W^T x: each bucket holds its
 * own pillar plus the hat share of the instruments between tenors.
 *
 * <p>The parallel DV01 is the value change when every risk par rate rises one basis point, G 1
 * applied to x. It equals the sum of the buckets by linearity; {@link #riskParallel} computes it
 * by a different route (a forward solve, where the ladder uses a transposed solve) so that the
 * test of "sum of buckets equals parallel" compares two computations.
 *
 * <p>Worked example ({@code SofrCurveTest.everyRiskBucketIsPopulatedByItsOwnSwap}): a unit 7-year
 * payer swap on the standard curve has its whole DV01 in the 7-year bucket; under the five-pillar
 * curve of pricing v1 the same swap put 60 percent on 5 years and 40 percent on 10.
 *
 * <p>Single-threaded. {@link #update} and the risk methods do not allocate.
 */
public final class SofrCurve {

  /** What a build input is. */
  public enum Kind {
    /** The overnight fixing estimate, a simple Act/360 rate. */
    OVERNIGHT,
    /** A one-month SOFR future, quoted as a price. */
    SR1,
    /** A three-month SOFR future, quoted as a price. */
    SR3,
    /** A par OIS swap, a decimal rate. */
    SWAP
  }

  /** Days per year used to place a futures or overnight input among the risk tenors. */
  private static final double DAYS_PER_YEAR = 365.25;

  private final int today;
  private final SofrCurveSpec spec;
  private final FixingHistory fixings;
  private final Layout layout;
  private final CurveBuilder builder;
  private final DiscountCurve curve;
  private final OisSwapPricer pricer = new OisSwapPricer();
  private final int n;
  private final int riskCount;
  private final int[] builderIndex;
  private final double[] perRate;
  private final double[] perRateOrdered;
  private final OisSwap[] riskSwap;
  private final double[] hat;
  private final double[] riskMatrix;
  private final LuSolver riskLu;
  private final double[] row;
  private final double[] work;
  private final double[] ordered;
  private final double[] riskWork;

  /**
   * Builds the curve with every input at one flat rate; call {@link #update} with market quotes.
   *
   * @param today valuation date, a SOFR business day
   * @param spec the build and risk sets
   * @param fixings published fixings, needed from the first day of the reference period of any
   *     future that has started
   * @param flatRate starting level, decimal
   */
  public SofrCurve(
      final int today, final SofrCurveSpec spec, final FixingHistory fixings,
      final double flatRate) {
    this.today = today;
    this.spec = spec;
    this.fixings = fixings;
    this.layout = new Layout(today, spec, flatRate);
    this.builder = layout.builder(today, spec, fixings, -1);
    this.curve = builder.curve();
    this.n = layout.instruments.size();
    builder.build(CurveBuilder.Method.AUTO);
    builderIndex = new int[n];
    perRate = new double[n];
    perRateOrdered = new double[n];
    final List<CurveInstrument> sorted = builder.instruments();
    for (int i = 0; i < n; i++) {
      builderIndex[i] = sorted.indexOf(layout.instruments.get(i));
      perRate[i] = layout.instruments.get(i).quotePerRate();
      perRateOrdered[builderIndex[i]] = perRate[i];
    }
    final int[] riskMonths = spec.riskTenorMonths();
    riskCount = riskMonths.length;
    riskSwap = new OisSwap[riskCount];
    final double[] riskYears = new double[riskCount];
    for (int k = 0; k < riskCount; k++) {
      riskSwap[k] = OisSwap.spot(SwapConvention.USD_SOFR, today, riskMonths[k], 1.0, 0.0, true);
      riskYears[k] = riskMonths[k] / 12.0;
    }
    hat = new double[n * riskCount];
    for (int i = 0; i < n; i++) {
      hatWeights(layout.years[i], riskYears, hat, builderIndex[i] * riskCount);
    }
    riskMatrix = new double[riskCount * riskCount];
    riskLu = new LuSolver(riskCount);
    row = new double[n];
    work = new double[n];
    ordered = new double[n];
    riskWork = new double[riskCount];
    refreshRiskMap();
  }

  /** Hat weights of a maturity on increasing tenors: flat outside, linear between. */
  static void hatWeights(
      final double years, final double[] tenorYears, final double[] out, final int offset) {
    final int r = tenorYears.length;
    for (int k = 0; k < r; k++) {
      out[offset + k] = 0.0;
    }
    if (years <= tenorYears[0]) {
      out[offset] = 1.0;
      return;
    }
    if (years >= tenorYears[r - 1]) {
      out[offset + r - 1] = 1.0;
      return;
    }
    int k = 1;
    while (tenorYears[k] < years) {
      k++;
    }
    final double w = (years - tenorYears[k - 1]) / (tenorYears[k] - tenorYears[k - 1]);
    out[offset + k - 1] = 1.0 - w;
    out[offset + k] = w;
  }

  private void refreshRiskMap() {
    for (int j = 0; j < riskCount; j++) {
      pricer.parRateGradient(riskSwap[j], curve, fixings, today, row, work);
      builder.quoteSensitivities(row);
      for (int k = 0; k < riskCount; k++) {
        double s = 0.0;
        for (int b = 0; b < n; b++) {
          s += row[b] * perRateOrdered[b] * hat[b * riskCount + k];
        }
        riskMatrix[j * riskCount + k] = s;
      }
    }
    riskLu.factor(riskMatrix, riskCount);
  }

  /** Valuation date. */
  public int valuationDate() {
    return today;
  }

  /** The specification the curve was built from. */
  public SofrCurveSpec spec() {
    return spec;
  }

  /** The fixings the curve was built with. */
  public FixingHistory fixings() {
    return fixings;
  }

  /** The discount curve; it changes in place on {@link #update}. */
  public DiscountCurve curve() {
    return curve;
  }

  /** The bootstrap (read-only use: tests and bumped risk). */
  public CurveBuilder builder() {
    return builder;
  }

  /** Number of build inputs. */
  public int inputCount() {
    return n;
  }

  /** Kind of input i (inputs are in maturity order of their own instruments). */
  public Kind kind(final int i) {
    return layout.kinds.get(i);
  }

  /** Label of input i, for example {@code SR3 2026-12} or {@code USD_SOFR 5Y}. */
  public String label(final int i) {
    return layout.instruments.get(i).label();
  }

  /** Tenor in months of a swap input, 0 for the others. */
  public int tenorMonths(final int i) {
    return layout.tenorMonths[i];
  }

  /** Contract month of a futures input as yyyymm, 0 for the others. */
  public int contractMonth(final int i) {
    return layout.contract[i];
  }

  /** The futures convexity adjustment inside input i, decimal; 0 for rates. */
  public double convexity(final int i) {
    return layout.convexity[i];
  }

  /** Pillar date input i determines. */
  public int pillarDate(final int i) {
    return layout.instruments.get(i).pillarDate();
  }

  /** Index of the build swap of a tenor, or -1. */
  public int swapInput(final int months) {
    for (int i = 0; i < n; i++) {
      if (layout.kinds.get(i) == Kind.SWAP && layout.tenorMonths[i] == months) {
        return i;
      }
    }
    return -1;
  }

  /** Current quote of input i in its own units (rate, or futures price). */
  public double quote(final int i) {
    return layout.instruments.get(i).quote();
  }

  /** Quote units per unit of rate: 1 for rates, -100 for futures prices. */
  public double quotePerRate(final int i) {
    return perRate[i];
  }

  /** The quote of input i that corresponds to a rate: the rate, or 100 (1 - rate - convexity). */
  public double quoteForRate(final int i, final double rate) {
    return perRate[i] == 1.0 ? rate : 100.0 * (1.0 - rate - layout.convexity[i]);
  }

  /** Number of leading pillars joined by linear log discount factors. */
  public int stepPillars() {
    return layout.stepPillars;
  }

  /**
   * Moves every input to {@code quotes} (input order, own units) and re-solves once. Returns false
   * and leaves the previous curve and quotes in place if the set cannot be solved.
   */
  public boolean update(final double[] quotes) {
    for (int i = 0; i < n; i++) {
      ordered[builderIndex[i]] = quotes[i];
    }
    try {
      builder.rebuildAll(ordered);
    } catch (final IllegalStateException | ArithmeticException e) {
      return false;
    }
    refreshRiskMap();
    return true;
  }

  /** Number of risk tenors. */
  public int riskTenorCount() {
    return riskCount;
  }

  /** The curve's par rate of risk tenor k, decimal. */
  public double riskParRate(final int k) {
    return pricer.parRate(riskSwap[k], curve, fixings, today);
  }

  /** Par rate of any swap on the curve, decimal. */
  public double parRate(final OisSwap swap) {
    return pricer.parRate(swap, curve, fixings, today);
  }

  /**
   * The ladder of a value on the risk tenors, per basis point. {@code gradient} holds dV / d ln P
   * at the pillars and is overwritten; {@code out} receives one value per risk tenor.
   */
  public void riskLadder(final double[] gradient, final double[] out) {
    builder.quoteSensitivities(gradient);
    for (int k = 0; k < riskCount; k++) {
      double s = 0.0;
      for (int b = 0; b < n; b++) {
        s += gradient[b] * perRateOrdered[b] * hat[b * riskCount + k];
      }
      out[k] = s * 1e-4;
    }
    riskLu.solveTransposed(out);
  }

  /**
   * The parallel DV01 of a value: its change when every risk par rate rises one basis point.
   * {@code gradient} holds dV / d ln P and is overwritten.
   */
  public double riskParallel(final double[] gradient) {
    builder.quoteSensitivities(gradient);
    for (int k = 0; k < riskCount; k++) {
      riskWork[k] = 1.0;
    }
    riskLu.solve(riskWork);
    double s = 0.0;
    for (int b = 0; b < n; b++) {
      double shape = 0.0;
      for (int k = 0; k < riskCount; k++) {
        shape += hat[b * riskCount + k] * riskWork[k];
      }
      s += gradient[b] * perRateOrdered[b] * shape;
    }
    return s * 1e-4;
  }

  /**
   * Writes the move of the build quotes (input order, own units) that raises risk par rate {@code
   * k} by {@code size} in rate and leaves the other risk par rates unchanged to first order. Used
   * to check the ladder by bumping.
   */
  public void riskShift(final int k, final double size, final double[] out) {
    for (int j = 0; j < riskCount; j++) {
      riskWork[j] = j == k ? size : 0.0;
    }
    riskLu.solve(riskWork);
    for (int i = 0; i < n; i++) {
      double shape = 0.0;
      for (int j = 0; j < riskCount; j++) {
        shape += hat[builderIndex[i] * riskCount + j] * riskWork[j];
      }
      out[i] = shape * perRate[i];
    }
  }

  /** Element (j, k) of K W: the move of risk rate j per unit move of hat k. Identity if aligned. */
  public double riskMatrix(final int j, final int k) {
    return riskMatrix[j * riskCount + k];
  }

  /** Simple overnight forward for the day starting at {@code date}, Act/360, decimal. */
  public double overnightForward(final int date) {
    return curve.simpleForward(date, date + 1, 1.0 / 360.0);
  }

  /** The build instruments and what they are, for one valuation date. */
  static final class Layout {
    final List<CurveInstrument> instruments = new ArrayList<>();
    final List<Kind> kinds = new ArrayList<>();
    final int[] tenorMonths;
    final int[] contract;
    final double[] convexity;
    final double[] years;
    final int stepPillars;

    Layout(final int today, final SofrCurveSpec spec, final double flatRate) {
      final HolidayCalendar cal = Calendars.US_SOFR;
      if (!cal.isBusinessDay(today)) {
        throw new IllegalArgumentException(
            "valuation date " + Dates.format(today) + " is not a SOFR business day");
      }
      final List<Integer> months = new ArrayList<>();
      final List<Integer> contracts = new ArrayList<>();
      final List<Double> adjustments = new ArrayList<>();
      final int onEnd = cal.advanceBusinessDays(today, 1);
      final int[] effective = new int[spec.meetings() + 1];
      final int steps =
          Math.min(spec.meetings(), FomcCalendar.effectiveDatesAfter(today, cal, effective));
      final CurveInstrument on =
          new PeriodRateInstrument("SOFR ON", "ON", today, onEnd, DayCount.ACT_360, flatRate);
      int lastStep = onEnd;
      if (steps >= 1 && effective[0] > onEnd) {
        // the level today holds until the first policy date
        lastStep = effective[0];
        instruments.add(new PillarOverride(on, lastStep));
      } else {
        instruments.add(on);
      }
      kinds.add(Kind.OVERNIGHT);
      months.add(0);
      contracts.add(0);
      adjustments.add(0.0);
      for (int j = 0; j < steps; j++) {
        // the month that shows the level after meeting j: its own month if the step falls in
        // the first half, the next month otherwise (a late step leaves the month uninformative)
        int first = Dates.of(Dates.year(effective[j]), Dates.month(effective[j]), 1);
        if (Dates.dayOfMonth(effective[j]) > 15) {
          first = Dates.addMonths(first, 1);
        }
        final int end = Dates.endOfMonth(first) + 1;
        final double ca = convexity(spec, today, first, end);
        final OisFutureInstrument future =
            OisFutureInstrument.oneMonth(
                "SR1 " + yearMonth(first), Dates.year(first), Dates.month(first), cal,
                DayCount.ACT_360, ca, 100.0 * (1.0 - flatRate - ca));
        lastStep = j + 1 < steps ? effective[j + 1] : end;
        instruments.add(new PillarOverride(future, lastStep));
        kinds.add(Kind.SR1);
        months.add(0);
        contracts.add(Dates.year(first) * 100 + Dates.month(first));
        adjustments.add(ca);
      }
      final boolean stepped =
          spec.interpolation() == Interpolation.STEP_THEN_MONOTONE_CONVEX
              || spec.interpolation() == Interpolation.STEP_THEN_TENSION_SPLINE;
      stepPillars = stepped ? instruments.size() : 0;

      final List<OisSwapInstrument> swaps = new ArrayList<>();
      for (final int m : spec.swapTenorMonths()) {
        swaps.add(new OisSwapInstrument(SwapConvention.USD_SOFR, today, m, tenor(m), flatRate));
      }
      // three-month contracts: clear of the last step by two weeks and of the first swap by a
      // month, so that no two pillars sit within days of each other
      final int earliest = lastStep + 14;
      final int latest = swaps.get(0).pillarDate() - 30;
      int taken = 0;
      for (int y = Dates.year(today) - 1; taken < spec.sr3Contracts(); y++) {
        boolean past = false;
        for (int m = 3; m <= 12 && taken < spec.sr3Contracts(); m += 3) {
          final int start = ImmDates.thirdWednesday(y, m);
          final int endMonth = Dates.addMonths(Dates.of(y, m, 1), 3);
          final int end = ImmDates.thirdWednesday(Dates.year(endMonth), Dates.month(endMonth));
          if (end < earliest) {
            continue;
          }
          if (end > latest) {
            past = true;
            break;
          }
          final double ca = convexity(spec, today, start, end);
          instruments.add(
              OisFutureInstrument.threeMonth(
                  "SR3 " + yearMonth(start), y, m, cal, DayCount.ACT_360, ca,
                  100.0 * (1.0 - flatRate - ca)));
          kinds.add(Kind.SR3);
          months.add(0);
          contracts.add(y * 100 + m);
          adjustments.add(ca);
          taken++;
        }
        if (past) {
          break;
        }
      }
      for (int j = 0; j < swaps.size(); j++) {
        instruments.add(swaps.get(j));
        kinds.add(Kind.SWAP);
        months.add(spec.swapTenorMonths()[j]);
        contracts.add(0);
        adjustments.add(0.0);
      }
      final int count = instruments.size();
      tenorMonths = new int[count];
      contract = new int[count];
      convexity = new double[count];
      years = new double[count];
      for (int i = 0; i < count; i++) {
        tenorMonths[i] = months.get(i);
        contract[i] = contracts.get(i);
        convexity[i] = adjustments.get(i);
        years[i] =
            kinds.get(i) == Kind.SWAP
                ? tenorMonths[i] / 12.0
                : (instruments.get(i).pillarDate() - today) / DAYS_PER_YEAR;
      }
    }

    /** A builder over the layout's instruments, leaving out one input if {@code without >= 0}. */
    CurveBuilder builder(
        final int today, final SofrCurveSpec spec, final FixingHistory fixings,
        final int without) {
      final List<CurveInstrument> list = new ArrayList<>(instruments);
      if (without >= 0) {
        list.remove(without);
      }
      final int steps = without >= 0 && without < stepPillars ? stepPillars - 1 : stepPillars;
      final CurveBuilder b =
          new CurveBuilder(today, list, spec.interpolation(), spec.tension(), null, fixings, steps);
      for (int k = 0; k < spec.turnCount(); k++) {
        b.addTurn(spec.turnStart(k), spec.turnEnd(k), spec.turnSpread(k));
      }
      return b;
    }

    private static double convexity(
        final SofrCurveSpec spec, final int today, final int start, final int end) {
      final double t1 = Math.max(0.0, (start - today) / 365.0);
      final double t2 = (end - today) / 365.0;
      return t2 > t1 ? HullWhite.futuresConvexity(spec.hwA(), spec.hwSigma(), t1, t2) : 0.0;
    }

    private static String yearMonth(final int date) {
      final int m = Dates.month(date);
      return Dates.year(date) + (m < 10 ? "-0" : "-") + m;
    }

    private static String tenor(final int months) {
      return months % 12 == 0 ? months / 12 + "Y" : months + "M";
    }
  }
}
