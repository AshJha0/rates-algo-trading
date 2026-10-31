package io.ratesalgo.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code data/presets.yaml} (key {@code router}) and {@link RouterPresets} carry the same values,
 * and every value lies inside the range the file declares. Parsed with a line pattern so the
 * module needs no YAML library; the file's format for this key is pinned in its header comment.
 */
class RouterPresetsTest {

  private static final Pattern PARAM =
      Pattern.compile(
          "^\\s+(\\w+): \\{ value: ([-0-9.eE]+), unit: \"[^\"]*\", range: \\[([-0-9.eE]+),"
              + " ([-0-9.eE]+)\\] \\}\\s*$");
  private static final Pattern SECTION = Pattern.compile("^\\s+(global|clob|futures):\\s*$");

  static Map<String, Map<String, double[]>> load() throws IOException {
    Path file = Path.of("data", "presets.yaml");
    if (!Files.exists(file)) {
      file = Path.of("..", "data", "presets.yaml");
    }
    final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    final Map<String, Map<String, double[]>> out = new HashMap<>();
    boolean inRouter = false;
    String section = null;
    for (final String line : lines) {
      if (line.startsWith("router:")) {
        inRouter = true;
        continue;
      }
      if (!inRouter) {
        continue;
      }
      final Matcher s = SECTION.matcher(line);
      if (s.matches()) {
        section = s.group(1);
        continue;
      }
      final Matcher m = PARAM.matcher(line);
      if (m.matches() && section != null) {
        out.computeIfAbsent(section, k -> new HashMap<>())
            .put(
                m.group(1),
                new double[] {
                  Double.parseDouble(m.group(2)),
                  Double.parseDouble(m.group(3)),
                  Double.parseDouble(m.group(4))
                });
      }
    }
    return out;
  }

  private static void same(final Map<String, double[]> sec, final String key, final double v) {
    final double[] p = sec.get(key);
    assertTrue(p != null, "presets.yaml lacks " + key);
    assertEquals(p[0], v, 0.0, key);
    assertTrue(p[0] >= p[1] && p[0] <= p[2], key + " outside its declared range");
  }

  @Test
  void yamlAndJavaAgree() throws IOException {
    final Map<String, Map<String, double[]>> y = load();
    final RouterParams g = RouterPresets.global();
    final Map<String, double[]> gl = y.get("global");
    assertEquals(16, gl.size());
    same(gl, "sigma_daily_points", g.sigmaDailyPoints());
    same(gl, "trading_seconds_per_day", g.tradingSecondsPerDay());
    same(gl, "impact_y", g.impactY());
    same(gl, "impact_alpha", g.impactAlpha());
    same(gl, "adverse_factor", g.adverseFactor());
    same(gl, "latency_risk_factor", g.latencyRiskFactor());
    same(gl, "passive_horizon_max_nanos", (double) g.passiveHorizonMaxNanos());
    same(gl, "passive_horizon_min_nanos", (double) g.passiveHorizonMinNanos());
    same(gl, "max_slices", g.maxSlices());
    same(gl, "schedule_horizon_nanos", (double) g.scheduleHorizonNanos());
    same(gl, "size_jitter", g.sizeJitter());
    same(gl, "interval_jitter", g.intervalJitter());
    same(gl, "book_move_threshold_ticks", g.bookMoveThresholdTicks());
    same(gl, "aggressive_collar_ticks", g.aggressiveCollarTicks());
    same(gl, "retry_delay_nanos", (double) g.retryDelayNanos());
    same(gl, "max_steps", g.maxSteps());
    venue(y.get("clob"), RouterPresets.clob(1));
    venue(y.get("futures"), RouterPresets.futures(2));
  }

  private static void venue(final Map<String, double[]> v, final VenueParams p) {
    assertEquals(10, v.size());
    same(v, "tick_mantissa", (double) p.tickMantissa());
    same(v, "lot_size", (double) p.lotSize());
    same(v, "min_size", (double) p.minSize());
    same(v, "taker_fee", p.takerFee());
    same(v, "maker_fee", p.makerFee());
    same(v, "latency_nanos", (double) p.latencyNanos());
    same(v, "cancel_rate_per_second", p.cancelRatePerSecond());
    same(v, "adv", (double) p.adv());
    same(v, "participation_cap", p.participationCap());
    same(v, "depth_levels", p.depthLevels());
  }
}
