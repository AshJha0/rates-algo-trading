package io.ratesalgo.backtest.model;

import io.ratesalgo.backtest.util.Yaml;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads {@code data/presets.yaml}. The file is written by the Python research package
 * ({@code ratesresearch.presets}) and read here and by the quoting engine (P3), so its schema is a
 * contract (API_SPEC.md Section 16.2): every preset has {@code name}, {@code fitted_from},
 * {@code data_range}, {@code sample_size}, {@code method} and {@code parameters} (each with
 * {@code value} and {@code standard_error}); presets carrying declared assumptions add
 * {@code assumptions}, each with a {@code band} of {@code low}, {@code central} and {@code high}.
 */
public final class Presets {

  private final Map<String, Preset> byName;

  private Presets(final Map<String, Preset> byName) {
    this.byName = Collections.unmodifiableMap(byName);
  }

  public static Presets load(final Path path) {
    return parse(Yaml.loadMap(path), path.toString());
  }

  public static Presets parse(final Map<String, Object> root, final String where) {
    final List<Object> list = Yaml.asList(Yaml.require(root, "presets", where), where + ".presets");
    final TreeMap<String, Preset> map = new TreeMap<>();
    for (int i = 0; i < list.size(); i++) {
      final String at = where + ".presets[" + i + "]";
      final Preset p = parsePreset(Yaml.asMap(list.get(i), at), at);
      if (map.put(p.name(), p) != null) {
        throw new IllegalArgumentException(where + ": duplicate preset " + p.name());
      }
    }
    return new Presets(map);
  }

  private static Preset parsePreset(final Map<String, Object> m, final String at) {
    final String name = Yaml.string(m, "name", at);
    final String where = at + "(" + name + ")";
    final String fittedFrom = Yaml.string(m, "fitted_from", where);
    final Map<String, Object> range = Yaml.map(m, "data_range", where);
    Yaml.string(range, "start", where + ".data_range");
    Yaml.string(range, "end", where + ".data_range");
    final long sampleSize = Yaml.integer(m, "sample_size", where);
    final String method = Yaml.string(m, "method", where);
    final Map<String, Object> params = Yaml.map(m, "parameters", where);
    final TreeMap<String, Preset.Parameter> parameters = new TreeMap<>();
    for (final Map.Entry<String, Object> e : params.entrySet()) {
      final String pw = where + ".parameters." + e.getKey();
      final Map<String, Object> pm = Yaml.asMap(e.getValue(), pw);
      final double value = Yaml.number(pm, "value", pw);
      if (!pm.containsKey("standard_error")) {
        throw new IllegalArgumentException(pw + ": missing key 'standard_error' (null is allowed)");
      }
      final Object se = pm.get("standard_error");
      parameters.put(
          e.getKey(), new Preset.Parameter(value, se == null ? Double.NaN : Yaml.toDouble(se, pw)));
    }
    final TreeMap<String, Preset.Band> assumptions = new TreeMap<>();
    final Object rawAssumptions = m.get("assumptions");
    if (rawAssumptions != null) {
      for (final Map.Entry<String, Object> e : Yaml.asMap(rawAssumptions, where).entrySet()) {
        final String aw = where + ".assumptions." + e.getKey();
        final Map<String, Object> am = Yaml.asMap(e.getValue(), aw);
        final Map<String, Object> band = Yaml.map(am, "band", aw);
        final Object rationale = am.get("rationale");
        assumptions.put(
            e.getKey(),
            new Preset.Band(
                Yaml.number(band, "low", aw),
                Yaml.number(band, "central", aw),
                Yaml.number(band, "high", aw),
                rationale == null ? "" : rationale.toString()));
      }
    }
    return new Preset(name, fittedFrom, method, sampleSize, parameters, assumptions);
  }

  public Preset get(final String name) {
    final Preset p = byName.get(name);
    if (p == null) {
      throw new IllegalArgumentException("no preset named " + name + " in data/presets.yaml");
    }
    return p;
  }

  public boolean contains(final String name) {
    return byName.containsKey(name);
  }

  public List<String> names() {
    return List.copyOf(byName.keySet());
  }
}
