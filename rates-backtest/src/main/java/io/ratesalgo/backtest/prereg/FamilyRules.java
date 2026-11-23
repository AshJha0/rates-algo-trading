package io.ratesalgo.backtest.prereg;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rules of a registered family: one pre-registration whose config names several primary
 * comparisons on one data set, the data set's rows and the band points it leaves out
 * (research/prereg/R5-FAM-001.yaml). {@code ratesresearch.prereg} applies the same rules and
 * prints the same problems, in the same order.
 *
 * <pre>
 *   family:
 *     id: R5-FAM-001
 *     q: 0.05
 *     hypotheses: [{id: H1, treatment: v2, baseline: baseline, guards: {quoted_share: 0.8, ...}}, ...]
 *     reference_arm: baseline                       optional; the arm the description reads
 *     guards_vs_reference: {client_dv01: 0.25}      optional; needs reference_arm
 *     descriptive_arms: [legacy]                    optional; run and described, in no hypothesis
 *     structural_declines: not_above_baseline       optional
 *   rows:
 *     - {row: central, arms: [every arm, descriptive ones too], sessions: data_set.sessions}
 *     - {row: "all_in_cost:low", arms: [baseline, v2], sessions: 1000}
 *   excluded_band_points:
 *     - {row: "all_in_cost:high", reason: why it is not run}
 * </pre>
 *
 * <p>Every low and high point of the band's parameters is either a row or excluded with a reason,
 * so the cost of leaving one out is stated in the file. Whether an excluded point is really
 * refused by the simulator is checked by the runner, which can build it.
 */
public final class FamilyRules {

  private FamilyRules() {}

  /** True when a config declares a family or its rows. */
  public static boolean applies(final Map<String, Object> config) {
    return config.containsKey("family") || config.containsKey("rows") || config.containsKey("excluded_band_points");
  }

  /**
   * Problems with a family's config, appended to {@code problems}.
   *
   * @param raw the pre-registration
   * @param config its config
   * @param tested baseline then treatments; the family's descriptive arms are added to them
   */
  public static void validate(
      final Map<String, Object> raw, final Map<String, Object> config, final List<String> tested, final List<String> problems) {
    final List<String> arms = family(config.get("family"), config.get("arms"), tested, problems);
    final Object sessions = raw.get("data_set") instanceof Map<?, ?> ds ? ds.get("sessions") : null;
    final List<String> band = bandLabels(raw);
    final List<String> run = rows(config.get("rows"), arms, sessions, band, problems);
    final List<String> dropped = new ArrayList<>();
    final Object excludedRaw = config.get("excluded_band_points");
    final List<?> excluded;
    if (excludedRaw == null) {
      excluded = List.of();
    } else if (excludedRaw instanceof List<?> l) {
      excluded = l;
    } else {
      problems.add("config excluded_band_points must be a list of {row, reason}");
      excluded = List.of();
    }
    for (final Object e : excluded) {
      final String label = e instanceof Map<?, ?> m ? String.valueOf(m.get("row")) : String.valueOf(e);
      dropped.add(label);
      if (!band.contains(label)) {
        problems.add("config excluded_band_points: '" + label + "' is not a low or high row of the band's parameters");
      }
      if (run.contains(label)) {
        problems.add("config excluded_band_points: '" + label + "' is also run as a row");
      }
      if (!(e instanceof Map<?, ?> m) || blank(m.get("reason"))) {
        problems.add("config excluded_band_points '" + label + "' needs a reason");
      }
    }
    for (final String label : band) {
      if (!run.contains(label) && !dropped.contains(label)) {
        problems.add("assumption_band point '" + label
            + "' is neither run (config rows) nor excluded with a reason (config excluded_band_points)");
      }
    }
  }

