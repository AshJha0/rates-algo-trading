package io.ratesalgo.app.pricing;

import io.ratesalgo.analytics.bond.BondConvention;
import io.ratesalgo.analytics.bond.BondPricer;
import io.ratesalgo.analytics.bond.FixedRateBond;
import io.ratesalgo.analytics.curve.CurveBuilder;
import io.ratesalgo.analytics.curve.CurveInstrument;
import io.ratesalgo.analytics.curve.DiscountCurve;
import io.ratesalgo.analytics.curve.Interpolation;
import io.ratesalgo.analytics.curve.OisSwapInstrument;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.risk.KeyRateRisk;
import io.ratesalgo.analytics.swap.FixingHistory;
import io.ratesalgo.analytics.swap.OisSwap;
import io.ratesalgo.analytics.swap.OisSwapPricer;
import io.ratesalgo.analytics.swap.SwapConvention;
import io.ratesalgo.api.BookSource;
import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.FairValueSource;
import io.ratesalgo.api.InMemoryInstrumentDirectory;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.InstrumentDirectory;
import io.ratesalgo.api.KeyRateTenors;
import io.ratesalgo.api.SensitivityProvider;
import java.util.ArrayList;
import java.util.List;

/**
 * The pricing binding (API_SPEC.md Section 18.3): P3's {@link FairValueSource} and {@link
 * SensitivityProvider} over P1's analytics, for one currency's instrument group.
 *
 * <p>One USD SOFR OIS curve is bootstrapped from the par rates of the swap templates. Each
 * CurveInput (template 3, kind ParSwap) that reaches the loop moves one pillar's quote and calls
 * {@link CurveBuilder#rebuild}, which re-solves only that pillar and the ones after it; then every
 * swap's par rate and every instrument's unit key-rate ladder are recomputed in place.
 *
 * <ul>
 *   <li>Swaps: fair mid is the curve-implied par rate in percent; the ladder is the gradient of a
 *       unit payer swap's PV in the curve's log discount factors, mapped to quote sensitivities
 *       through the bootstrap Jacobian (the same computation as {@link KeyRateRisk#fromGradient}).
 *       The unit swap's fixed rate is the template's starting par rate, so the swap object is
 *       built once; against the current par rate the ladder differs by the fixed-rate gap times
 *       the annuity's own sensitivity, under 0.1 percent of the DV01 for a 5 bp move.
 *   <li>Bonds: fair mid is the mid of the composite book; the quoting engine itself prefers the
 *       composite microprice (FairValueEngine) and falls back to this. The ladder is the gradient
 *       of the curve dirty price per unit face.
 * </ul>
 *
 * <p>Ladders are mapped to the eight key-rate tenors by {@link Dv01Adapter}. After construction
 * nothing allocates: the bootstrap, the pricers' gradients and the Jacobian solve all work in
 * preallocated arrays. Single-threaded: the loop thread owns it.
 */
public final class AnalyticsPricing implements FairValueSource, SensitivityProvider {

  /** Per-cent to decimal and Decimal9 together: a percent mantissa times this is a decimal rate. */
  private static final double MANTISSA_PERCENT_TO_DECIMAL = 1e-11;

  private final UsdUniverse universe;
  private final int today;
  private final CurveBuilder builder;
  private final DiscountCurve curve;
  private final OisSwapPricer swapPricer = new OisSwapPricer();
  private final BondPricer bondPricer = new BondPricer();
  private final InMemoryInstrumentDirectory directory = new InMemoryInstrumentDirectory();
  private final Dv01Adapter adapter;

  /** Curve pillar (builder order) of each swap index, and the reverse. */
  private final int[] pillarOfSwap;
  private final int[] swapIds;
  private final OisSwap[] unitSwaps;
  private final FixedRateBond[] bonds;
  private final int[] bondSettlement;

  /** Per dense directory index. */
  private final boolean[] isSwap;
  private final int[] kindIndex;
  private final long[] fairMid;
  private final long[] referenceNanos;
  private final double[] parRate;
  private final double[][] unitLadder;

  private final double[] gradient;
  private final double[] keyRates = new double[KeyRateTenors.COUNT];
  private BookSource book;
  private long newestInput = NO_VALUE;
  private long rebuilds;

