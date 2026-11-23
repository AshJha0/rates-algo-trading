package io.ratesalgo.backtest.prereg;

import io.ratesalgo.backtest.stats.Stats;
import io.ratesalgo.backtest.util.Yaml;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Benjamini-Hochberg across the primary tests of a registered family, computed from the ledger on
 * request and printed, never applied silently (docs/research/SIM_V2_DESIGN.md section 12).
 *
 * <p>A family is either every simulator v2 row that read one data set ({@code data_set:<key>}: all
 * primary tests read on one confirmation set, the design's definition), or the members listed in
 * a family file:
 *
 * <pre>
 *   family: R5
 *   q: 0.05
 *   members: [R5-001, R5-002, ...]
 * </pre>
 *
 * <p>Each member's latest row contributes one test per treatment: the central row's paired-t p.
 * The adjusted values are recomputed whenever the command runs, so a result is "supported" only
 * while its adjusted p stays below q as the family grows. Verdict per test, in this order:
 * {@code trading less} if a guard ratio is below the minimum the row recorded; {@code supported}
 * if the adjusted p is below q, the central bootstrap interval lies above zero and the mean
 * difference is positive at every band row; {@code reversed} if the adjusted p is below q and the
 * interval lies below zero; {@code not robust} if the sign of the mean difference changes across
 * the band rows; otherwise {@code inconclusive}. {@code ratesresearch.prereg} prints the same text.
 */
public final class FamilyAdjustment {

  /** A family: its name, q and the rows that belong to it. */
  public record Family(String name, double q, List<String> members, String dataSet) {
    public Family {
      members = List.copyOf(members);
    }

    /** Parses {@code data_set:<key>} or a family file. */
    public static Family of(final String spec) {
      if (spec.startsWith("data_set:")) {
        final String key = spec.substring("data_set:".length());
        return new Family(spec, 0.05, List.of(), key);
      }
      final Path file = Path.of(spec);
      if (!Files.isRegularFile(file)) {
        throw new IllegalArgumentException("family file not found: " + spec);
      }
      final Map<String, Object> m = Yaml.loadMap(file);
      final List<String> members = new ArrayList<>();
      for (final Object o : Yaml.asList(m.get("members"), "members")) {
        members.add(String.valueOf(o));
      }
      final double q = m.get("q") instanceof Number n ? n.doubleValue() : 0.05;
      return new Family(String.valueOf(m.get("family")), q, members, null);
    }

    boolean contains(final Map<String, Object> row) {
      return dataSet != null ? dataSet.equals(row.get("data_set")) : members.contains(String.valueOf(row.get("prereg_id")));
    }
  }

  /** One primary test of the family. */
  record Test(String preregId, String comparison, long seq, double p, Map<String, Object> central, Map<String, Object> row) {}

  private FamilyAdjustment() {}

  /** The family's tests with their adjusted p and verdicts, as text. */
  public static String report(final List<Map<String, Object>> entries, final Family family) {
    final Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
    for (final Map<String, Object> e : entries) {
      if (family.contains(e) && e.get("results") instanceof Map<?, ?> r && r.get("primary") instanceof Map<?, ?>) {
        latest.put(String.valueOf(e.get("prereg_id")), e);
      }
    }
    final List<Test> tests = new ArrayList<>();
    for (final Map<String, Object> row : latest.values()) {
      final Map<String, Object> primary = Yaml.asMap(Yaml.asMap(row.get("results"), "results").get("primary"), "primary");
      for (final Map.Entry<String, Object> t : primary.entrySet()) {
        final Map<String, Object> c = Yaml.asMap(t.getValue(), "comparison");
        final double p = c.get("paired_p") instanceof Number n ? n.doubleValue() : 1.0;
        tests.add(
            new Test(
                String.valueOf(row.get("prereg_id")), t.getKey(), ((Number) row.get("seq")).longValue(), p, c, row));
      }
    }
    final StringBuilder b = new StringBuilder(1024);
    b.append("family ").append(family.name()).append(" (q = ").append(fmt(family.q(), 2)).append("): ")
        .append(tests.size()).append(tests.size() == 1 ? " test" : " tests").append(" from ").append(latest.size())
        .append(latest.size() == 1 ? " ledger row\n" : " ledger rows\n");
    if (tests.isEmpty()) {
      return b.toString();
    }
    final double[] raw = new double[tests.size()];
    for (int i = 0; i < raw.length; i++) {
      raw[i] = tests.get(i).p();
    }
    final double[] adjusted = Stats.benjaminiHochberg(raw);
    for (int i = 0; i < raw.length; i++) {
      final Test t = tests.get(i);
      final int[] signs = signs(t);
      b.append("  ").append(t.preregId()).append(' ').append(t.comparison()).append("_vs_baseline  seq ").append(t.seq())
          .append("  p ").append(fmt(raw[i], 8)).append("  bh ").append(fmt(adjusted[i], 8)).append("  ci [")
          .append(fmt(number(t.central().get("ci_low")), 2)).append(", ").append(fmt(number(t.central().get("ci_high")), 2))
          .append("]  signs +").append(signs[0]).append("/-").append(signs[1]).append("  guards ")
          .append(guards(t)).append("  verdict ").append(verdict(t, adjusted[i], family.q(), signs)).append('\n');
    }
    return b.toString();
  }

  private static int[] signs(final Test t) {
    final int[] s = new int[2];
    final Object bySign = Yaml.asMap(t.row().get("results"), "results").get("signs_by_band");
    if (bySign instanceof Map<?, ?> m && m.get(t.comparison()) instanceof Map<?, ?> rows) {
      for (final Object v : rows.values()) {
        final long sign = ((Number) v).longValue();
        if (sign > 0) {
          s[0]++;
        } else if (sign < 0) {
          s[1]++;
        }
      }
    }
    return s;
  }

  /** "ok", "fail" or "unchecked" (no minimums recorded in the row). */
  private static String guards(final Test t) {
    if (!(t.row().get("guards_min") instanceof Map<?, ?> min) || min.isEmpty()) {
      return "unchecked";
    }
    final Map<String, Object> ratios = Yaml.asMap(t.central().get("guards"), "guards");
    for (final Map.Entry<?, ?> e : min.entrySet()) {
      final Object r = ratios.get(e.getKey() + "_ratio");
      if (!(r instanceof Number n) || n.doubleValue() < ((Number) e.getValue()).doubleValue()) {
        return "fail";
      }
    }
    return "ok";
  }

  private static String verdict(final Test t, final double adjusted, final double q, final int[] signs) {
    if ("fail".equals(guards(t))) {
      return "trading less";
    }
    final double low = number(t.central().get("ci_low"));
    final double high = number(t.central().get("ci_high"));
    if (adjusted < q && low > 0.0 && signs[1] == 0 && signs[0] > 0) {
      return "unchecked".equals(guards(t)) ? "supported if the guards hold" : "supported";
    }
    if (adjusted < q && high < 0.0) {
      return "reversed";
    }
    if (signs[0] > 0 && signs[1] > 0) {
      return "not robust";
    }
    return "inconclusive";
  }

  private static double number(final Object o) {
    return o instanceof Number n ? n.doubleValue() : Double.NaN;
  }

  private static String fmt(final double v, final int digits) {
    return String.format(Locale.ROOT, "%." + digits + "f", v);
  }
}