  /** Checks the family mapping and returns every arm of the design, the descriptive ones last. */
  private static List<String> family(
      final Object raw, final Object configArms, final List<String> tested, final List<String> problems) {
    final List<String> arms = new ArrayList<>(tested);
    if (!(raw instanceof Map<?, ?> fam)) {
      problems.add("config family must be a mapping with id, q and hypotheses");
      return arms;
    }
    if (blank(fam.get("id"))) {
      problems.add("config family.id is missing");
    }
    if (!(fam.get("q") instanceof Number q) || !(q.doubleValue() > 0.0 && q.doubleValue() < 1.0)) {
      problems.add("config family.q must be a number between 0 and 1");
    }
    final List<String> descriptive = new ArrayList<>();
    if (fam.get("descriptive_arms") != null) {
      if (fam.get("descriptive_arms") instanceof List<?> d) {
        for (final Object o : d) {
          final String a = String.valueOf(o);
          if (tested.contains(a)) {
            problems.add("config family.descriptive_arms: '" + a + "' is also the baseline or a treatment");
          } else if (!(configArms instanceof Map<?, ?> named) || !named.containsKey(a)) {
            problems.add("config family.descriptive_arms: '" + a + "' is not named under config arms");
          }
          descriptive.add(a);
        }
      } else {
        problems.add("config family.descriptive_arms must be a list of arm names");
      }
    }
    for (final String a : descriptive) {
      if (!arms.contains(a)) {
        arms.add(a);
      }
    }
    final List<?> hyps;
    if (fam.get("hypotheses") instanceof List<?> l && !l.isEmpty()) {
      hyps = l;
    } else {
      problems.add("config family.hypotheses must be a non-empty list");
      hyps = List.of();
    }
    final Set<String> seen = new HashSet<>();
    for (int n = 0; n < hyps.size(); n++) {
      if (!(hyps.get(n) instanceof Map<?, ?> h) || blank(h.get("id"))) {
        problems.add("config family.hypotheses[" + n + "] needs an id");
        continue;
      }
      final String id = String.valueOf(h.get("id"));
      if (!seen.add(id)) {
        problems.add("config family: hypothesis id '" + id + "' is listed twice");
      }
      for (final String role : List.of("treatment", "baseline")) {
        final String a = String.valueOf(h.get(role));
        if (!arms.contains(a)) {
          problems.add("hypothesis " + id + ": " + role + " '" + a + "' is not an arm of the design");
        } else if (descriptive.contains(a)) {
          problems.add("hypothesis " + id + ": " + role + " '" + a + "' is a descriptive arm and enters no hypothesis");
        }
      }
      if (String.valueOf(h.get("treatment")).equals(String.valueOf(h.get("baseline")))) {
        problems.add("hypothesis " + id + ": treatment and baseline are the same arm");
      }
      guardMap(h.get("guards"), "hypothesis " + id + ": guards", problems);
    }
    if (fam.get("guards_vs_default") != null) {
      problems.add("config family.guards_vs_default is replaced by reference_arm and guards_vs_reference");
    }
    final Object reference = fam.get("reference_arm");
    if (reference != null) {
      if (!arms.contains(String.valueOf(reference))) {
        problems.add("config family.reference_arm '" + reference + "' is not an arm of the design");
      } else if (descriptive.contains(String.valueOf(reference))) {
        problems.add("config family.reference_arm '" + reference + "' is a descriptive arm");
      }
    }
    if (fam.get("guards_vs_reference") != null) {
      guardMap(fam.get("guards_vs_reference"), "config family.guards_vs_reference", problems);
      if (reference == null) {
        problems.add("config family.guards_vs_reference needs family.reference_arm");
      }
    }
    return arms;
  }

  private static List<String> rows(
      final Object raw, final List<String> arms, final Object sessions, final List<String> band,
      final List<String> problems) {
    final List<String> run = new ArrayList<>();
    if (!(raw instanceof List<?> rows) || rows.isEmpty()) {
      problems.add("config rows must be a non-empty list of {row, arms, sessions}");
      return run;
    }
    final Integer registered = sessions instanceof Number n && !(sessions instanceof Double) ? n.intValue() : null;
    for (int n = 0; n < rows.size(); n++) {
      if (!(rows.get(n) instanceof Map<?, ?> r)) {
        problems.add("config rows[" + n + "] must be a mapping of row, arms and sessions");
        continue;
      }
      final String label = String.valueOf(r.get("row"));
      final List<String> rowArms = new ArrayList<>();
      if (r.get("arms") instanceof List<?> a) {
        for (final Object o : a) {
          rowArms.add(String.valueOf(o));
        }
      }
      if (run.contains(label)) {
        problems.add("config rows: '" + label + "' is listed twice");
      }
      run.add(label);
      final Object s = r.get("sessions");
      if (n == 0) {
        if (!"central".equals(label)) {
          problems.add("config rows[0] must be the central row");
        } else if (!new HashSet<>(rowArms).equals(new HashSet<>(arms)) || rowArms.size() != arms.size()) {
          problems.add("config rows[0] (central) must run every arm of the design");
        }
        if (registered != null && !(s instanceof Number v && v.longValue() == registered)) {
          problems.add("config rows[0] sessions " + s + " differs from data_set.sessions " + registered);
        }
        continue;
      }
      if (!band.contains(label)) {
        problems.add("config rows: '" + label + "' is not a low or high row of the band's parameters");
      }
      if (rowArms.isEmpty()) {
        problems.add("config rows '" + label + "': arms must be a non-empty list");
      }
      for (final String a : rowArms) {
        if (!arms.contains(a)) {
          problems.add("config rows '" + label + "': arm '" + a + "' is not an arm of the design");
        }
      }
      final boolean ok =
          s instanceof Number v && !(s instanceof Double) && v.longValue() > 0
              && (registered == null || v.longValue() <= registered);
      if (!ok) {
        problems.add("config rows '" + label + "': sessions must be a positive integer no larger than data_set.sessions");
      }
    }
    return run;
  }

  private static void guardMap(final Object guards, final String where, final List<String> problems) {
    if (guards == null) {
      return;
    }
    if (!(guards instanceof Map<?, ?> g)) {
      problems.add(where + " must be a mapping of guard name to minimum ratio");
      return;
    }
    for (final Map.Entry<?, ?> e : g.entrySet()) {
      if (!SessionDesignRules.GUARDS.contains(String.valueOf(e.getKey()))) {
        problems.add(where + "." + e.getKey() + " is not a guard; known: " + SessionDesignRules.GUARDS);
      } else if (!(e.getValue() instanceof Number v) || !(v.doubleValue() > 0.0)) {
        problems.add(where + "." + e.getKey() + " must be a positive ratio");
      }
    }
  }

  /** The low and high row labels of the band's parameters, in file order. */
  static List<String> bandLabels(final Map<String, Object> raw) {
    final List<String> out = new ArrayList<>();
    if (raw.get("assumption_band") instanceof Map<?, ?> band && band.get("parameters") instanceof List<?> names) {
      for (final Object n : names) {
        out.add(n + ":low");
        out.add(n + ":high");
      }
    }
    return out;
  }

  private static boolean blank(final Object v) {
    return v == null || (v instanceof String s && s.isBlank());
  }
}