  /**
   * Builds the curve from the universe's starting par rates and the instrument directory.
   *
   * @param universe the instruments
   * @param startNanos epoch nanoseconds the starting quotes are stamped with
   */
  public AnalyticsPricing(final UsdUniverse universe, final long startNanos) {
    this.universe = universe;
    this.today = universe.valuationDate();
    final List<UsdUniverse.SwapSpec> swaps = universe.swaps();
    final List<CurveInstrument> instruments = new ArrayList<>();
    for (final UsdUniverse.SwapSpec s : swaps) {
      instruments.add(
          new OisSwapInstrument(
              SwapConvention.USD_SOFR, today, s.tenorMonths(), s.tenor(), s.initialMid() * MANTISSA_PERCENT_TO_DECIMAL));
    }
    builder = new CurveBuilder(today, instruments, Interpolation.LINEAR_LOG_DF, 0.0, null, FixingHistory.EMPTY);
    builder.build(CurveBuilder.Method.AUTO);
    curve = builder.curve();
    final int pillars = curve.pillarCount();
    gradient = new double[pillars];
    final double[] years = new double[pillars];
    for (int i = 0; i < pillars; i++) {
      years[i] = curve.time(curve.pillarDate(i));
    }
    adapter = new Dv01Adapter(years);

    swapIds = new int[swaps.size()];
    pillarOfSwap = new int[swaps.size()];
    unitSwaps = new OisSwap[swaps.size()];
    for (int j = 0; j < swaps.size(); j++) {
      final UsdUniverse.SwapSpec s = swaps.get(j);
      swapIds[j] = s.instrumentId();
      unitSwaps[j] =
          OisSwap.spot(SwapConvention.USD_SOFR, today, s.tenorMonths(), 1.0, s.initialMid() * MANTISSA_PERCENT_TO_DECIMAL, true);
      pillarOfSwap[j] = -1;
      for (int p = 0; p < pillars; p++) {
        if (builder.instruments().get(p).tenor().equals(s.tenor())) {
          pillarOfSwap[j] = p;
        }
      }
      final double annuity = swapPricer.annuity(unitSwaps[j], curve, today);
      directory.add(
          Instrument.swap(s.instrumentId(), s.securityId(), CurrencyCode.USD, s.tenorMonths() / 12.0, annuity, 0, 0.0,
              5_000_000_000L));
    }
    final List<UsdUniverse.BondSpec> bondSpecs = universe.bonds();
    bonds = new FixedRateBond[bondSpecs.size()];
    bondSettlement = new int[bondSpecs.size()];
    for (int j = 0; j < bondSpecs.size(); j++) {
      final UsdUniverse.BondSpec b = bondSpecs.get(j);
      final int dated = epochDay(b.bond().issueDate());
      final int maturity = epochDay(b.bond().maturity());
      bonds[j] = new FixedRateBond(b.securityId(), BondConvention.UST, b.bond().couponMantissa() * MANTISSA_PERCENT_TO_DECIMAL,
          dated, maturity);
      bondSettlement[j] = Math.max(bonds[j].settlementDate(today), dated);
      directory.add(Instrument.bond(b.instrumentId(), b.securityId(), CurrencyCode.USD,
          Math.max((maturity - today) / 365.25, 0.1), 2_500_000_000L));
    }

    final int n = directory.size();
    isSwap = new boolean[n];
    kindIndex = new int[n];
    fairMid = new long[n];
    referenceNanos = new long[n];
    parRate = new double[n];
    unitLadder = new double[n][KeyRateTenors.COUNT];
    for (int j = 0; j < swapIds.length; j++) {
      final int i = directory.indexOf(swapIds[j]);
      isSwap[i] = true;
      kindIndex[i] = j;
    }
    for (int j = 0; j < bondSpecs.size(); j++) {
      kindIndex[directory.indexOf(bondSpecs.get(j).instrumentId())] = j;
    }
    newestInput = startNanos;
    recompute();
  }

  private static int epochDay(final java.time.LocalDate d) {
    return Dates.of(d.getYear(), d.getMonthValue(), d.getDayOfMonth());
  }

  /** The instruments, with swap annuities from the starting curve. */
  public InstrumentDirectory directory() {
    return directory;
  }

  /** The universe the binding was built from. */
  public UsdUniverse universe() {
    return universe;
  }

  /** The composite book bonds take their fair mid from. */
  public void book(final BookSource composite) {
    this.book = composite;
  }

  /** The curve builder (read-only use: tests compare against direct analytics). */
  public CurveBuilder builder() {
    return builder;
  }

  /** Incremental rebuilds done since construction. */
  public long rebuilds() {
    return rebuilds;
  }

