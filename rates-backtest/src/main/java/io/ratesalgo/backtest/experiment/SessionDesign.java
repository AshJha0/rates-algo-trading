package io.ratesalgo.backtest.experiment;

import io.ratesalgo.backtest.prereg.PreRegistration;
import io.ratesalgo.backtest.prereg.PreRegistrationException;
import io.ratesalgo.backtest.util.Yaml;
import io.ratesalgo.sim.SessionSeeds;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a version 2 pre-registration and its configuration fix for a run: the sessions, the arms,
 * the band parameters and the statistics. Built only from a file that has passed {@link
 * io.ratesalgo.backtest.prereg.SessionDesignRules}.
 *
 * @param id pre-registration id
 * @param seedBase first seed of the data set's base
 * @param first first session number
 * @param sessions registered session count; -1 while a draft gives only the rule
 * @param baseline baseline arm
 * @param treatments treatment arms, empty for a description
 * @param bandParameters band parameter names, in the file's order
 * @param blockSessions block length of the moving-block bootstrap, sessions
 * @param resamples bootstrap resamples
 * @param bootstrapSeed bootstrap seed
 * @param smallestEffectUsd the smallest effect of interest in USD per session; NaN for a description
 * @param pilotBase the pilot's seed base
 * @param draft true while the file is a draft
 * @param guardMinimums treatment-over-baseline minimums of the guard ratios, by guard name
 *     ({@code quoted_share}, {@code hit_rate}, {@code client_dv01}); empty when the config states none
 * @param plan what is run on which sessions and what is tested: the data-set rows and, for a
 *     registered family, its hypotheses
 */
