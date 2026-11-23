package io.ratesalgo.backtest.experiment;

import io.ratesalgo.sim.SimParams;
import io.ratesalgo.sim.SimParams.Scalar;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The band points of the parameters the calibration could only bound or had to assume
 * (docs/research/SIM_V2_DESIGN.md section 12, "Sensitivities that every headline must carry").
 *
 * <p>A pre-registration names parameters by the short names below under {@code
 * assumption_band.parameters}; each name has a low and a high point, and the central point is the
 * committed parameter file. A run evaluates the central row and one row per (parameter, point),
 * every row on the same seeds, so a sensitivity is part of the same look. The values are the
 * design's, transcribed once here; {@code ratesresearch.prereg.BAND_PARAMETERS} lists the same
 * names.
 *
 * <ul>
 *   <li>{@code winner_half_spread}: competitor median half-spread 0.1228 and 0.2719 bp, the adverse
 *       move solved again for zero competitor profit at the central all-in cost.
 *   <li>{@code all_in_cost}: 0.05 and 0.15 bp, adverse move solved again at the central median.
 *   <li>{@code informed_fraction}: 0.2 and 1.0 at a fixed average adverse move.
 *   <li>{@code drift_tau}: information drift time constant 2 s and 60 s.
 *   <li>{@code level_vol}: every curve factor's sd scaled by 0.76 and 1.29.
 *   <li>{@code spread_factor}: swap-spread factor sd scaled by 0.3 / 0.7 and 1.3 / 0.7 (daily sd
 *       0.3 and 1.3 bp against the central 0.7; per sqrt-minute 0.0072 and 0.0312 from 0.0168).
 *   <li>{@code impact}: impact coefficient Y 0.3 and 1.5.
 *   <li>{@code basis}: futures basis stationary sd 0.05 and 0.4 bp.
 *   <li>{@code taker_skill}: other takers' sign skill 0.0 and 0.2.
 *   <li>{@code impact_half_life}: 5 s and 120 s.
 * </ul>
 *
 * <p>A row whose parameters the simulator's start-up checks refuse (the upper winner band at
 * informed fraction 0.5, design section 14 D14) fails when it is built, before any session runs.
 */
public final class BandParameters {

  /** One evaluated row: {@code central} or {@code <parameter>:low|high}, and its parameters. */
  public record Row(String label, SimParams params) {}

  private record Points(UnaryOperator<SimParams> low, UnaryOperator<SimParams> high) {}

  private static final Map<String, Points> TABLE = new LinkedHashMap<>();

  static {
    TABLE.put(
        "winner_half_spread",
        new Points(
            p -> p.withZeroProfit(0.1228, p.get(Scalar.ALL_IN_COST_BP)),
            p -> p.withZeroProfit(0.2719, p.get(Scalar.ALL_IN_COST_BP))));
    TABLE.put(
        "all_in_cost",
        new Points(
            p -> p.withZeroProfit(p.get(Scalar.MEDIAN_HALF_SPREAD_BP), 0.05),
            p -> p.withZeroProfit(p.get(Scalar.MEDIAN_HALF_SPREAD_BP), 0.15)));
    TABLE.put("informed_fraction", new Points(p -> p.withInformedFraction(0.2), p -> p.withInformedFraction(1.0)));
    TABLE.put(
        "drift_tau",
        new Points(p -> p.with(Scalar.DRIFT_TIME_CONSTANT_S, 2.0), p -> p.with(Scalar.DRIFT_TIME_CONSTANT_S, 60.0)));
    TABLE.put("level_vol", new Points(p -> p.withFactorSdScale(0.76), p -> p.withFactorSdScale(1.29)));
    TABLE.put(
        "spread_factor",
        new Points(
            p -> p.with(Scalar.SPREAD_SD_PER_SQRT_MINUTE, p.get(Scalar.SPREAD_SD_PER_SQRT_MINUTE) * 0.3 / 0.7),
            p -> p.with(Scalar.SPREAD_SD_PER_SQRT_MINUTE, p.get(Scalar.SPREAD_SD_PER_SQRT_MINUTE) * 1.3 / 0.7)));
    TABLE.put("impact", new Points(p -> p.with(Scalar.IMPACT_Y, 0.3), p -> p.with(Scalar.IMPACT_Y, 1.5)));
    TABLE.put(
        "basis",
        new Points(p -> p.with(Scalar.BASIS_STATIONARY_SD_BP, 0.05), p -> p.with(Scalar.BASIS_STATIONARY_SD_BP, 0.4)));
    TABLE.put(
        "taker_skill", new Points(p -> p.with(Scalar.TAKER_SIGN_SKILL, 0.0), p -> p.with(Scalar.TAKER_SIGN_SKILL, 0.2)));
    TABLE.put(
        "impact_half_life",
        new Points(p -> p.with(Scalar.IMPACT_HALF_LIFE_S, 5.0), p -> p.with(Scalar.IMPACT_HALF_LIFE_S, 120.0)));
  }

  /** Label of the central row. */
  public static final String CENTRAL = "central";

  private BandParameters() {}

  /** Every known parameter name, in table order. */
  public static List<String> names() {
    return List.copyOf(TABLE.keySet());
  }

  /** True if {@code name} is a known band parameter. */
  public static boolean known(final String name) {
    return TABLE.containsKey(name);
  }

  /**
   * The rows to evaluate: central first, then low and high of each parameter in the order given.
   *
   * @throws IllegalArgumentException for an unknown name, or when the simulator refuses a row
   */
  public static List<Row> rows(final SimParams central, final List<String> parameters) {
    final List<Row> rows = new ArrayList<>();
    rows.add(new Row(CENTRAL, central));
    for (final String name : parameters) {
      final Points points = TABLE.get(name);
      if (points == null) {
        throw new IllegalArgumentException("unknown band parameter '" + name + "'; known: " + names());
      }
      rows.add(new Row(name + ":low", build(name, "low", points.low(), central)));
      rows.add(new Row(name + ":high", build(name, "high", points.high(), central)));
    }
    return rows;
  }

  private static SimParams build(
      final String name, final String point, final UnaryOperator<SimParams> f, final SimParams central) {
    try {
      final SimParams p = f.apply(central);
      // the start-up checks run when the market specification is built
      new io.ratesalgo.sim.MarketSpec(p);
      return p;
    } catch (final IllegalArgumentException | IllegalStateException e) {
      throw new IllegalArgumentException(
          "band row " + name + ":" + point + " is refused by the simulator's start-up checks: " + e.getMessage(), e);
    }
  }

  /** The row with this label from a central parameter set. */
  public static Row row(final SimParams central, final String label) {
    if (CENTRAL.equals(label)) {
      return new Row(CENTRAL, central);
    }
    final int colon = label.indexOf(':');
    if (colon < 0) {
      throw new IllegalArgumentException("not a band row label: " + label);
    }
    final String name = label.substring(0, colon);
    final String point = label.substring(colon + 1);
    final Points points = TABLE.get(name);
    if (points == null || !("low".equals(point) || "high".equals(point))) {
      throw new IllegalArgumentException("not a band row label: " + label);
    }
    return new Row(label, build(name, point, "low".equals(point) ? points.low() : points.high(), central));
  }
}
