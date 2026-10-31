package io.ratesalgo.strategy.experiment;

import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.InstrumentSet;
import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.model.CompetitorModel;
import io.ratesalgo.backtest.model.HitRateModel;
import io.ratesalgo.backtest.model.Preset;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.util.Yaml;
import io.ratesalgo.hedge.AxeSource;
import io.ratesalgo.quoting.ClientTier;
import io.ratesalgo.strategy.AxeBook;
import io.ratesalgo.strategy.ClientTiering;
import io.ratesalgo.strategy.CouponCarry;
import io.ratesalgo.strategy.DeskQuoter;
import io.ratesalgo.strategy.EnvelopeSizer;
import io.ratesalgo.strategy.FlowRateEstimator;
import io.ratesalgo.strategy.FundingPenalty;
import io.ratesalgo.strategy.HedgeBudget;
import io.ratesalgo.strategy.HedgeTiming;
import io.ratesalgo.strategy.InventoryPenalty;
import io.ratesalgo.strategy.MarkoutTiering;
import io.ratesalgo.strategy.NettingHedgeBudget;
import io.ratesalgo.strategy.OptimalStoppingTiming;
import io.ratesalgo.strategy.QuoteSizer;
import io.ratesalgo.strategy.RiskLadder;
import io.ratesalgo.strategy.SpreadOptimizer;
import io.ratesalgo.strategy.StaticTiering;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the baseline and the treatments of the desk-feature experiments from the experiment
 * config (data/experiments/R2B.config.yaml; API_SPEC.md Section 19.9).
 *
 * <p>Treatments: {@value #INTERNALISATION}, {@value #AXES}, {@value #SIZING}, {@value #TIERING},
 * {@value #TIMING}, {@value #FUNDING} switch on one feature each over the baseline; {@value
 * #DESK_ALL} switches on all six.
 */
public final class DeskFactory {

  public static final String BASELINE = "baseline";
  public static final String INTERNALISATION = "internalisation";
  public static final String AXES = "axes";
  public static final String SIZING = "sizing";
  public static final String TIERING = "tiering";
  public static final String TIMING = "timing";
  public static final String FUNDING = "funding";
  public static final String DESK_ALL = "desk_all";

  /** The treatments, in pre-registration order (R2B-001 to R2B-007). */
  public static final List<String> TREATMENTS =
      List.of(INTERNALISATION, AXES, SIZING, TIERING, TIMING, FUNDING, DESK_ALL);

  private static final String WHERE = "desk experiment config";

  private final Map<String, Object> config;
  private final Presets presets;
  private final InstrumentSet instruments;
  private final int clientsPerTier;
  private final CouponCarry carry;

  public DeskFactory(final Map<String, Object> config, final Presets presets, final InstrumentSet instruments) {
    this.config = new java.util.LinkedHashMap<>(config);
    this.presets = presets;
    this.instruments = instruments;
    this.clientsPerTier = (int) presets.get("usd_synthetic_market").value("clients_per_tier");
    final Map<Integer, Double> coupons = new TreeMap<>();
    for (final Map.Entry<String, Object> e : section("carry").entrySet()) {
      coupons.put(Integer.parseInt(e.getKey()), Yaml.toDouble(e.getValue(), WHERE + ".carry") / 100.0);
    }
    this.carry = new CouponCarry(coupons);
  }

  private Map<String, Object> section(final String name) {
    return Yaml.map(config, name, WHERE);
  }

  private double num(final String section, final String key) {
    return Yaml.number(section(section), key, WHERE + "." + section);
  }

  private static long nanos(final double seconds) {
    return Math.round(seconds * 1e9);
  }

  public CouponCarry carry() {
    return carry;
  }

  public int clientsPerTier() {
    return clientsPerTier;
  }

  /** Limit on absolute net DV01, major currency per bp. */
  public double limitDv01() {
    return num("limits", "net_dv01_usd");
  }

  /** Limit on absolute position per instrument, millions. */
  public double positionLimitMm() {
    return num("limits", "position_mm");
  }

  public long markoutHorizonNanos() {
    return nanos(num("baseline", "markout_horizon_s"));
  }

  /** Rate volatility of the synthetic market's level factor, bp per sqrt(second). */
  public double sigmaBpPerSqrtSecond() {
    return presets.get("usd_synthetic_market").value("level_vol_bp_per_sqrt_min") / Math.sqrt(60.0);
  }

  /** Risk aversion used by the timing rule and the risk-adjusted hedge cost metric. */
  public double gamma() {
    return num("timing", "gamma");
  }

  public double bondBalanceSheetPerYear() {
    return num("funding", "bond_bs_per_year");
  }

  public double swapBalanceSheetPerYear() {
    return num("funding", "swap_bs_per_year");
  }

  /** Cost per DV01 of a futures hedge under the hedge cost model: half the book spread plus the fee. */
  public double hedgeCostPerDv01() {
    final Instrument ty = instruments.get(InstrumentSet.TY_FUTURE);
    final double tickValue = ty.tickMantissa() / 1e9 * ty.valuePerUnit() * ty.lotSize();
    final double perContract = Math.abs(ty.dv01PerUnit()) * ty.lotSize();
    final Preset hedge = presets.get("usd_hedge_cost");
    final double halfSpread = 0.5 * presets.get("usd_synthetic_market").value("future_spread_ticks") * tickValue;
    return (halfSpread + hedge.value("fee_per_lot")) / perContract;
  }

  /** Impact coefficient k with impact = k * dv01^2 under the hedge cost model's linear impact per lot. */
  public double impactPerDv01Squared() {
    final Instrument ty = instruments.get(InstrumentSet.TY_FUTURE);
    final double tickValue = ty.tickMantissa() / 1e9 * ty.valuePerUnit() * ty.lotSize();
    final double perContract = Math.abs(ty.dv01PerUnit()) * ty.lotSize();
    return presets.get("usd_hedge_cost").value("impact_ticks_per_lot") * tickValue / (perContract * perContract);
  }

  private SpreadOptimizer spread() {
    final Map<String, Object> b = section("baseline");
    final List<Object> adverse = Yaml.asList(Yaml.require(b, "adverse_bp_by_tier", WHERE), WHERE);
    final double[] a = new double[adverse.size()];
    for (int i = 0; i < a.length; i++) {
      a[i] = Yaml.toDouble(adverse.get(i), WHERE + ".baseline.adverse_bp_by_tier");
    }
    return new SpreadOptimizer(
        HitRateModel.fromPreset(presets.get(HitRateModel.PRESET), BandPoint.CENTRAL),
        CompetitorModel.fromPreset(presets.get(CompetitorModel.PRESET)),
        a,
        num("baseline", "swap_hedge_cost_bp"),
        num("baseline", "bond_hedge_cost_bp"),
        num("baseline", "max_half_spread_bp"),
        num("baseline", "grid_step_bp"));
  }

  /** A fresh client tiering of the tiering feature; its state is meant to persist across days. */
  public MarkoutTiering newTiering() {
    final Map<String, Object> t = section("tiering");
    final double[] demote = doubles(t, "demote_below_bp");
    final double[] promote = doubles(t, "promote_above_bp");
    final List<ClientTier> tiers = new ArrayList<>();
    for (int i = 0; i < demote.length; i++) {
      tiers.add(new ClientTier(i + 1, 1.0, Long.MAX_VALUE, true, false));
    }
    return new MarkoutTiering(
        tiers,
        demote,
        promote,
        Yaml.integer(t, "min_sample", WHERE),
        num("tiering", "alpha"),
        (int) Yaml.integer(t, "start_tier", WHERE) - 1,
        nanos(num("tiering", "review_interval_s")),
        (int) Yaml.integer(t, "proposer_id", WHERE),
        (int) Yaml.integer(t, "approver_id", WHERE),
        256);
  }

  private static double[] doubles(final Map<String, Object> m, final String key) {
    final List<Object> list = Yaml.asList(Yaml.require(m, key, WHERE), WHERE + "." + key);
    final double[] out = new double[list.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = Yaml.toDouble(list.get(i), WHERE + "." + key);
    }
    return out;
  }

  /** The baseline strategy: every feature at today's behaviour. */
  public DeskQuoter baseline() {
    final RiskLadder ladder = new RiskLadder(instruments.all());
    return new DeskQuoter(
        BASELINE,
        DeskQuoter.baselineFeatures(
            ladder, spread(), num("baseline", "hedge_threshold_dv01"), clientsPerTier, markoutHorizonNanos()));
  }

  /**
   * A treatment.
   *
   * @param tiering the persistent tiering for {@value #TIERING} and {@value #DESK_ALL}; ignored otherwise
   */
  public DeskQuoter treatment(final String name, final ClientTiering tiering) {
    if (!TREATMENTS.contains(name)) {
      throw new IllegalArgumentException("unknown treatment " + name);
    }
    final boolean all = DESK_ALL.equals(name);
    final RiskLadder ladder = new RiskLadder(instruments.all());
    final HedgeBudget budget =
        all || INTERNALISATION.equals(name)
            ? new NettingHedgeBudget(
                num(INTERNALISATION, "soft_dv01"),
                num(INTERNALISATION, "hard_dv01"),
                num(INTERNALISATION, "retain_dv01"),
                nanos(num(INTERNALISATION, "hold_s")))
            : HedgeBudget.threshold(num("baseline", "hedge_threshold_dv01"));
    final HedgeTiming timing =
        all || TIMING.equals(name)
            ? new OptimalStoppingTiming(
                new FlowRateEstimator(num(TIMING, "flow_tau_s"), num(TIMING, "flow_prior_dv01_per_s")),
                gamma(),
                sigmaBpPerSqrtSecond(),
                hedgeCostPerDv01(),
                impactPerDv01Squared(),
                nanos(num(TIMING, "wait_step_s")),
                nanos(num(TIMING, "max_wait_s")),
                (int) Yaml.integer(section(TIMING), "slices", WHERE),
                nanos(num(TIMING, "slice_interval_s")))
            : HedgeTiming.ALWAYS_IMMEDIATE;
    final AxeSource axes =
        all || AXES.equals(name)
            ? new AxeBook(ladder, num(AXES, "max_bp"), num(AXES, "budget_dv01"), num(AXES, "bucket_weight"))
            : AxeSource.NONE;
    final QuoteSizer sizer;
    if (all || SIZING.equals(name)) {
      final Preset flow = presets.get("usd_rfq_flow");
      sizer =
          new EnvelopeSizer(
              limitDv01(),
              num(SIZING, "soft_util"),
              num(SIZING, "hard_util"),
              num(SIZING, "risk_premium_bp"),
              EnvelopeSizer.lognormalQuantileMm(
                  flow.value("size_mu_ln_mm"), flow.value("size_sigma_ln_mm"), num(SIZING, "block_quantile_z")),
              num(SIZING, "block_premium_bp"));
    } else {
      sizer = QuoteSizer.UNLIMITED;
    }
    final ClientTiering tiers = all || TIERING.equals(name) ? tiering : new StaticTiering(clientsPerTier);
    final InventoryPenalty penalty;
    if (all || FUNDING.equals(name)) {
      final double[] coupons = new double[ladder.size()];
      for (int i = 0; i < coupons.length; i++) {
        coupons[i] = carry.coupon(ladder.instrumentId(i));
      }
      penalty =
          new FundingPenalty(
              ladder, coupons, bondBalanceSheetPerYear(), swapBalanceSheetPerYear(), num(FUNDING, "holding_days"));
    } else {
      penalty = InventoryPenalty.NONE;
    }
    return new DeskQuoter(
        name,
        new DeskQuoter.Features(
            ladder, spread(), budget, timing, axes, sizer, tiers, penalty, markoutHorizonNanos()));
  }
}
