package io.ratesalgo.app.backtest;

import io.ratesalgo.app.loop.DeskProfile;
import io.ratesalgo.app.loop.DeskSettings;
import io.ratesalgo.app.loop.DeskSettings.StrategySwitches;
import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.backtest.Strategy;
import io.ratesalgo.backtest.experiment.ArmFactory;
import io.ratesalgo.backtest.sim.SimDay;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The desk's arms for simulator v2 experiments: each name a pre-registration's config uses, and
 * the {@link DeskProfile} the desk runs under it (docs/research/STRATEGY_V2.md Section 8,
 * research/prereg/R5-FAM-001.yaml).
 *
 * <table>
 *   <caption>Arms</caption>
 *   <tr><th>Arm</th><th>Profile</th></tr>
 *   <tr><td>baseline</td><td>{@link DeskProfile#simV2} with {@link DeskSettings#defaults()}: every strategy switch off, on the common footing</td></tr>
 *   <tr><td>v2</td><td>{@link DeskProfile#v2()}: every strategy switch on, on the common footing</td></tr>
 *   <tr><td>v2_minus_cover_pricing</td><td>v2 without break-even cover-relative pricing</td></tr>
 *   <tr><td>v2_minus_reservation_price</td><td>v2 without the capped reservation price</td></tr>
 *   <tr><td>v2_minus_factor_hedging</td><td>v2 without the factor covariance, factor hedging and tick hedge cost</td></tr>
 *   <tr><td>v2_minus_internalisation</td><td>v2 with the default hedger</td></tr>
 *   <tr><td>v2_minus_markouts</td><td>v2 without the client markout store</td></tr>
 *   <tr><td>v2_minus_quote_protection</td><td>v2 without tick rounding, the volatility time to live and the pull</td></tr>
 *   <tr><td>cover_only</td><td>baseline with cover pricing and nothing else</td></tr>
 *   <tr><td>legacy (default and engine, its older names)</td><td>{@link DeskProfile#defaults()}: the desk as built in 1.1.0</td></tr>
 * </table>
 *
 * <p>The common footing is the simulator v2 market and universe ({@link UsdUniverse#simV2()}: every
 * swap tenor of the model, the Treasuries and TY), pricing v2 and the ladder router. Every arm but
 * {@code legacy} stands on it, so any two of them differ only in their strategy switches ({@link
 * DeskProfile#resolved}). {@code legacy} is the recorded universe, pricing v1 and the touch router;
 * R5-FAM-001 reports it as description only and no hypothesis may use it.
 */
public final class DeskArms implements ArmFactory {

  /** The ten arms of the family R5-FAM-001, baseline first, the descriptive arm last. */
  public static final List<String> FAMILY =
      List.of(
          "baseline", "v2", "v2_minus_cover_pricing", "v2_minus_reservation_price", "v2_minus_factor_hedging",
          "v2_minus_internalisation", "v2_minus_markouts", "v2_minus_quote_protection", "cover_only", "legacy");

  /** The arms of the family that stand on the common footing: all but {@code legacy}. */
  public static final List<String> COMMON_FOOTING = FAMILY.subList(0, FAMILY.size() - 1);

  private static final Map<String, DeskProfile> ARMS = new LinkedHashMap<>();

  static {
    final DeskProfile baseline = DeskProfile.simV2(DeskSettings.defaults());
    final DeskProfile v2 = DeskProfile.v2();
    final StrategySwitches s = v2.settings().strategy();
    ARMS.put("baseline", baseline);
    ARMS.put("v2", v2);
    ARMS.put("v2_minus_cover_pricing", ablation(v2, s.withCoverPricing(false)));
    ARMS.put("v2_minus_reservation_price", ablation(v2, s.withReservationPrice(false)));
    ARMS.put("v2_minus_factor_hedging", ablation(v2, s.withFactorHedging(false)));
    ARMS.put("v2_minus_internalisation", ablation(v2, s.withInternalisingHedger(false)));
    ARMS.put("v2_minus_markouts", ablation(v2, s.withMarkoutStore(false)));
    ARMS.put("v2_minus_quote_protection", ablation(v2, s.withQuoteProtection(false, 0.0)));
    ARMS.put("cover_only", ablation(baseline, baseline.settings().strategy().withCoverPricing(true)));
    ARMS.put("legacy", DeskProfile.defaults());
    // older names of the recorded desk, for the superseded drafts
    ARMS.put("default", DeskProfile.defaults());
    ARMS.put("engine", DeskProfile.defaults());
  }

  private static DeskProfile ablation(final DeskProfile base, final StrategySwitches switches) {
    return base.withSettings(base.settings().withStrategy(switches));
  }

  /** Every arm name. */
  public static List<String> names() {
    return List.copyOf(ARMS.keySet());
  }

  /** The profile of an arm. */
  public static DeskProfile profile(final String name) {
    final DeskProfile p = ARMS.get(name);
    if (p == null) {
      throw new IllegalArgumentException("unknown desk arm '" + name + "'; known: " + names());
    }
    return p;
  }

  /** The settings of an arm. */
  public static DeskSettings settings(final String name) {
    return profile(name).settings();
  }

  @Override
  public String refusal(final String name) {
    return ARMS.containsKey(name) ? null : "unknown desk arm '" + name + "'; known: " + names();
  }

  @Override
  public Strategy create(final String name, final SimDay day) {
    final DeskProfile profile = profile(name);
    final UsdUniverse universe = profile.newUniverse();
    // every arm is priced as of the simulator v2 desk's own business date, not the label date
    return new DeskStrategy(profile, universe, SimBinding.of(day.model().spec(), universe))
        .atSessionOpen(day.openEpochNanos());
  }

  @Override
  public String configurationHash(final String name) {
    return profile(name).configurationHash();
  }
}
