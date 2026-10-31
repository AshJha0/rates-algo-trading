package io.ratesalgo.quoting;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The hit-rate coefficients and their assumption band, read from {@code data/presets.yaml} (schema
 * in API_SPEC.md Section 12.3). The reader understands the subset of YAML that file uses: a
 * {@code presets:} list whose items start with {@code - name:}, and under each a {@code
 * parameters:} mapping whose entries are one-line flow mappings such as
 *
 * <pre>
 *   spread_bp: { value: -1.6, standard_error: null, band_low: -2.4, band_high: -1.0 }
 * </pre>
 *
 * When the file has no preset with the requested name, {@link #load} falls back to the default
 * shipped in this module ({@code quoting-hit-rate-default.yaml}), which is a declared assumption
 * (DR-008).
 */
public final class HitRatePreset {

  /** Name of the preset the quoting engine reads. */
  public static final String DEFAULT_NAME = "quoting_hit_rate_default";

  private static final String[] KEYS = {
    "intercept", "spread_bp", "log_size", "tenor_years", "liquid_session", "dealers_minus_one"
  };

  private final String name;
  private final String fittedFrom;
  private final double[] value = new double[HitRateModel.COUNT];
  private final double[] low = new double[HitRateModel.COUNT];
  private final double[] high = new double[HitRateModel.COUNT];

  private HitRatePreset(final String name, final String fittedFrom) {
    this.name = name;
    this.fittedFrom = fittedFrom;
  }

  /** The preset named {@code name} in {@code presetsYaml}, or the shipped default. */
  public static HitRatePreset load(final Path presetsYaml, final String name) throws IOException {
    if (presetsYaml != null && Files.isRegularFile(presetsYaml)) {
      try (Reader r = Files.newBufferedReader(presetsYaml, StandardCharsets.UTF_8)) {
        final HitRatePreset p = parse(r, name);
        if (p != null) {
          return p;
        }
      }
    }
    return shippedDefault();
  }

  /** The default preset shipped in this module. */
  public static HitRatePreset shippedDefault() throws IOException {
    try (InputStream in = HitRatePreset.class.getResourceAsStream("/quoting-hit-rate-default.yaml")) {
      if (in == null) {
        throw new IOException("missing resource quoting-hit-rate-default.yaml");
      }
      final HitRatePreset p = parse(new InputStreamReader(in, StandardCharsets.UTF_8), DEFAULT_NAME);
      if (p == null) {
        throw new IOException("shipped default has no preset " + DEFAULT_NAME);
      }
      return p;
    }
  }

  /** Parses the named preset, or returns null if it is absent. */
  static HitRatePreset parse(final Reader reader, final String wanted) throws IOException {
    final BufferedReader in = new BufferedReader(reader);
    final List<String> lines = new ArrayList<>();
    for (String line = in.readLine(); line != null; line = in.readLine()) {
      final int hash = line.indexOf('#');
      final String body = hash >= 0 ? line.substring(0, hash) : line;
      if (!body.isBlank()) {
        lines.add(body);
      }
    }
    for (int i = 0; i < lines.size(); i++) {
      final String t = lines.get(i).trim();
      if (!t.startsWith("- name:") || !unquote(t.substring(7)).equals(wanted)) {
        continue;
      }
      String fitted = "";
      final HitRatePreset p = new HitRatePreset(wanted, "");
      final boolean[] seen = new boolean[HitRateModel.COUNT];
      boolean inParameters = false;
      for (int j = i + 1; j < lines.size() && !lines.get(j).trim().startsWith("- name:"); j++) {
        final String s = lines.get(j).trim();
        if (s.startsWith("fitted_from:")) {
          fitted = unquote(s.substring(12));
        } else if (s.startsWith("parameters:")) {
          inParameters = true;
        } else if (inParameters && s.contains("{")) {
          final String key = s.substring(0, s.indexOf(':')).trim();
          final int k = indexOf(key);
          if (k >= 0) {
            p.value[k] = number(s, "value", key);
            p.low[k] = number(s, "band_low", key);
            p.high[k] = number(s, "band_high", key);
            if (!(p.low[k] <= p.value[k] && p.value[k] <= p.high[k])) {
              throw new IOException("preset " + wanted + ": " + key + " value outside its band");
            }
            seen[k] = true;
          }
        } else if (!s.contains("{") && s.endsWith(":")) {
          inParameters = false;
        }
      }
      for (int k = 0; k < HitRateModel.COUNT; k++) {
        if (!seen[k]) {
          throw new IOException("preset " + wanted + " lacks parameter " + KEYS[k]);
        }
      }
      final HitRatePreset out = new HitRatePreset(wanted, fitted);
      System.arraycopy(p.value, 0, out.value, 0, HitRateModel.COUNT);
      System.arraycopy(p.low, 0, out.low, 0, HitRateModel.COUNT);
      System.arraycopy(p.high, 0, out.high, 0, HitRateModel.COUNT);
      return out;
    }
    return null;
  }

  private static int indexOf(final String key) {
    for (int k = 0; k < KEYS.length; k++) {
      if (KEYS[k].equals(key)) {
        return k;
      }
    }
    return -1;
  }

  private static double number(final String line, final String field, final String key)
      throws IOException {
    final String flow = line.substring(line.indexOf('{') + 1, line.lastIndexOf('}'));
    for (final String part : flow.split(",", -1)) {
      final int colon = part.indexOf(':');
      if (colon > 0 && part.substring(0, colon).trim().equals(field)) {
        final String v = part.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
        try {
          return Double.parseDouble(v);
        } catch (final NumberFormatException e) {
          throw new IOException("parameter " + key + " field " + field + " is not a number: " + v, e);
        }
      }
    }
    throw new IOException("parameter " + key + " lacks field " + field);
  }

  private static String unquote(final String s) {
    final String t = s.trim();
    if (t.length() >= 2 && (t.charAt(0) == '"' || t.charAt(0) == '\'')) {
      return t.substring(1, t.length() - 1);
    }
    return t;
  }

  public String name() {
    return name;
  }

  /** The preset's {@code fitted_from}: {@code assumption} for the shipped default. */
  public String fittedFrom() {
    return fittedFrom;
  }

  /** Central value of coefficient {@code i}. */
  public double value(final int i) {
    return value[i];
  }

  /** Lower end of the band of coefficient {@code i}. */
  public double low(final int i) {
    return low[i];
  }

  /** Upper end of the band of coefficient {@code i}. */
  public double high(final int i) {
    return high[i];
  }

  /** YAML key of coefficient {@code i}. */
  public static String key(final int i) {
    return KEYS[i];
  }
}