public record SessionDesign(
    String id,
    long seedBase,
    int first,
    int sessions,
    String baseline,
    List<String> treatments,
    List<String> bandParameters,
    int blockSessions,
    int resamples,
    long bootstrapSeed,
    double smallestEffectUsd,
    long pilotBase,
    boolean draft,
    Map<String, Double> guardMinimums,
    Plan plan) {

  /**
   * One data-set row: a band row run for some arms on the first {@code sessions} sessions of the
   * data set.
   */
  public record RowPlan(String label, List<String> arms, int sessions) {
    public RowPlan {
      arms = List.copyOf(arms);
    }

    /** True when session number {@code n} (from the data set's first) is in this row. */
    public boolean includes(final int n) {
      return sessions < 0 || n < sessions;
    }
  }

  /** One primary comparison of a family: treatment against baseline, with its guard minimums. */
  public record Hypothesis(String id, String treatment, String baseline, Map<String, Double> guards) {
    public Hypothesis {
      guards = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(guards));
    }

    /** The comparison's name in results: {@code <treatment>_vs_<baseline>}. */
    public String comparison() {
      return treatment + "_vs_" + baseline;
    }
  }

  /**
   * What a run evaluates. A single pre-registration runs every arm on every band row and session
   * and tests each treatment against the baseline; a registered family (config {@code family},
   * {@code rows}, {@code excluded_band_points}) runs each row's own arms on its own sessions and
   * tests its hypotheses, the band rows being description only.
   *
   * @param rows the data-set rows, central first
   * @param hypotheses the family's hypotheses; empty for a single pre-registration
   * @param familyId the family's id, or null
   * @param q the family's false-discovery level
   * @param guardsVsDefault minimums of treatment over the {@code default} arm, by guard name
   * @param structuralGuard true when a treatment may decline no more RFQs for a structural reason
   *     than its baseline
   * @param excluded band rows left out, with the reason
   * @param supersededBy what replaced the file before signing, or null
   */
  public record Plan(
      List<RowPlan> rows,
      List<Hypothesis> hypotheses,
      String familyId,
      double q,
      Map<String, Double> guardsVsDefault,
      boolean structuralGuard,
      Map<String, String> excluded,
      String supersededBy) {
    public Plan {
      rows = List.copyOf(rows);
      hypotheses = List.copyOf(hypotheses);
      guardsVsDefault = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(guardsVsDefault));
      excluded = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(excluded));
    }

    /** True for a registered family. */
    public boolean family() {
      return familyId != null;
    }

    /** Arm-sessions the plan runs: the sum over rows of arms times sessions. */
    public long armSessions() {
      long n = 0;
      for (final RowPlan r : rows) {
        n += (long) r.arms().size() * Math.max(0, r.sessions());
      }
      return n;
    }

    /** Session journals the plan writes: one per row and session. */
    public long journals() {
      long n = 0;
      for (final RowPlan r : rows) {
        n += Math.max(0, r.sessions());
      }
      return n;
    }
  }

  public SessionDesign {
    treatments = List.copyOf(treatments);
    bandParameters = List.copyOf(bandParameters);
    guardMinimums = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(guardMinimums));
    java.util.Objects.requireNonNull(plan, "plan");
  }

  /** A single pre-registration's design: every arm on every band row and session. */
  public SessionDesign(
      final String id, final long seedBase, final int first, final int sessions, final String baseline,
      final List<String> treatments, final List<String> bandParameters, final int blockSessions, final int resamples,
      final long bootstrapSeed, final double smallestEffectUsd, final long pilotBase, final boolean draft,
      final Map<String, Double> guardMinimums) {
    this(id, seedBase, first, sessions, baseline, treatments, bandParameters, blockSessions, resamples, bootstrapSeed,
        smallestEffectUsd, pilotBase, draft, guardMinimums, standardPlan(baseline, treatments, bandParameters, sessions, null));
  }

  /** Every arm on the central row and on both points of every band parameter, all sessions. */
  static Plan standardPlan(
      final String baseline, final List<String> treatments, final List<String> bandParameters, final int sessions,
      final String supersededBy) {
    final List<String> arms = new ArrayList<>();
    arms.add(baseline);
    arms.addAll(treatments);
    final List<RowPlan> rows = new ArrayList<>();
    rows.add(new RowPlan(BandParameters.CENTRAL, arms, sessions));
    for (final String p : bandParameters) {
      rows.add(new RowPlan(p + ":low", arms, sessions));
      rows.add(new RowPlan(p + ":high", arms, sessions));
    }
    return new Plan(rows, List.of(), null, 0.05, Map.of(), false, Map.of(), supersededBy);
  }

  /** Reads the design of a valid version 2 pre-registration. */
  public static SessionDesign of(final PreRegistration prereg, final Path repoRoot) {
    final List<String> problems = prereg.validate(repoRoot);
    if (!problems.isEmpty()) {
      throw new PreRegistrationException(problems);
    }
    if (prereg.version() != 2) {
      throw new PreRegistrationException(List.of(prereg.id() + " is not a session-seed design (prereg_version 2)"));
    }
    final Map<String, Object> raw = prereg.raw();
    final Map<String, Object> dataSet = Yaml.asMap(raw.get("data_set"), "data_set");
    final Map<String, Object> config = Yaml.loadMap(repoRoot.resolve(prereg.config()));
    final Map<String, Object> st = Yaml.map(config, "statistics", "config");
    final Map<String, Object> seeds = Yaml.map(config, "seeds", "config");
    final List<String> band = new ArrayList<>();
    for (final Object o : Yaml.asList(Yaml.asMap(raw.get("assumption_band"), "assumption_band").get("parameters"), "parameters")) {
      band.add(String.valueOf(o));
    }
    final double effect =
        config.get("smallest_effect") instanceof Map<?, ?> e && e.get("usd_per_session") instanceof Number n
            ? n.doubleValue()
            : Double.NaN;
    final Map<String, Double> guards = new java.util.LinkedHashMap<>();
    if (config.get("guards") instanceof Map<?, ?> g) {
      for (final Map.Entry<?, ?> e : g.entrySet()) {
        guards.put(String.valueOf(e.getKey()), ((Number) e.getValue()).doubleValue());
      }
    }
    final int sessions = dataSet.get("sessions") instanceof Number s ? s.intValue() : -1;
    final String superseded = raw.get("superseded_by") == null ? null : String.valueOf(raw.get("superseded_by"));
    final Plan plan =
        config.containsKey("family") || config.containsKey("rows")
            ? familyPlan(config, superseded)
            : standardPlan(prereg.baseline(), prereg.treatments(), band, sessions, superseded);
    return new SessionDesign(
        prereg.id(),
        ((Number) dataSet.get("seed_base")).longValue(),
        dataSet.get("first") instanceof Number f ? f.intValue() : 0,
        sessions,
        prereg.baseline(),
        prereg.treatments(),
        band,
        (int) Yaml.integer(st, "block_sessions", "statistics"),
        (int) Yaml.integer(st, "resamples", "statistics"),
        Yaml.integer(st, "bootstrap_seed", "statistics"),
        effect,
        Yaml.integer(seeds, "pilot_base", "seeds"),
        prereg.isDraft(),
        guards,
        plan);
  }

  /** A registered family's plan, from a config that has passed {@code FamilyRules}. */
  private static Plan familyPlan(final Map<String, Object> config, final String superseded) {
    final Map<String, Object> family = Yaml.asMap(config.get("family"), "family");
    final List<Hypothesis> hypotheses = new ArrayList<>();
    for (final Object o : Yaml.asList(family.get("hypotheses"), "family.hypotheses")) {
      final Map<String, Object> h = Yaml.asMap(o, "hypothesis");
      hypotheses.add(
          new Hypothesis(
              String.valueOf(h.get("id")), String.valueOf(h.get("treatment")), String.valueOf(h.get("baseline")),
              numbers(h.get("guards"))));
    }
    final List<RowPlan> rows = new ArrayList<>();
    for (final Object o : Yaml.asList(config.get("rows"), "rows")) {
      final Map<String, Object> r = Yaml.asMap(o, "row");
      final List<String> arms = new ArrayList<>();
      for (final Object a : Yaml.asList(r.get("arms"), "row arms")) {
        arms.add(String.valueOf(a));
      }
      rows.add(new RowPlan(String.valueOf(r.get("row")), arms, ((Number) r.get("sessions")).intValue()));
    }
    final Map<String, String> excluded = new java.util.LinkedHashMap<>();
    if (config.get("excluded_band_points") instanceof List<?> list) {
      for (final Object o : list) {
        final Map<String, Object> e = Yaml.asMap(o, "excluded band point");
        excluded.put(String.valueOf(e.get("row")), String.valueOf(e.get("reason")));
      }
    }
    return new Plan(
        rows,
        hypotheses,
        String.valueOf(family.get("id")),
        ((Number) family.get("q")).doubleValue(),
        numbers(family.get("guards_vs_default")),
        "not_above_baseline".equals(String.valueOf(family.get("structural_declines"))),
        excluded,
        superseded);
  }

  private static Map<String, Double> numbers(final Object map) {
    final Map<String, Double> out = new java.util.LinkedHashMap<>();
    if (map instanceof Map<?, ?> m) {
      for (final Map.Entry<?, ?> e : m.entrySet()) {
        out.put(String.valueOf(e.getKey()), ((Number) e.getValue()).doubleValue());
      }
    }
    return out;
  }

  /** The row plan with this label, or null. */
  public RowPlan row(final String label) {
    for (final RowPlan r : plan.rows()) {
      if (r.label().equals(label)) {
        return r;
      }
    }
    return null;
  }

  /** Every arm, baseline first. */
  public List<String> arms() {
    final List<String> out = new ArrayList<>();
    out.add(baseline);
    out.addAll(treatments);
    return out;
  }

  /** True for a description: one arm, no test. */
  public boolean descriptive() {
    return treatments.isEmpty();
  }

  /** The registered seeds, in session order. */
  public List<Long> seeds() {
    if (sessions < 0) {
      throw new IllegalStateException(id + ": the session count is not fixed yet (a draft gives only the rule)");
    }
    return seeds(seedBase, first, sessions);
  }

  /** Seeds {@code base + first .. base + first + count - 1}. */
  public static List<Long> seeds(final long base, final int first, final int count) {
    final List<Long> out = new ArrayList<>(count);
    for (int n = first; n < first + count; n++) {
      out.add(SessionSeeds.seed(base, n));
    }
    return out;
  }
}
