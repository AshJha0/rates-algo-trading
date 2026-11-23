package io.ratesalgo.app.backtest;

import io.ratesalgo.app.loop.DeskSettings;
import io.ratesalgo.app.pricing.PricingBindings;
import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.backtest.Strategy;
import io.ratesalgo.backtest.experiment.ArmFactory;
import io.ratesalgo.backtest.sim.SimDay;
import io.ratesalgo.quoting.StrategyConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The desk's arms for simulator v2 experiments: each name a pre-registration's config uses, and
 * what the desk runs under it (docs/research/STRATEGY_V2.md Section 8).
 *
 * <table>
 *   <caption>Arms</caption>
 *   <tr><th>Arm</th><th>Desk</th></tr>
 *   <tr><td>default, engine</td><td>{@link DeskSettings#defaults()}</td></tr>
 *   <tr><td>v2</td><td>{@link DeskSettings#strategyV2()}</td></tr>
 *   <tr><td>cover_pricing</td><td>defaults with {@code StrategyConfig.coverPricing(true)} only (R5-001)</td></tr>
 *   <tr><td>v2_without_reservation</td><td>V2 with {@code reservationPrice(false)} (R5-002)</td></tr>
 *   <tr><td>v2_without_factor</td><td>V2 with factor covariance, factor hedging and tick hedge cost off (R5-003)</td></tr>
 *   <tr><td>v2_hedge_at_trigger</td><td>V2 with the default hedger (R5-004)</td></tr>
 *   <tr><td>v2_without_markouts</td><td>V2 with {@code markoutStore(false)} (R5-005)</td></tr>
 *   <tr><td>v2_without_protection</td><td>V2 with tick rounding and volatility TTL off, pull fraction 0 (R5-006)</td></tr>
 *   <tr><td>default_pricing_v2, v2_pricing_v2</td><td>the same with {@link PricingBindings.Version#V2}</td></tr>
 *   <tr><td>default_router_ladder, v2_router_ladder</td><td>the same with the router's ladder model</td></tr>
 * </table>
 *
 * <p>Only {@code default}, {@code engine} and {@code v2} can be built today. {@link DeskSettings}
 * fixes the strategy switches, the hedger, the pricing binding and the router parameters from its
 * two presets, and the desk loop (rates-app {@code loop/}, not owned by the back-test) reads them
 * from there, so an arm that changes one switch needs a hook in that file. Every other arm is
 * described here exactly (its {@link Arm#quoting} and {@link Arm#defaultHedger}), and {@link
 * #refusal} names the missing hook, so a run that needs it is refused before it starts rather than
 * running the wrong desk. The hook is listed in the round's report (wiring W1 to W3).
 */
public final class DeskArms implements ArmFactory {

  /** The router model of an arm. */
  public enum Router {
    /** {@code RouterPresets.global()}, what the desk loop builds. */
    TOUCH,
    /** {@code RouterPresets.ladder()} (DECISIONS.md DR-R6-R01). */
    LADDER
  }

  /**
   * One arm.
   *
   * @param base the settings preset it starts from
   * @param quoting changes to the preset's strategy switches, identity for none
   * @param pullFraction the quote pull fraction, NaN to keep the preset's
   * @param defaultHedger true to use the default preset's hedger whatever the base
   * @param pricing the pricing binding
   * @param router the router model
   * @param wiring the hook the arm needs in rates-app's desk loop, null when it needs none
   */
  public record Arm(
      DeskSettings.StrategyPreset base,
      UnaryOperator<StrategyConfig> quoting,
      double pullFraction,
      boolean defaultHedger,
      PricingBindings.Version pricing,
      Router router,
      String wiring) {}

  static final String W1 =
      "needs wiring W1: DeskSettings must carry strategy-switch and hedger overrides that DeskGroup passes to"
          + " Desk.Inputs (rates-app loop/, not owned by the back-test)";
  static final String W2 =
      "needs wiring W2: DeskGroup must build its pricing with PricingBindings.create(settings.pricing(), ...)"
          + " instead of new AnalyticsPricing";
  static final String W3 =
      "needs wiring W3: DeskGroup must take its RouterParams from the settings (RouterPresets.ladder()) instead of"
          + " RouterPresets.global()";

  private static final Map<String, Arm> ARMS = new LinkedHashMap<>();

  static {
    final DeskSettings.StrategyPreset d = DeskSettings.StrategyPreset.DEFAULT;
    final DeskSettings.StrategyPreset v = DeskSettings.StrategyPreset.V2;
    final PricingBindings.Version p1 = PricingBindings.Version.V1;
    final PricingBindings.Version p2 = PricingBindings.Version.V2;
    ARMS.put("default", new Arm(d, UnaryOperator.identity(), Double.NaN, false, p1, Router.TOUCH, null));
    ARMS.put("engine", new Arm(d, UnaryOperator.identity(), Double.NaN, false, p1, Router.TOUCH, null));
    ARMS.put("v2", new Arm(v, UnaryOperator.identity(), Double.NaN, false, p1, Router.TOUCH, null));
    ARMS.put("cover_pricing", new Arm(d, s -> new StrategyConfig().coverPricing(true), Double.NaN, false, p1, Router.TOUCH, W1));
    ARMS.put("v2_without_reservation", new Arm(v, s -> s.reservationPrice(false), Double.NaN, false, p1, Router.TOUCH, W1));
    ARMS.put(
        "v2_without_factor",
        new Arm(v, s -> s.factorCovariance(false).factorHedging(false).tickHedgeCost(false), Double.NaN, false, p1, Router.TOUCH, W1));
    ARMS.put("v2_hedge_at_trigger", new Arm(v, UnaryOperator.identity(), Double.NaN, true, p1, Router.TOUCH, W1));
    ARMS.put("v2_without_markouts", new Arm(v, s -> s.markoutStore(false), Double.NaN, false, p1, Router.TOUCH, W1));
    ARMS.put(
        "v2_without_protection",
        new Arm(v, s -> s.tickRounding(false).volatilityTtl(false), 0.0, false, p1, Router.TOUCH, W1));
    ARMS.put("default_pricing_v2", new Arm(d, UnaryOperator.identity(), Double.NaN, false, p2, Router.TOUCH, W2));
    ARMS.put("v2_pricing_v2", new Arm(v, UnaryOperator.identity(), Double.NaN, false, p2, Router.TOUCH, W2));
    ARMS.put("default_router_ladder", new Arm(d, UnaryOperator.identity(), Double.NaN, false, p1, Router.LADDER, W3));
    ARMS.put("v2_router_ladder", new Arm(v, UnaryOperator.identity(), Double.NaN, false, p1, Router.LADDER, W3));
  }

  /** Every arm name. */
  public static List<String> names() {
    return List.copyOf(ARMS.keySet());
  }

  /** The arm with this name. */
  public static Arm arm(final String name) {
    final Arm a = ARMS.get(name);
    if (a == null) {
      throw new IllegalArgumentException("unknown desk arm '" + name + "'; known: " + names());
    }
    return a;
  }

  /** The settings a buildable arm runs with. */
  public static DeskSettings settings(final String name) {
    final Arm a = arm(name);
    if (a.wiring() != null) {
      throw new IllegalStateException("desk arm " + name + " " + a.wiring());
    }
    return a.base() == DeskSettings.StrategyPreset.V2 ? DeskSettings.strategyV2() : DeskSettings.defaults();
  }

  @Override
  public String refusal(final String name) {
    final Arm a = ARMS.get(name);
    if (a == null) {
      return "unknown desk arm '" + name + "'; known: " + names();
    }
    return a.wiring();
  }

  @Override
  public Strategy create(final String name, final SimDay day) {
    final UsdUniverse universe = UsdUniverse.standard();
    return new DeskStrategy(universe, settings(name), SimBinding.of(day.model().spec(), universe));
  }
}
