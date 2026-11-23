package io.ratesalgo.app.pricing;

import io.ratesalgo.analytics.bond.BondCarry;
import io.ratesalgo.analytics.bond.BondConvention;
import io.ratesalgo.analytics.bond.BondPricer;
import io.ratesalgo.analytics.bond.FixedRateBond;
import io.ratesalgo.analytics.bond.TreasurySpreadCurve;
import io.ratesalgo.analytics.curve.FairValueSmoother;
import io.ratesalgo.analytics.curve.SofrCurve;
import io.ratesalgo.analytics.curve.SofrCurveSpec;
import io.ratesalgo.analytics.curve.SofrLeaveOneOut;
import io.ratesalgo.analytics.date.BusinessDayConvention;
import io.ratesalgo.analytics.date.Calendars;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.funding.RepoCurve;
import io.ratesalgo.analytics.swap.FixingHistory;
import io.ratesalgo.analytics.swap.OisSwap;
import io.ratesalgo.analytics.swap.OisSwapPricer;
import io.ratesalgo.analytics.swap.SwapCarryRoll;
import io.ratesalgo.analytics.swap.SwapConvention;
import io.ratesalgo.api.BookSource;
import io.ratesalgo.api.CarryRoll;
import io.ratesalgo.api.CarrySource;
import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.InMemoryInstrumentDirectory;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.InstrumentDirectory;
import io.ratesalgo.api.KeyRateTenors;
import io.ratesalgo.api.SpreadSensitivityProvider;
import io.ratesalgo.common.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * Pricing v2 (docs/research/PRICING_V2.md): fair values and risk the desk can stand on.
 *
 * <ul>
 *   <li>One SOFR curve ({@link SofrCurve}): overnight, meeting steps from one-month futures,
 *       three-month futures with convexity, swaps at the eight risk tenors. Inputs the feed does
 *       not send yet are seeded from the ones it does and counted in {@link #unfedInputs()}.
 *   <li>Swap fair value: the leave-one-out par rate for a swap that is a curve input, the curve's
 *       par rate for one that is not, plus a residual that follows the swap's own quote with a
 *       half-life ({@link FairValueSmoother}). A quote cannot become its own fair value in one
 *       step; {@code PricingV2Test.aSkewedQuoteBarelyMovesItsOwnFairValue} states the share. The
 *       residuals are marked to the whole gap at the first quote of the day and again whenever
 *       an input is fed for the first time, since that changes what the model is built from.
 *   <li>Bond fair value: the SOFR curve plus a z-spread from the Treasury spread curve fitted to
 *       the other bonds' books ({@link TreasurySpreadCurve}, leave-one-out), plus the same kind of
 *       residual in spread. It does not follow the bond's own book; it does follow the other
 *       Treasuries. {@link #richCheapBp} is the bond's own spread against the fit.
 *   <li>Risk: every ladder on the eight risk tenors through the curve's Jacobian; bonds at their
 *       market z-spread; and a second ladder, the spread factor, for bonds ({@link
 *       SpreadSensitivityProvider}).
 *   <li>Carry and roll-down per instrument ({@link CarrySource}).
 *   <li>The business date comes from the input timestamps and the {@link #advanceTo} calls,
 *       with a New York cut ({@link BusinessDate}); at a date change every schedule, settlement
 *       date and accrued amount is rebuilt.
 * </ul>
 *
 * <p>Time. The binding reads no clock for its state. Time enters only as the timestamps of the
 * inputs it is given and the instants of {@link #advanceTo}, both journaled, so a binding built
 * at any instant and given the same timed calls holds the same state to the bit (a live loop
 * builds its binding before its first input and a replay on its first journaled frame). Until the
 * first timed call the binding stands on a provisional date with the universe's starting quotes
 * as marks and no reference time. The first timed call fixes the date; if that is not the
 * provisional date, the start is built again on it, as if the binding had been built there. The
 * first quote of each instrument is then taken whole: its residual is marked to the full gap.
 *
 * <p>Set input, then reprice. {@link #setInput} only records a value; {@link #repriceIfDirty}
 * applies everything recorded with one solve. A loop that conflates its curve inputs (newest per
 * instrument) calls {@code setInput} for each and {@code repriceIfDirty} once at the end of the
 * burst. {@link #onCurveInput}, the entry point of {@link PricingBinding}, holds inputs and
 * reprices by itself when a round is complete (every input that has ever been fed has a new
 * value) or when an input arrives {@link Options#conflationNanos()} after the round's first;
 * {@link #advanceTo} applies a held round, once and whole, when its conflation time has passed on
 * the instant it is given. Every trigger compares journaled times only, so when a reprice happens
 * is the same on replay. Pricing runs on the loop thread; {@code PricingV2RepriceBenchmark} and
 * {@code PricingV2Test.repriceDoesNotAllocate} give its cost.
 *
 * <p>After the first timed call and between date changes nothing allocates. A date change
 * allocates (new schedules); it happens once a day at the cut. Single-threaded: the loop thread
 * owns it.
 */
public final class PricingV2 implements PricingBinding, SpreadSensitivityProvider, CarrySource {

  /**
   * Settings.
   *
   * @param spec the SOFR curve; its risk tenors must be the eight of {@link KeyRateTenors}
   * @param conflationNanos longest time an input is held for the rest of its round
   * @param halfLifeSeconds half-life of the fair-value residuals
   * @param maxQuoteMove largest accepted change of an input from its previous value, in rate
   * @param referenceMove move of any input, in rate, after which the leave-one-out rates are
   *     recomputed exactly instead of extrapolated
   * @param overnightRate overnight rate used until the feed sends one, decimal; NaN to use the
   *     shortest swap rate
   * @param repo general collateral curve for bond carry, or null for the overnight rate at every
   *     term
   * @param specialness how far below general collateral on-the-run issues finance, decimal
   * @param carryHorizonDays horizon of carry and roll-down in calendar days
   * @param cutHour hour of the business-date cut, New York time
   * @param maxBondSpreadMove largest accepted change of a bond's z-spread between two reprices
   */
  public record Options(
      SofrCurveSpec spec,
      long conflationNanos,
      double halfLifeSeconds,
      double maxQuoteMove,
      double referenceMove,
      double overnightRate,
      RepoCurve repo,
      double specialness,
      int carryHorizonDays,
      int cutHour,
      double maxBondSpreadMove) {

    /** Validates the settings. */
    public Options {
      final int[] risk = spec.riskTenorMonths();
      boolean same = risk.length == KeyRateTenors.COUNT;
      for (int k = 0; same && k < risk.length; k++) {
        same = risk[k] == Math.round(12.0 * KeyRateTenors.years(k));
      }
      if (!same) {
        throw new IllegalArgumentException(
            "risk tenors must be the key-rate tenors: " + Arrays.toString(risk));
      }
      if (conflationNanos < 0 || !(halfLifeSeconds > 0.0) || !(maxQuoteMove > 0.0)
          || !(referenceMove > 0.0) || carryHorizonDays < 1 || !(maxBondSpreadMove > 0.0)) {
        throw new IllegalArgumentException(
            "settings out of range: conflation " + conflationNanos + ", half-life "
                + halfLifeSeconds + ", band " + maxQuoteMove + ", reference move " + referenceMove
                + ", horizon " + carryHorizonDays + ", bond band " + maxBondSpreadMove);
      }
    }

    /**
     * The desk settings: the standard curve, 100 ms conflation, a 5 minute half-life, the 100 bp
     * input band of version 1, exact leave-one-out every 5 bp, one-day carry at the overnight
     * rate with no specialness, a 17:00 cut and a 3 bp band on bond spreads.
     */
    public static Options defaults() {
      return new Options(SofrCurveSpec.usd(), 100_000_000L, 300.0, 0.01, 5e-4, Double.NaN, null,
          0.0, 1, BusinessDate.DEFAULT_CUT_HOUR, 3e-4);
    }

    /** A copy with another curve specification. */
    public Options withSpec(final SofrCurveSpec newSpec) {
      return new Options(newSpec, conflationNanos, halfLifeSeconds, maxQuoteMove, referenceMove,
          overnightRate, repo, specialness, carryHorizonDays, cutHour, maxBondSpreadMove);
    }

    /** A copy with another repo curve, specialness and carry horizon. */
    public Options withFunding(
        final RepoCurve newRepo, final double newSpecialness, final int horizonDays) {
      return new Options(spec, conflationNanos, halfLifeSeconds, maxQuoteMove, referenceMove,
          overnightRate, newRepo, newSpecialness, horizonDays, cutHour, maxBondSpreadMove);
    }

    /** A copy with another starting overnight rate. */
    public Options withOvernightRate(final double rate) {
      return new Options(spec, conflationNanos, halfLifeSeconds, maxQuoteMove, referenceMove,
          rate, repo, specialness, carryHorizonDays, cutHour, maxBondSpreadMove);
    }
  }

  private static final double MANTISSA_PERCENT_TO_DECIMAL = 1e-11;
  private static final double MANTISSA_TO_PRICE = 1e-9;
  private static final double BP = 1e-4;

  /** Largest z-spread taken as a real price, decimal: 500 bp, as in version 1. */
  private static final double MAX_BOND_SPREAD = 0.05;

  /** Rates outside this range are not taken as a first quote of an input. */
  private static final double MIN_RATE = -0.02;

  private static final double MAX_RATE = 0.25;

  /** Reprices a bond stays held out before its spread is taken as the new level. */
  private static final int REANCHOR_AFTER = 20;

  /**
   * Carry and roll-down are recomputed on every sixteenth reprice (and after a date change or a
   * re-mark): they move with the slope of the curve and the repo rate, not with the level, and
   * they cost a third of a reprice.
   */
  private static final int CARRY_EVERY = 16;

  /** Floor on the spread half-width used to weight a bond in the fit: a quarter of a basis point. */
  private static final double SPREAD_NOISE_FLOOR = 0.25e-4;

  /** What became of an input: not one of the binding's, dropped, or held for the next reprice. */
  private static final int NOT_TAKEN = 0;

  private static final int REJECTED = 1;
  private static final int RECORDED = 2;

  private static final double[] RISK_YEARS = new double[KeyRateTenors.COUNT];

  static {
    for (int k = 0; k < KeyRateTenors.COUNT; k++) {
      RISK_YEARS[k] = KeyRateTenors.years(k);
    }
  }

  private final UsdUniverse universe;
  private final Options options;
  private final InMemoryInstrumentDirectory directory = new InMemoryInstrumentDirectory();
  private final OisSwapPricer swapPricer = new OisSwapPricer();
  private final BondPricer bondPricer = new BondPricer();
  private final BondCarry bondCarry = new BondCarry();
  private final FairValueSmoother smoother;
  private final TreasurySpreadCurve fit;

  private final int[] swapIds;
  private final int[] swapMonths;
  private final int[] swapIndex;
  private final int[] bondIds;
  private final int[] bondIndex;
  private final FixedRateBond[] bonds;
  private final int[] bondIssue;
  private final int[] bondOnTheRunFrom;
  private final String[] bondTenor;
  private final double[] bondStartClean;
  private final double[] bondSpread;
  private final double[] bondFairSpread;
  private final double[] bondModelSpread;
  private final double[] bondPricePerBp;
  private final double[] bondCandidate;
  private final double[] bondWeight;
  private final double[] newtonScratch = new double[KeyRateTenors.COUNT];
  private final double[] bondMoves;
  private final boolean[] bondHasSpread;
  private final boolean[] bondUsable;
  private final int[] bondHeldOut;
  private final double[] fitYears;
  private final double[] fitSpreads;
  private final double[] fitWeights;
  private final boolean[] fitOtr;

  /** Per dense directory index. */
  private final boolean[] isSwap;
  private final int[] kindIndex;
  private final long[] fairMid;
  private final long[] referenceNanos;
  private final double[] fairValue;
  private final double[] modelValue;
  private final double[][] unitLadder;
  private final double[][] spreadLadder;
  private final double[] carryBp;
  private final double[] rollBp;
  /** The next observation of the instrument is taken whole: set at the start of the day. */
  private final boolean[] takeWhole;
  /** The universe's starting swap rates, decimal. */
  private final double[] startRates;

  private int[] fixingDates;
  private double[] fixingRates;
  private int fixingCount;

  private BookSource book;
  private Day day;
  /** False until the first timed call has fixed the business date. */
  private boolean started;
  /** True while the start-of-day marks are computed: no book, no reference time. */
  private boolean startMarks;
  private long nextCut = Long.MIN_VALUE;
  /** Newest input timestamp; the stamp of a reprice. */
  private long newestInput = Long.MIN_VALUE;
  /** Latest instant the binding has been given, by an input or by {@link #advanceTo}. */
  private long latestNanos = Long.MIN_VALUE;
  private long firstPendingStamp;
  private int pendingCount;
  private int fedCount;
  private boolean remark;
  private boolean carryDue;
  private boolean carryForced = true;
  private long reprices;
  private long rebuilds;
  private long rejectedInputs;
  private long rejectedBondBooks;
  private long dateChanges;
  private long referenceFailures;

  /**
   * Builds the binding. It reads no clock: until its first timed call it stands on the universe's
   * trade date, and the first input or {@link #advanceTo} fixes the date from then on. This is
   * the constructor of a desk.
   *
   * @param universe the instruments
   * @param options settings
   */
  public PricingV2(final UsdUniverse universe, final Options options) {
    this(universe, options, Integer.MIN_VALUE);
  }

  /**
   * Builds the binding and shows the inputs of the clock's business date until its first timed
   * call, for a caller that needs to list a date's inputs before it has any (a feed's probe). The
   * clock is read once, for that date alone; nothing read from it survives the first timed call,
   * which builds the start again on its own date if that is another one. A desk uses {@link
   * #PricingV2(UsdUniverse, Options)}.
   *
   * @param universe the instruments
   * @param clock read once, for the date shown before the first timed call
   * @param options settings
   */
  public PricingV2(final UsdUniverse universe, final Clock clock, final Options options) {
    this(universe, options,
        BusinessDate.tradeDate(clock.epochNanos(), options.cutHour(), Calendars.US_SOFR));
  }

  private PricingV2(final UsdUniverse universe, final Options options, final int shownDate) {
    this.universe = universe;
    this.options = options;
    // the universe's own date, not an instant: the directory is built on it and does not move
    final int today =
        Calendars.US_SOFR.adjust(universe.valuationDate(), BusinessDayConvention.FOLLOWING);

    final List<UsdUniverse.SwapSpec> swaps = universe.swaps();
    final List<UsdUniverse.BondSpec> bondSpecs = universe.bonds();
    swapIds = new int[swaps.size()];
    swapMonths = new int[swaps.size()];
    swapIndex = new int[swaps.size()];
    startRates = new double[swaps.size()];
    for (int j = 0; j < swapIds.length; j++) {
      swapIds[j] = swaps.get(j).instrumentId();
      swapMonths[j] = swaps.get(j).tenorMonths();
      startRates[j] = swaps.get(j).initialMid() * MANTISSA_PERCENT_TO_DECIMAL;
    }
    bondIds = new int[bondSpecs.size()];
    bondIndex = new int[bondIds.length];
    bonds = new FixedRateBond[bondIds.length];
    bondIssue = new int[bondIds.length];
    bondOnTheRunFrom = new int[bondIds.length];
    bondTenor = new String[bondIds.length];
    bondStartClean = new double[bondIds.length];
    bondSpread = new double[bondIds.length];
    bondFairSpread = new double[bondIds.length];
    bondModelSpread = new double[bondIds.length];
    bondPricePerBp = new double[bondIds.length];
    bondCandidate = new double[bondIds.length];
    bondWeight = new double[bondIds.length];
    bondMoves = new double[bondIds.length];
    bondHasSpread = new boolean[bondIds.length];
    bondUsable = new boolean[bondIds.length];
    bondHeldOut = new int[bondIds.length];
    fitYears = new double[bondIds.length];
    fitSpreads = new double[bondIds.length];
    fitWeights = new double[bondIds.length];
    fitOtr = new boolean[bondIds.length];
    for (int j = 0; j < bondIds.length; j++) {
      final UsdUniverse.BondSpec b = bondSpecs.get(j);
      bondIds[j] = b.instrumentId();
      bonds[j] =
          new FixedRateBond(
              b.securityId(), BondConvention.UST,
              b.bond().couponMantissa() * MANTISSA_PERCENT_TO_DECIMAL, epochDay(b.bond().datedDate()),
              epochDay(b.bond().firstCouponDate()), epochDay(b.bond().maturity()));
      bondIssue[j] = epochDay(b.bond().issueDate());
      bondOnTheRunFrom[j] = epochDay(b.bond().onTheRunFromDate());
      bondTenor[j] = b.bond().tenor();
      bondStartClean[j] = b.initialMid() * MANTISSA_TO_PRICE;
    }
    // no cross-sectional rejection here: a bond dropping in and out of the fit would move the
    // model value of the others in steps. A bad book is caught in time instead (its spread
    // jumps), and its last accepted spread stands in for it
    fit =
        new TreasurySpreadCurve(
            Math.max(1, bondIds.length), 5.0, 1.5e-4, Double.POSITIVE_INFINITY);

    fixingDates = new int[256];
    fixingRates = new double[256];
    day = freshDay(today);
    for (int j = 0; j < swapIds.length; j++) {
      final double annuity = swapPricer.annuity(day.unit[j], day.curve.curve(), today);
      swapIndex[j] =
          directory.add(
              Instrument.swap(swapIds[j], swaps.get(j).securityId(), CurrencyCode.USD,
                  swapMonths[j] / 12.0, annuity, 0, 0.0, 5_000_000_000L));
    }
    for (int j = 0; j < bondIds.length; j++) {
      bondIndex[j] =
          directory.add(
              Instrument.bond(bondIds[j], bondSpecs.get(j).securityId(), CurrencyCode.USD,
                  Math.max((bonds[j].maturity() - today) / 365.25, 0.1), 2_500_000_000L));
    }
    final int n = directory.size();
    isSwap = new boolean[n];
    kindIndex = new int[n];
    fairMid = new long[n];
    referenceNanos = new long[n];
    fairValue = new double[n];
    modelValue = new double[n];
    unitLadder = new double[n][KeyRateTenors.COUNT];
    spreadLadder = new double[n][KeyRateTenors.COUNT];
    carryBp = new double[n];
    rollBp = new double[n];
    for (int j = 0; j < swapIds.length; j++) {
      isSwap[swapIndex[j]] = true;
      kindIndex[swapIndex[j]] = j;
    }
    for (int j = 0; j < bondIds.length; j++) {
      kindIndex[bondIndex[j]] = j;
    }
    takeWhole = new boolean[n];
    smoother = new FairValueSmoother(n, options.halfLifeSeconds());
    markStart();
    if (shownDate != Integer.MIN_VALUE && shownDate != today) {
      startDay(shownDate);
    }
  }

  /**
   * The fixings and the curve of a start on a date from the universe's starting quotes. The
   * simulated market has no history, so the overnight rate of the start is assumed for the days a
   * started futures contract looks back over.
   */
  private Day freshDay(final int date) {
    final double overnight = startOvernight();
    fixingCount = 0;
    for (int d = date - 120; d < date; d++) {
      if (Calendars.US_SOFR.isBusinessDay(d)) {
        appendFixing(d, overnight);
      }
    }
    return new Day(date, null, startRates, overnight);
  }

  /**
   * Starts again on a date, as a binding built there: everything before is forgotten, counters
   * included, so the state does not depend on which date the binding was built on.
   */
  private void startDay(final int date) {
    reprices = 0;
    rebuilds = 0;
    rejectedInputs = 0;
    rejectedBondBooks = 0;
    dateChanges = 0;
    referenceFailures = 0;
    day = freshDay(date);
    markStart();
  }

  /**
   * The start-of-day marks: the starting quotes are applied as one round, with no book and no
   * time. Residuals are set to the whole gap and the first real observation of each instrument
   * marks it again at its own timestamp, so no stamp is made here.
   */
  private void markStart() {
    Arrays.fill(bondSpread, 0.0);
    Arrays.fill(bondHasSpread, false);
    Arrays.fill(bondHeldOut, 0);
    Arrays.fill(bondWeight, 0.0);
    Arrays.fill(takeWhole, true);
    remark = false;
    carryForced = true;
    newestInput = Long.MIN_VALUE;
    pendingCount = 0;
    final Day d = day;
    for (int s = 0; s < d.slots; s++) {
      if (d.fed[s]) {
        d.pendingSet[s] = true;
        d.pendingValue[s] = d.value[s];
        pendingCount++;
      }
    }
    startMarks = true;
    applyHeld(0L);
    startMarks = false;
  }

  /**
   * The first timed call: fixes the business date, builds the start again if the date shown so
   * far is another one, and dates the start-of-day marks at the start of the business day.
   */
  private void start(final long epochNanos) {
    started = true;
    final int date = BusinessDate.tradeDate(epochNanos, options.cutHour(), Calendars.US_SOFR);
    if (date != day.date) {
      startDay(date);
    }
    nextCut = BusinessDate.nextCut(epochNanos, options.cutHour());
    Arrays.fill(referenceNanos, BusinessDate.lastCut(epochNanos, options.cutHour()));
  }

  private double startOvernight() {
    if (!Double.isNaN(options.overnightRate())) {
      return options.overnightRate();
    }
    int shortest = 0;
    for (int j = 1; j < swapMonths.length; j++) {
      if (swapMonths[j] < swapMonths[shortest]) {
        shortest = j;
      }
    }
    return startRates[shortest];
  }

  private void appendFixing(final int date, final double rate) {
    if (fixingCount == fixingDates.length) {
      fixingDates = Arrays.copyOf(fixingDates, 2 * fixingCount);
      fixingRates = Arrays.copyOf(fixingRates, 2 * fixingCount);
    }
    fixingDates[fixingCount] = date;
    fixingRates[fixingCount++] = rate;
  }

  private static int epochDay(final LocalDate d) {
    return Dates.of(d.getYear(), d.getMonthValue(), d.getDayOfMonth());
  }

  /** Everything that depends on the business date; replaced at a date change. */
  private final class Day {
    final int date;
    final SofrCurve curve;
    final SofrLeaveOneOut loo;
    final int inputs;
    final int slots;
    /** Slot of each universe swap: its build input, or a mark slot after the inputs. */
    final int[] swapSlot;
    final int[] inputId;
    final double[] value;
    final double[] pendingValue;
    final boolean[] pendingSet;
    final boolean[] fed;
    final double[] quotes;
    final OisSwap[] unit;
    final OisSwap[] quoted;
    final SwapCarryRoll[] carry;
    final double[] gradient;
    final int[] settlement;
    final int[] horizon;
    final double[] accrued;
    final boolean[] onTheRun;

    Day(final int date, final Day previous, final double[] startRates, final double overnight) {
      this.date = date;
      final FixingHistory fixings =
          new FixingHistory(
              Arrays.copyOf(fixingDates, fixingCount), Arrays.copyOf(fixingRates, fixingCount));
      curve = new SofrCurve(date, options.spec(), fixings, overnight);
      inputs = curve.inputCount();
      swapSlot = new int[swapIds.length];
      int extra = 0;
      for (int j = 0; j < swapIds.length; j++) {
        final int input = curve.swapInput(swapMonths[j]);
        swapSlot[j] = input >= 0 ? input : inputs + extra++;
      }
      slots = inputs + extra;
      inputId = new int[inputs];
      value = new double[slots];
      pendingValue = new double[slots];
      pendingSet = new boolean[slots];
      fed = new boolean[slots];
      quotes = new double[inputs];
      for (int i = 0; i < inputs; i++) {
        inputId[i] =
            switch (curve.kind(i)) {
              case OVERNIGHT -> V2InputIds.OVERNIGHT;
              case SR1 -> V2InputIds.sr1(curve.contractMonth(i));
              case SR3 -> V2InputIds.sr3(curve.contractMonth(i));
              case SWAP -> V2InputIds.parSwap(curve.tenorMonths(i));
            };
        value[i] = curve.quote(i);
      }
      if (previous == null) {
        for (int j = 0; j < swapIds.length; j++) {
          value[swapSlot[j]] = startRates[j];
          fed[swapSlot[j]] = true;
        }
      } else {
        carryOver(previous);
      }
      fedCount = 0;
      for (int s = 0; s < slots; s++) {
        fedCount += fed[s] ? 1 : 0;
      }
      seedUnfed(overnight);
      System.arraycopy(value, 0, quotes, 0, inputs);
      if (curve.update(quotes)) {
        rebuilds++;
      } else {
        // the carried quotes do not solve on the new date: price off the flat start and count it
        rejectedInputs++;
        for (int i = 0; i < inputs; i++) {
          value[i] = curve.quote(i);
        }
      }
      loo = new SofrLeaveOneOut(curve);
      unit = new OisSwap[swapIds.length];
      quoted = new OisSwap[swapIds.length];
      carry = new SwapCarryRoll[swapIds.length];
      for (int j = 0; j < swapIds.length; j++) {
        unit[j] =
            OisSwap.spot(SwapConvention.USD_SOFR, date, swapMonths[j], 1.0, value[swapSlot[j]], true);
        quoted[j] = OisSwap.spot(SwapConvention.USD_SOFR, date, swapMonths[j], 1.0, 0.0, true);
        carry[j] =
            new SwapCarryRoll(SwapConvention.USD_SOFR, date, swapMonths[j], options.carryHorizonDays());
      }
      gradient = new double[inputs];
      settlement = new int[bondIds.length];
      horizon = new int[bondIds.length];
      accrued = new double[bondIds.length];
      onTheRun = new boolean[bondIds.length];
      for (int j = 0; j < bondIds.length; j++) {
        settlement[j] = Math.max(bonds[j].settlementDate(date), bondIssue[j]);
        horizon[j] =
            Calendars.US_SIFMA.adjust(
                settlement[j] + options.carryHorizonDays(), BusinessDayConvention.FOLLOWING);
        accrued[j] = bondPricer.accrued(bonds[j], settlement[j]);
        // on the run: the latest issue of its tenor that has become on-the-run by this date
        boolean latest = bondOnTheRunFrom[j] <= date;
        for (int k = 0; latest && k < bondIds.length; k++) {
          latest =
              k == j || !bondTenor[k].equals(bondTenor[j]) || bondOnTheRunFrom[k] > date
                  || bondOnTheRunFrom[k] <= bondOnTheRunFrom[j];
        }
        onTheRun[j] = latest;
      }
    }

    /** Takes the quotes and fed flags of the same inputs from the previous date. */
    private void carryOver(final Day previous) {
      for (int i = 0; i < inputs; i++) {
        for (int p = 0; p < previous.inputs; p++) {
          if (previous.inputId[p] == inputId[i]) {
            value[i] = previous.value[p];
            fed[i] = previous.fed[p];
          }
        }
      }
      for (int j = 0; j < swapIds.length; j++) {
        if (swapSlot[j] >= inputs) {
          value[swapSlot[j]] = previous.value[previous.swapSlot[j]];
          fed[swapSlot[j]] = previous.fed[previous.swapSlot[j]];
        }
      }
    }

    /**
     * Gives every input the feed has not sent a value derived from the ones it has: the overnight
     * rate for the overnight input and the futures (a flat front end), and for a swap the linear
     * interpolation in tenor of the fed swaps on either side, flat beyond them.
     */
    void seedUnfed(final double overnight) {
      final double on = fed[0] ? value[0] : overnight;
      for (int i = 0; i < inputs; i++) {
        if (fed[i]) {
          continue;
        }
        if (curve.kind(i) != SofrCurve.Kind.SWAP) {
          value[i] = curve.quoteForRate(i, on);
          continue;
        }
        int below = -1;
        int above = -1;
        for (int k = 0; k < inputs; k++) {
          if (fed[k] && curve.kind(k) == SofrCurve.Kind.SWAP) {
            if (curve.tenorMonths(k) < curve.tenorMonths(i)) {
              below = k;
            } else if (above < 0) {
              above = k;
            }
          }
        }
        if (below < 0 && above < 0) {
          value[i] = on;
        } else if (below < 0) {
          value[i] = value[above];
        } else if (above < 0) {
          value[i] = value[below];
        } else {
          final double w =
              (double) (curve.tenorMonths(i) - curve.tenorMonths(below))
                  / (curve.tenorMonths(above) - curve.tenorMonths(below));
          value[i] = value[below] + w * (value[above] - value[below]);
        }
      }
    }

    int slotOf(final int instrumentId) {
      for (int j = 0; j < swapIds.length; j++) {
        if (swapIds[j] == instrumentId) {
          return swapSlot[j];
        }
      }
      for (int i = 0; i < inputs; i++) {
        if (inputId[i] == instrumentId) {
          return i;
        }
      }
      return -1;
    }

    boolean isPrice(final int slot) {
      return slot < inputs && curve.quotePerRate(slot) != 1.0;
    }

    /** The rate a slot's value stands for. */
    double rateOf(final int slot, final double v) {
      return isPrice(slot) ? 1.0 - v / 100.0 - curve.convexity(slot) : v;
    }
  }

  @Override
  public InstrumentDirectory directory() {
    return directory;
  }

  @Override
  public UsdUniverse universe() {
    return universe;
  }

  @Override
  public void book(final BookSource composite) {
    this.book = composite;
  }

  /** The settings the binding was built with. */
  public Options options() {
    return options;
  }

  /** The business date prices and settlement dates are computed for, analytics epoch days. */
  public int businessDate() {
    return day.date;
  }

  /** The SOFR curve of the current business date (read-only use). */
  public SofrCurve curve() {
    return day.curve;
  }

  /**
   * Time from the loop, journaled: moves the business date forward if the instant is at or past
   * the next New York cut, and applies a held round, once and whole, if its conflation time has
   * passed on this instant. The first call before any input fixes the business date. The loop
   * calls it from its timer, so that a round the feed left incomplete is applied and the date
   * changes on a day without market data. A date change allocates.
   */
  public void advanceTo(final long epochNanos) {
    advanceDate(epochNanos);
    if (pendingCount > 0 && epochNanos - firstPendingStamp >= options.conflationNanos()) {
      applyHeld(newestInput);
    }
  }

  /** Fixes the date at the first timed call and moves it at a cut; nothing else. */
  private void advanceDate(final long epochNanos) {
    if (!started) {
      start(epochNanos);
    }
    latestNanos = Math.max(latestNanos, epochNanos);
    if (epochNanos < nextCut) {
      return;
    }
    // what is held belongs to the date that is ending
    if (pendingCount > 0) {
      applyHeld(newestInput);
    }
    final int date = BusinessDate.tradeDate(epochNanos, options.cutHour(), Calendars.US_SOFR);
    nextCut = BusinessDate.nextCut(epochNanos, options.cutHour());
    if (date == day.date) {
      return;
    }
    // the overnight rate of each day that has ended becomes its fixing; the published number
    // arrives the next morning and replaces nothing here, since the feed has no fixing history
    final double overnight = day.rateOf(0, day.value[0]);
    for (int d = day.date; d < date; d++) {
      if (Calendars.US_SOFR.isBusinessDay(d)) {
        appendFixing(d, overnight);
      }
    }
    day = new Day(date, day, null, overnight);
    carryForced = true;
    dateChanges++;
    pendingCount = 0;
    newestInput = Math.max(newestInput, epochNanos);
    recompute(newestInput);
  }

  /**
   * One curve input from the loop, the entry point of {@link PricingBinding}: records it as
   * {@link #setInput} does and reprices when the round is complete (every input that has ever been
   * fed has a new value) or when this input is {@link Options#conflationNanos()} newer than the
   * first one held, which then joins the round. A loop that conflates its inputs itself calls
   * {@link #setInput} and {@link #repriceIfDirty} instead.
   */
  @Override
  public boolean onCurveInput(
      final int instrumentId, final long valueMantissa, final long timestampNanos) {
    final int taken = record(instrumentId, valueMantissa, timestampNanos);
    if (taken == RECORDED
        && (pendingCount >= fedCount
            || timestampNanos - firstPendingStamp >= options.conflationNanos())) {
      applyHeld(newestInput);
    }
    return taken != NOT_TAKEN;
  }

  /**
   * Records one curve input and does not reprice: a par swap rate or the overnight estimate in
   * percent, or a futures price, as a Decimal9 mantissa, for a universe swap or an id of {@link
   * V2InputIds}. A second value for the same input before the next reprice replaces the first,
   * so a burst collapses to its newest values. A value further than {@link
   * Options#maxQuoteMove()} from the input's previous value is counted in {@link
   * #rejectedInputs()} and dropped. Returns true if the instrument is one the binding takes quotes
   * for.
   *
   * <p>Fair values, ladders and carry do not change until {@link #repriceIfDirty}, {@link
   * #advanceTo} or a complete round through {@link #onCurveInput} applies what is held. The one
   * exception is time: an input stamped at or after the next New York cut moves the business date
   * first, which applies what was held and reprices on the new date. No allocation.
   */
  @Override
  public boolean setInput(
      final int instrumentId, final long valueMantissa, final long timestampNanos) {
    return record(instrumentId, valueMantissa, timestampNanos) != NOT_TAKEN;
  }

  /**
   * Applies every input recorded since the last reprice with one solve and one reprice, and
   * returns true; returns false and does nothing if nothing is held.
   */
  @Override
  public boolean repriceIfDirty() {
    if (pendingCount == 0) {
      return false;
    }
    applyHeld(newestInput);
    return true;
  }

  /**
   * Kept for loops written before {@link #advanceTo} applied held rounds by itself. Applies a held
   * round only if its conflation time has passed on the latest instant the binding has been given
   * (an input's timestamp or an {@code advanceTo}); otherwise it does nothing. So it never splits
   * a round, and after {@code advanceTo} has run for the same instant it never reprices a second
   * time. To apply what is held now, call {@link #repriceIfDirty}.
   */
  public void flush() {
    if (pendingCount > 0 && latestNanos - firstPendingStamp >= options.conflationNanos()) {
      applyHeld(newestInput);
    }
  }

  private int record(final int instrumentId, final long valueMantissa, final long timestampNanos) {
    advanceDate(timestampNanos);
    final Day d = day;
    final int slot = d.slotOf(instrumentId);
    if (slot < 0) {
      return NOT_TAKEN;
    }
    final double v =
        valueMantissa * (d.isPrice(slot) ? MANTISSA_TO_PRICE : MANTISSA_PERCENT_TO_DECIMAL);
    final double rate = d.rateOf(slot, v);
    final double previous = d.pendingSet[slot] ? d.pendingValue[slot] : d.value[slot];
    // written so that a NaN fails both tests
    final boolean ok =
        d.fed[slot]
            ? Math.abs(rate - d.rateOf(slot, previous)) <= options.maxQuoteMove()
            : rate >= MIN_RATE && rate <= MAX_RATE;
    if (!ok) {
      rejectedInputs++;
      return REJECTED;
    }
    if (!d.fed[slot]) {
      // a first quote changes what the curve is built from: the residuals, which were marked
      // against a curve that had this input seeded, are marked again at the next reprice
      d.fed[slot] = true;
      fedCount++;
      remark = true;
    }
    d.pendingValue[slot] = v;
    if (!d.pendingSet[slot]) {
      d.pendingSet[slot] = true;
      if (pendingCount++ == 0) {
        firstPendingStamp = timestampNanos;
      }
    }
    newestInput = Math.max(newestInput, timestampNanos);
    return RECORDED;
  }

  /** Applies what is held: one solve if a quote moved, then one reprice of everything. */
  private void applyHeld(final long stamp) {
    final Day d = day;
    for (int s = 0; s < d.slots; s++) {
      if (d.pendingSet[s]) {
        d.value[s] = d.pendingValue[s];
      }
    }
    d.seedUnfed(d.rateOf(0, d.value[0]));
    boolean changed = false;
    for (int i = 0; i < d.inputs; i++) {
      changed |= Double.compare(d.value[i], d.curve.quote(i)) != 0;
      d.quotes[i] = d.value[i];
    }
    if (changed) {
      if (d.curve.update(d.quotes)) {
        rebuilds++;
        if (d.loo.moveSinceReference() > options.referenceMove()) {
          try {
            d.loo.reference();
          } catch (final IllegalStateException | ArithmeticException ignored) {
            // a reduced curve did not solve: keep extrapolating from the last reference
            referenceFailures++;
          }
        }
      } else {
        // the round does not solve: keep the last good curve and its quotes
        rejectedInputs += pendingCount;
        for (int i = 0; i < d.inputs; i++) {
          d.value[i] = d.curve.quote(i);
        }
      }
    }
    recompute(stamp);
    for (int s = 0; s < d.slots; s++) {
      d.pendingSet[s] = false;
    }
    pendingCount = 0;
  }

  /** Fair values, ladders and carry of every instrument off the current curve and book. */
  private void recompute(final long stamp) {
    final Day d = day;
    final FixingHistory fixings = d.curve.fixings();
    carryDue = remark || carryForced || reprices % CARRY_EVERY == 0;
    carryForced = false;
    for (int j = 0; j < swapIds.length; j++) {
      final int i = swapIndex[j];
      final int slot = d.swapSlot[j];
      final int k = slot < d.inputs ? d.loo.swapOfInput(slot) : -1;
      final double model = k >= 0 ? d.loo.value(k) : d.curve.parRate(d.quoted[j]);
      // the residual takes an observation only when the swap has a new quote in this round
      final boolean observed = d.pendingSet[slot];
      final double fair;
      if (startMarks) {
        // a start-of-day mark is the whole gap, with no time: the first real quote marks it again
        smoother.reset(i, observed ? d.value[slot] : model, model, 0L);
        fair = observed ? d.value[slot] : smoother.fair(i, model);
      } else {
        if (d.fed[slot] && (remark || (takeWhole[i] && observed))) {
          smoother.reset(i, d.value[slot], model, stamp);
          takeWhole[i] = false;
        }
        fair = observed ? smoother.update(i, d.value[slot], model, stamp) : smoother.fair(i, model);
      }
      modelValue[i] = model;
      fairValue[i] = fair;
      fairMid[i] = Decimal9.round(fair * 100.0);
      referenceNanos[i] = startMarks ? NO_VALUE : stamp;
      Arrays.fill(d.gradient, 0.0);
      swapPricer.pvGradient(d.unit[j], d.curve.curve(), d.curve.curve(), fixings, d.date,
          d.gradient, d.gradient);
      d.curve.riskLadder(d.gradient, unitLadder[i]);
      if (carryDue) {
        d.carry[j].compute(d.curve.curve(), fixings, d.date);
        carryBp[i] = d.carry[j].carry() / BP;
        rollBp[i] = d.carry[j].rollDown() / BP;
      }
    }
    recomputeBonds(stamp);
    remark = false;
    reprices++;
  }

  private void recomputeBonds(final long stamp) {
    final Day d = day;
    final int n = bondIds.length;
    if (n == 0) {
      return;
    }
    // 1. each bond's market z-spread; the move common to the bonds since the last reprice (the
    // curve and the books are not sampled at the same instant) is taken out before a bond's own
    // move is judged
    int moved = 0;
    for (int j = 0; j < n; j++) {
      final double z = solveSpread(d, j, marketClean(j) + d.accrued[j]);
      // NaN fails the comparison
      bondCandidate[j] = Math.abs(z) <= MAX_BOND_SPREAD ? z : Double.NaN;
      if (bondHasSpread[j] && !Double.isNaN(bondCandidate[j])) {
        // insertion into the sorted moves
        final double move = bondCandidate[j] - bondSpread[j];
        int k = moved++;
        while (k > 0 && bondMoves[k - 1] > move) {
          bondMoves[k] = bondMoves[k - 1];
          k--;
        }
        bondMoves[k] = move;
      }
    }
    final double common =
        moved == 0 ? 0.0 : 0.5 * (bondMoves[(moved - 1) / 2] + bondMoves[moved / 2]);
    // 2. its spread ladder and its weight in the fit
    for (int j = 0; j < n; j++) {
      final int i = bondIndex[j];
      final double z = bondCandidate[j];
      boolean usable = !Double.isNaN(z);
      if (usable && bondHasSpread[j]
          && !(Math.abs(z - bondSpread[j] - common) <= options.maxBondSpreadMove())
          && ++bondHeldOut[j] < REANCHOR_AFTER) {
        // a jump of its own that no market makes between two reprices: a bad book, until it
        // has stood this long
        usable = false;
      }
      bondUsable[j] = usable;
      if (usable) {
        bondSpread[j] = z;
        bondHasSpread[j] = true;
        bondHeldOut[j] = 0;
      } else {
        rejectedBondBooks++;
      }
      Arrays.fill(spreadLadder[i], 0.0);
      final double perUnitSpread =
          bondPricer.spreadLadder(bonds[j], d.curve.curve(), d.settlement[j], bondSpread[j],
              0.01, RISK_YEARS, spreadLadder[i]);
      for (int k = 0; k < KeyRateTenors.COUNT; k++) {
        spreadLadder[i][k] *= BP;
      }
      // price per 100 per unit of spread; the book's half-width in spread sets the weight
      final double pricePerSpread = Math.abs(perUnitSpread) * 100.0;
      bondPricePerBp[j] = pricePerSpread * BP;
      final double halfWidth = bookHalfWidth(j) / pricePerSpread;
      fitYears[j] = (bonds[j].maturity() - d.settlement[j]) / 365.25;
      fitSpreads[j] = bondSpread[j];
      fitOtr[j] = d.onTheRun[j];
      // a held-out bond stays in the fit at its last accepted spread and weight
      if (usable) {
        bondWeight[j] = 1.0 / (halfWidth * halfWidth + SPREAD_NOISE_FLOOR * SPREAD_NOISE_FLOOR);
      }
      fitWeights[j] = bondHasSpread[j] ? bondWeight[j] : 0.0;
    }
    fit.fit(n, fitYears, fitSpreads, fitWeights, fitOtr);
    // 3. fair value from the fit, ladders at the market spread, carry
    for (int j = 0; j < n; j++) {
      final int i = bondIndex[j];
      final FixedRateBond b = bonds[j];
      final double model = fit.leaveOneOut(j);
      // the residual takes an observation when the book has moved on, not when time has
      final double fairSpread;
      if (startMarks) {
        smoother.reset(i, bondUsable[j] ? bondSpread[j] : model, model, 0L);
        fairSpread = bondUsable[j] ? bondSpread[j] : smoother.fair(i, model);
      } else {
        final long bookStamp = book == null ? stamp : book.updateNanos(bondIds[j]);
        if (bondUsable[j] && (remark || takeWhole[i])) {
          smoother.reset(i, bondSpread[j], model, bookStamp);
          takeWhole[i] = false;
        }
        fairSpread =
            bondUsable[j]
                ? smoother.update(i, bondSpread[j], model, bookStamp)
                : smoother.fair(i, model);
      }
      bondModelSpread[j] = model;
      bondFairSpread[j] = fairSpread;
      final double fairClean =
          bondPricer.dirtyFromCurve(b, d.curve.curve(), d.settlement[j], fairSpread) - d.accrued[j];
      modelValue[i] =
          bondPricer.dirtyFromCurve(b, d.curve.curve(), d.settlement[j], model) - d.accrued[j];
      fairValue[i] = fairClean;
      fairMid[i] = Decimal9.round(fairClean);
      referenceNanos[i] = startMarks ? NO_VALUE : stamp;
      Arrays.fill(d.gradient, 0.0);
      bondPricer.dirtyFromCurveGradient(b, d.curve.curve(), d.settlement[j], bondSpread[j], 0.01,
          d.gradient);
      d.curve.riskLadder(d.gradient, unitLadder[i]);
      if (!carryDue) {
        continue;
      }
      final double pricePerBp = bondPricePerBp[j];
      final double clean = marketClean(j);
      final int term = d.horizon[j] - d.settlement[j];
      final double overnight = d.rateOf(0, d.value[0]);
      final double repoRate =
          (options.repo() == null ? overnight : options.repo().rate(term))
              - (d.onTheRun[j] ? options.specialness() : 0.0);
      carryBp[i] = bondCarry.carry(b, clean, d.settlement[j], d.horizon[j], repoRate) / pricePerBp;
      final double premium = d.onTheRun[j] ? fit.onTheRunPremium() : 0.0;
      final double shorter = Math.max(fitYears[j] - term / 365.25, 0.25);
      final double curveToday =
          TreasurySpreadCurve.parYield(d.curve.curve(), d.settlement[j], fitYears[j],
              fit.spread(fitYears[j]) - premium);
      final double curveAtHorizon =
          TreasurySpreadCurve.parYield(d.curve.curve(), d.settlement[j], shorter,
              fit.spread(shorter) - premium);
      // in yield the roll-down is the fall of the curve's yield as the bond shortens; repricing
      // the bond at the horizon (BondCarry.rollDown) gives the same number to first order and
      // would cost a yield solve per bond per reprice
      rollBp[i] = (curveToday - curveAtHorizon) / BP;
    }
  }

  /**
   * The z-spread that reprices a dirty price, or NaN if there is none within 500 bp. Newton on
   * the price in the spread, started from the bond's last accepted spread: the price is smooth
   * and convex in the spread and the start is within basis points, so three steps reach 1e-9 of
   * a price point, where the bracketing search of {@link BondPricer#zSpread} takes some fifty
   * price evaluations. That search is the fallback when Newton does not settle.
   */
  private double solveSpread(final Day d, final int j, final double dirty) {
    final FixedRateBond b = bonds[j];
    double s = bondSpread[j];
    for (int k = 0; k < 6 && Math.abs(s) <= 2.0 * MAX_BOND_SPREAD; k++) {
      final double error = bondPricer.dirtyFromCurve(b, d.curve.curve(), d.settlement[j], s) - dirty;
      if (Math.abs(error) <= 1e-9) {
        return s;
      }
      Arrays.fill(newtonScratch, 0.0);
      s -= error / bondPricer.spreadLadder(b, d.curve.curve(), d.settlement[j], s, 1.0, RISK_YEARS,
          newtonScratch);
    }
    try {
      return bondPricer.zSpread(b, d.curve.curve(), dirty, d.settlement[j]);
    } catch (final IllegalStateException ignored) {
      // no spread within 500 bp reprices this price
      return Double.NaN;
    }
  }

  /**
   * Market clean price per 100 of bond j: the composite mid, or the starting price without one or
   * for the start-of-day marks.
   */
  private double marketClean(final int j) {
    if (book != null && !startMarks) {
      final long bid = book.bestBid(bondIds[j]);
      final long ask = book.bestAsk(bondIds[j]);
      if (bid != NO_VALUE && ask != NO_VALUE && ask >= bid) {
        return 0.5 * (bid + ask) * MANTISSA_TO_PRICE;
      }
    }
    return bondStartClean[j];
  }

  /** Half the bid-ask of bond j's book in price per 100, zero without a two-sided book. */
  private double bookHalfWidth(final int j) {
    if (book != null && !startMarks) {
      final long bid = book.bestBid(bondIds[j]);
      final long ask = book.bestAsk(bondIds[j]);
      if (bid != NO_VALUE && ask != NO_VALUE && ask >= bid) {
        return 0.5 * (ask - bid) * MANTISSA_TO_PRICE;
      }
    }
    return 0.0;
  }

  @Override
  public long fairMid(final int instrumentId) {
    final int i = directory.indexOf(instrumentId);
    return i < 0 ? NO_VALUE : fairMid[i];
  }

  @Override
  public long referenceNanos(final int instrumentId) {
    final int i = directory.indexOf(instrumentId);
    return i < 0 ? NO_VALUE : referenceNanos[i];
  }

  @Override
  public boolean pv01(final int instrumentId, final long quantityMinor, final Dv01Vector out) {
    return scaled(unitLadder, instrumentId, quantityMinor, out);
  }

  @Override
  public boolean spreadPv01(
      final int instrumentId, final long quantityMinor, final Dv01Vector out) {
    return scaled(spreadLadder, instrumentId, quantityMinor, out);
  }

  private boolean scaled(
      final double[][] ladders, final int instrumentId, final long quantityMinor,
      final Dv01Vector out) {
    out.clear();
    final int i = directory.indexOf(instrumentId);
    if (i < 0) {
      return false;
    }
    final Instrument inst = directory.byIndex(i);
    out.currency(inst.currency());
    final double scale = inst.major(quantityMinor);
    for (int k = 0; k < KeyRateTenors.COUNT; k++) {
      out.set(k, ladders[i][k] * scale);
    }
    return true;
  }

  @Override
  public boolean carry(final int instrumentId, final CarryRoll out) {
    final int i = directory.indexOf(instrumentId);
    if (i < 0) {
      out.clear();
      return false;
    }
    out.set(options.carryHorizonDays(), carryBp[i], rollBp[i]);
    return true;
  }

  /**
   * Fair value of an instrument as a number: the par rate of a swap (decimal), the clean price of
   * a bond (per 100); NaN for an unknown instrument.
   */
  public double fairValue(final int instrumentId) {
    final int i = directory.indexOf(instrumentId);
    return i < 0 ? Double.NaN : fairValue[i];
  }

  /**
   * The model value before the residual: the leave-one-out or curve par rate of a swap, the clean
   * price of a bond at its leave-one-out fitted spread; NaN for an unknown instrument.
   */
  public double modelValue(final int instrumentId) {
    final int i = directory.indexOf(instrumentId);
    return i < 0 ? Double.NaN : modelValue[i];
  }

  /** The market z-spread of a bond to the SOFR curve last accepted, decimal; NaN for a non-bond. */
  public double bondSpread(final int instrumentId) {
    final int i = directory.indexOf(instrumentId);
    return i < 0 || isSwap[i] ? Double.NaN : bondSpread[kindIndex[i]];
  }

  /** The z-spread a bond's fair value is priced at, decimal; NaN for a non-bond. */
  public double fairSpread(final int instrumentId) {
    final int i = directory.indexOf(instrumentId);
    return i < 0 || isSwap[i] ? Double.NaN : bondFairSpread[kindIndex[i]];
  }

  /**
   * Rich or cheap in basis points: the bond's market z-spread minus the fitted curve's spread for
   * it with the bond left out of the fit. Positive is cheap. NaN for a non-bond.
   */
  public double richCheapBp(final int instrumentId) {
    final int i = directory.indexOf(instrumentId);
    if (i < 0 || isSwap[i]) {
      return Double.NaN;
    }
    final int j = kindIndex[i];
    return (bondSpread[j] - bondModelSpread[j]) / BP;
  }

  /** The fitted on-the-run premium in basis points; zero when the universe cannot identify it. */
  public double onTheRunPremiumBp() {
    return fit.onTheRunPremium() / BP;
  }

  /** The fitted Treasury spread curve (read-only use). */
  public TreasurySpreadCurve treasuryFit() {
    return fit;
  }

  /** Settlement date of a bond on the current business date, or -1 for a non-bond. */
  public int settlementDate(final int instrumentId) {
    final int i = directory.indexOf(instrumentId);
    return i < 0 || isSwap[i] ? -1 : day.settlement[kindIndex[i]];
  }

  /** Accrued interest per 100 of a bond at its settlement date, or NaN for a non-bond. */
  public double accrued(final int instrumentId) {
    final int i = directory.indexOf(instrumentId);
    return i < 0 || isSwap[i] ? Double.NaN : day.accrued[kindIndex[i]];
  }

  /** Number of curve inputs on the current business date. */
  public int inputCount() {
    return day.inputs;
  }

  /** The {@link V2InputIds} id of input i. */
  public int inputId(final int i) {
    return day.inputId[i];
  }

  /** True once the feed has sent input i (or it was a starting quote of the universe). */
  public boolean inputFed(final int i) {
    return day.fed[i];
  }

  /** Inputs of the curve the feed has never sent: they are seeded from the others. */
  public int unfedInputs() {
    int n = 0;
    for (int i = 0; i < day.inputs; i++) {
      n += day.fed[i] ? 0 : 1;
    }
    return n;
  }

  /** Inputs recorded and not yet applied; a reprice is due on {@link #repriceIfDirty} when above zero. */
  public int pendingInputs() {
    return pendingCount;
  }

  /**
   * Reprices done: the start-of-day marks, then one per applied round and one per date change.
   * Building the start again on another date at the first timed call starts the count again.
   */
  public long reprices() {
    return reprices;
  }

  /** Curve solves done; a round whose quotes are all unchanged does not solve. */
  public long rebuilds() {
    return rebuilds;
  }

  /** Inputs dropped: outside the band, or part of a round the curve could not solve. */
  public long rejectedInputs() {
    return rejectedInputs;
  }

  /** Times a bond's book was held out of the fit: no spread within 500 bp, or a jump. */
  public long rejectedBondBooks() {
    return rejectedBondBooks;
  }

  /** Exact leave-one-out references taken on the current date, the first included. */
  public long leaveOneOutReferences() {
    return day.loo.references();
  }

  /** Times an exact leave-one-out reference could not be solved and the previous one was kept. */
  public long referenceFailures() {
    return referenceFailures;
  }

  /** Business-date changes since the first timed call. */
  public long dateChanges() {
    return dateChanges;
  }
}
