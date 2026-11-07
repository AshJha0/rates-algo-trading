package io.ratesalgo.strategy.experiment;

import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.util.Yaml;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One point of the desk experiments' declared assumption band (DR-008, DR-R2B-03): the
 * win-probability slope at the band point (preset {@code usd_rfq_hit_rate}, applied by the
 * back-tester through {@link BandPoint}), and the dealer count range and informed-flow share of
 * the synthetic market, which change the journals themselves.
 *
 * <pre>
 *   dealers_min, dealers_max          replaced by the band value at the point
 *   tier_k_informed_share             multiplied by informed_share_multiplier at the point, capped at 1
 * </pre>
 */
public final class BandScenario {

  private static final String MARKET = "usd_synthetic_market";

  private BandScenario() {}

  /** The presets of {@code presetsFile} with the market parameters of {@code point} applied. */
  public static Presets presets(final Path presetsFile, final Map<String, Object> band, final BandPoint point) {
    return presets(Yaml.loadMap(presetsFile), presetsFile.toString(), band, point);
  }

  /** As {@link #presets(Path, Map, BandPoint)}, on an already parsed (and possibly edited) file. */
  public static Presets presets(
      final Map<String, Object> root, final String where, final Map<String, Object> band, final BandPoint point) {
    final List<Object> list = Yaml.asList(Yaml.require(root, "presets", where), "presets");
    for (final Object o : list) {
      final Map<String, Object> p = Yaml.asMap(o, "preset");
      if (!MARKET.equals(p.get("name"))) {
        continue;
      }
      final Map<String, Object> params = Yaml.map(p, "parameters", MARKET);
      set(params, "dealers_min", at(band, "dealers_min", point));
      set(params, "dealers_max", at(band, "dealers_max", point));
      final double mult = at(band, "informed_share_multiplier", point);
      for (int k = 1; k <= 3; k++) {
        final String key = "tier_" + k + "_informed_share";
        final double v = Yaml.number(Yaml.map(params, key, MARKET), "value", key);
        set(params, key, Math.min(1.0, v * mult));
      }
    }
    return Presets.parse(root, where + " at band point " + point.label());
  }

  /** The value of band parameter {@code name} at {@code point}. */
  public static double at(final Map<String, Object> band, final String name, final BandPoint point) {
    return Yaml.number(Yaml.map(band, name, "band"), point.label(), "band." + name);
  }

  private static void set(final Map<String, Object> params, final String key, final double value) {
    Yaml.map(params, key, MARKET).put("value", value);
  }
}