  /**
   * One curve input from the loop. A ParSwap quote for a curve swap moves its pillar (value is the
   * par rate in percent as a Decimal9 mantissa); an unchanged quote only refreshes the reference
   * time. Returns true if the instrument is a curve pillar.
   */
  public boolean onCurveInput(final int instrumentId, final long valueMantissa, final long timestampNanos) {
    final int i = directory.indexOf(instrumentId);
    if (i < 0 || !isSwap[i] || pillarOfSwap[kindIndex[i]] < 0) {
      return false;
    }
    newestInput = Math.max(newestInput, timestampNanos);
    final int pillar = pillarOfSwap[kindIndex[i]];
    final double quote = valueMantissa * MANTISSA_PERCENT_TO_DECIMAL;
    if (Double.compare(quote, builder.instruments().get(pillar).quote()) != 0) {
      builder.rebuild(pillar, quote);
      rebuilds++;
    }
    recompute();
    return true;
  }

  private void recompute() {
    for (int j = 0; j < swapIds.length; j++) {
      final int i = directory.indexOf(swapIds[j]);
      final OisSwap swap = unitSwaps[j];
      final double par = swapPricer.parRate(swap, curve, FixingHistory.EMPTY, today);
      parRate[i] = par;
      fairMid[i] = Decimal9.round(par * 100.0);
      referenceNanos[i] = newestInput;
      java.util.Arrays.fill(gradient, 0.0);
      swapPricer.pvGradient(swap, curve, curve, FixingHistory.EMPTY, today, gradient, gradient);
      ladder(gradient, unitLadder[i]);
    }
    for (int j = 0; j < bonds.length; j++) {
      final int i = directory.indexOf(universe.bonds().get(j).instrumentId());
      java.util.Arrays.fill(gradient, 0.0);
      bondPricer.dirtyFromCurveGradient(bonds[j], curve, bondSettlement[j], 0.01, gradient);
      ladder(gradient, unitLadder[i]);
    }
  }

  /** dValue/d ln P at the pillars into a per-unit key-rate ladder on the eight tenors. */
  private void ladder(final double[] g, final double[] out) {
    builder.quoteSensitivities(g);
    for (int p = 0; p < g.length; p++) {
      g[p] *= builder.instruments().get(p).quotePerRate() * KeyRateRisk.BP;
    }
    java.util.Arrays.fill(keyRates, 0.0);
    adapter.accumulate(g, 1.0, keyRates);
    System.arraycopy(keyRates, 0, out, 0, KeyRateTenors.COUNT);
  }

  /** Curve-implied par rate of a swap as a decimal, or NaN for a non-swap. */
  public double parRate(final int instrumentId) {
    final int i = directory.indexOf(instrumentId);
    return i < 0 || !isSwap[i] ? Double.NaN : parRate[i];
  }

  /** PV of a spot-starting swap of this template on the binding's curve (tests and reports). */
  public double presentValue(final int instrumentId, final double notional, final double fixedRate, final boolean payer) {
    final UsdUniverse.SwapSpec s = universe.swap(instrumentId);
    final OisSwap swap = OisSwap.spot(SwapConvention.USD_SOFR, today, s.tenorMonths(), notional, fixedRate, payer);
    return swapPricer.pv(swap, curve, FixingHistory.EMPTY, today);
  }

  /** The adapter from curve-pillar ladders to key-rate tenors. */
  public Dv01Adapter adapter() {
    return adapter;
  }

  @Override
  public long fairMid(final int instrumentId) {
    final int i = directory.indexOf(instrumentId);
    if (i < 0) {
      return NO_VALUE;
    }
    if (isSwap[i]) {
      return fairMid[i];
    }
    if (book == null) {
      return NO_VALUE;
    }
    final long bid = book.bestBid(instrumentId);
    final long ask = book.bestAsk(instrumentId);
    return bid == NO_VALUE || ask == NO_VALUE ? NO_VALUE : (bid + ask) / 2;
  }

  @Override
  public long referenceNanos(final int instrumentId) {
    final int i = directory.indexOf(instrumentId);
    if (i < 0) {
      return NO_VALUE;
    }
    if (isSwap[i]) {
      return referenceNanos[i];
    }
    return book == null ? NO_VALUE : book.updateNanos(instrumentId);
  }

  @Override
  public boolean pv01(final int instrumentId, final long quantityMinor, final Dv01Vector out) {
    out.clear();
    final int i = directory.indexOf(instrumentId);
    if (i < 0) {
      return false;
    }
    final Instrument inst = directory.byIndex(i);
    out.currency(inst.currency());
    final double scale = inst.major(quantityMinor);
    final double[] unit = unitLadder[i];
    for (int k = 0; k < KeyRateTenors.COUNT; k++) {
      out.set(k, unit[k] * scale);
    }
    return true;
  }
}
