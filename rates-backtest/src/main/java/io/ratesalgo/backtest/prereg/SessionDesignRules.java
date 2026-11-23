package io.ratesalgo.backtest.prereg;

import io.ratesalgo.backtest.experiment.BandParameters;
import io.ratesalgo.backtest.experiment.SpentData;
import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.util.Hashing;
import io.ratesalgo.backtest.util.Yaml;
import io.ratesalgo.sim.SessionSeeds;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rules of a version 2 pre-registration: a session-seed design on simulator v2
 * (docs/research/SIM_V2_DESIGN.md section 12). {@code ratesresearch.prereg.validate} applies the
 * same rules and prints the same problems.
 *
 * <p>Plain idea: a version 1 file names calendar dates; a simulator v2 experiment names seeds. The
 * session with seed base + n is a full simulated day whose market depends on the seed only, so a
 * data set is (seed base, first session, session count), and the parameters the calibration could
 * not identify are run at their band points on the same seeds.
 *
 * <p>Keys, all required unless marked:
 *
 * <pre>
 *   prereg_version: 2
 *   status            optional; a value starting with "draft" validates but is never run
 *   id, title, hypothesis, metric, success_threshold, author, date (ISO), config, config_sha256
 *   baseline          the arm every treatment is compared with
 *   treatments        list; empty only for a description (then no test is computed)
 *   test              required when there are treatments
 *   data_set:
 *     seed_base       a whole million, not spent, not the acceptance or pilot base
 *     first           optional, default 0
 *     sessions        an integer of at least the floor; a draft may still give the rule as text
 *     simulator_params, simulator_params_sha256   the parameter file and its SHA-256
 *   assumption_band: {preset: sim_v2_params, parameters: [names of BandParameters], points: [low, central, high]}
 *   prior_looks       optional list of pre-registration ids
 *   guard_metrics, limitations, open_before_signing   optional text
 * </pre>
 *
 * <p>The version 1 date keys ({@code period}, {@code fitting_period}, {@code holdout}) are
 * refused: they do not describe seeded sessions. The configuration file must name every arm under
 * {@code arms}, the statistics ({@code block_sessions}, {@code resamples}, {@code bootstrap_seed})
 * and the pilot base under {@code seeds.pilot_base}, which must be the pilot base and not the data
 * set's; with treatments it must state {@code smallest_effect.usd_per_session}.
 */
public final class SessionDesignRules {

  /** The schema version these rules apply to. */
  public static final int VERSION = 2;

  /** Sessions a registered comparison needs at least (design section 12). */
  public static final int MINIMUM_SESSIONS = 2000;

  /**
   * Highest session number a data set may reach: a session's label date is 2030-01-01 plus its
   * number in days (PairedSessions.labelDate), and beyond about 85,000 days epoch nanoseconds
   * overflow a long.
   */
  public static final int MAX_SESSION_NUMBER = 80_000;

  /** Sessions a description on the exploration set needs at least: the daily statistics need two. */
  public static final int MINIMUM_DESCRIPTION_SESSIONS = 2;

  private static final List<String> REQUIRED =
      List.of(
          "id", "title", "hypothesis", "metric", "baseline", "treatments", "data_set", "success_threshold",
          "assumption_band", "author", "date", "config", "config_sha256");

  /** Guard ratios a config may give minimums for (treatment over baseline, summed over the set). */
  public static final List<String> GUARDS = List.of("quoted_share", "hit_rate", "client_dv01");

  private static final List<String> VERSION_1_KEYS = List.of("period", "fitting_period", "holdout");

  private SessionDesignRules() {}

  /** True if the file has any version 1 date key. */
  public static boolean hasDateKeys(final Map<String, Object> raw) {
    return VERSION_1_KEYS.stream().anyMatch(raw::containsKey);
  }

  /** One problem per version 1 date key present. */
  public static List<String> dateKeyProblems(final Map<String, Object> raw) {
    final List<String> out = new ArrayList<>();
    for (final String key : VERSION_1_KEYS) {
      if (raw.containsKey(key)) {
        out.add("version 1 date key '" + key + "' is not allowed in a session-seed design");
      }
    }
    return out;
  }

  /** Every problem with a version 2 pre-registration; empty when it is valid. */
  public static List<String> validate(final Map<String, Object> raw, final Path repoRoot) {
    final List<String> problems = new ArrayList<>();
    for (final String key : REQUIRED) {
      final Object v = raw.get(key);
      if (v == null || (v instanceof String s && s.isBlank())) {
        problems.add("missing or blank key '" + key + "'");
      }
    }
    problems.addAll(dateKeyProblems(raw));
    if (!problems.isEmpty()) {
      return problems;
    }
    final boolean draft = raw.get("status") != null && String.valueOf(raw.get("status")).trim().startsWith("draft");
    final List<String> treatments = new ArrayList<>();
    if (raw.get("treatments") instanceof List<?> t) {
      for (final Object o : t) {
        treatments.add(String.valueOf(o));
      }
    } else {
      problems.add("treatments must be a list (empty for a description)");
    }
    if (!treatments.isEmpty() && blank(raw.get("test"))) {
      problems.add("missing or blank key 'test': a comparison names its test");
    }
    if (treatments.contains(String.valueOf(raw.get("baseline")))) {
      problems.add("the baseline arm is also listed as a treatment");
    }
    try {
      LocalDate.parse(String.valueOf(raw.get("date")).trim());
    } catch (final DateTimeParseException e) {
      problems.add("date is not an ISO date: " + raw.get("date"));
    }
    if (raw.get("prior_looks") != null && !(raw.get("prior_looks") instanceof List<?>)) {
      problems.add("prior_looks must be a list of pre-registration ids");
    }
    final long base = dataSet(raw, treatments.isEmpty(), draft, repoRoot, problems);
    band(raw, problems);
    final Path config = repoRoot.resolve(String.valueOf(raw.get("config")));
    if (!Files.isRegularFile(config)) {
      problems.add("config file not found: " + raw.get("config"));
    } else {
      final String actual = Hashing.sha256TextFile(config);
      if (!actual.equals(String.valueOf(raw.get("config_sha256")))) {
        problems.add("config_sha256 " + raw.get("config_sha256") + " does not match the config file (" + actual + ")");
      }
      config(Yaml.loadMap(config), String.valueOf(raw.get("baseline")), treatments, base, problems);
    }
    return problems;
  }

  private static boolean blank(final Object v) {
    return v == null || (v instanceof String s && s.isBlank());
  }

  private static long dataSet(
      final Map<String, Object> raw, final boolean description, final boolean draft, final Path repoRoot,
      final List<String> problems) {
    if (!(raw.get("data_set") instanceof Map<?, ?> m)) {
      problems.add("data_set must be a mapping with seed_base, sessions and simulator_params");
      return -1;
    }
    long base = -1;
    if (m.get("seed_base") instanceof Number n) {
      base = n.longValue();
      final String refusal = SpentData.registeredRefusal(base);
      if (refusal != null) {
        problems.add("data_set.seed_base: " + refusal);
      }
    } else {
      problems.add("data_set.seed_base must be an integer");
    }
    long first = 0;
    if (m.get("first") != null) {
      if (m.get("first") instanceof Number n && n.longValue() >= 0) {
        first = n.longValue();
      } else {
        problems.add("data_set.first must be a non-negative integer");
      }
    }
    final int floor = description ? MINIMUM_DESCRIPTION_SESSIONS : MINIMUM_SESSIONS;
    final Object sessions = m.get("sessions");
    if (sessions instanceof Number n) {
      if (n.longValue() < floor) {
        problems.add("data_set.sessions " + n + " is below the floor of " + floor);
      } else if (first + n.longValue() > MAX_SESSION_NUMBER) {
        problems.add(
            "data_set.first + sessions exceeds " + MAX_SESSION_NUMBER
                + ": later sessions' label dates (2030-01-01 plus the session number in days) overflow epoch nanoseconds");
      }
    } else if (!(draft && sessions instanceof String s && !s.isBlank())) {
      problems.add(
          draft
              ? "data_set.sessions must be an integer, or the rule as text while the file is a draft"
              : "data_set.sessions must be an integer of at least " + floor + " once the file is not a draft");
    }
    final Object params = m.get("simulator_params");
    if (blank(params)) {
      problems.add("data_set.simulator_params is missing");
    } else {
      final Path file = repoRoot.resolve(String.valueOf(params));
      if (!Files.isRegularFile(file)) {
        problems.add("data_set.simulator_params not found: " + params);
      } else if (!Hashing.sha256TextFile(file).equals(String.valueOf(m.get("simulator_params_sha256")))) {
        problems.add("data_set.simulator_params_sha256 does not match " + params + " (" + Hashing.sha256TextFile(file) + ")");
      }
    }
    return base;
  }

  private static void band(final Map<String, Object> raw, final List<String> problems) {
    if (!(raw.get("assumption_band") instanceof Map<?, ?> band)) {
      problems.add("assumption_band must be a mapping");
      return;
    }
    if (!"sim_v2_params".equals(String.valueOf(band.get("preset")))) {
      problems.add("assumption_band.preset must be sim_v2_params, found " + band.get("preset"));
    }
    if (band.get("parameters") instanceof List<?> names) {
      for (final Object n : names) {
        if (!BandParameters.known(String.valueOf(n))) {
          problems.add("assumption_band.parameters has an unknown parameter '" + n + "'; known: " + BandParameters.names());
        }
      }
    } else {
      problems.add("assumption_band.parameters must be a list of band parameter names");
    }
    if (band.get("points") instanceof List<?> points) {
      final Set<BandPoint> seen = EnumSet.noneOf(BandPoint.class);
      for (final Object p : points) {
        try {
          seen.add(BandPoint.parse(String.valueOf(p)));
        } catch (final IllegalArgumentException e) {
          problems.add("assumption_band.points has an unknown point '" + p + "'");
        }
      }
      if (!seen.equals(EnumSet.allOf(BandPoint.class))) {
        problems.add("assumption_band.points must be low, central and high (DR-008)");
      }
    } else {
      problems.add("assumption_band.points must be a list");
    }
  }

  private static void config(
      final Map<String, Object> config, final String baseline, final List<String> treatments, final long base,
      final List<String> problems) {
    if (config.get("arms") instanceof Map<?, ?> arms) {
      if (!arms.containsKey(baseline)) {
        problems.add("config arms do not name the baseline '" + baseline + "'");
      }
      for (final String t : treatments) {
        if (!arms.containsKey(t)) {
          problems.add("config arms do not name the treatment '" + t + "'");
        }
      }
    } else {
      problems.add("config must name the arms under 'arms'");
    }
    if (config.get("statistics") instanceof Map<?, ?> st) {
      positive(st.get("block_sessions"), "config statistics.block_sessions", problems);
      positive(st.get("resamples"), "config statistics.resamples", problems);
      if (!(st.get("bootstrap_seed") instanceof Number)) {
        problems.add("config statistics.bootstrap_seed must be an integer");
      }
    } else {
      problems.add("config must state the statistics under 'statistics'");
    }
    if (config.get("seeds") instanceof Map<?, ?> seeds) {
      final Object pilot = seeds.get("pilot_base");
      if (!(pilot instanceof Number p) || p.longValue() != SessionSeeds.PILOT) {
        problems.add("config seeds.pilot_base must be the pilot base " + SessionSeeds.PILOT + ", found " + pilot);
      }
      for (final String key : List.of("confirmation_base", "base")) {
        if (base >= 0 && seeds.get(key) instanceof Number n && n.longValue() != base) {
          problems.add("config seeds." + key + " " + n + " differs from data_set.seed_base " + base);
        }
      }
      if (seeds.get("minimum_sessions") instanceof Number n && n.longValue() < MINIMUM_SESSIONS) {
        problems.add("config seeds.minimum_sessions " + n + " is below the design's floor of " + MINIMUM_SESSIONS);
      }
    } else {
      problems.add("config must state the pilot base under 'seeds.pilot_base'");
    }
    if (config.get("guards") != null) {
      if (config.get("guards") instanceof Map<?, ?> g) {
        for (final Map.Entry<?, ?> e : g.entrySet()) {
          if (!GUARDS.contains(String.valueOf(e.getKey()))) {
            problems.add("config guards." + e.getKey() + " is not a guard; known: " + GUARDS);
          } else if (!(e.getValue() instanceof Number n) || !(n.doubleValue() > 0.0)) {
            problems.add("config guards." + e.getKey() + " must be a positive ratio");
          }
        }
      } else {
        problems.add("config guards must be a mapping of guard name to minimum ratio");
      }
    }
    if (!treatments.isEmpty()) {
      final Object effect =
          config.get("smallest_effect") instanceof Map<?, ?> e ? e.get("usd_per_session") : null;
      if (!(effect instanceof Number n) || !(n.doubleValue() > 0.0)) {
        problems.add("config smallest_effect.usd_per_session must be a positive number, stated before the pilot");
      }
    }
  }

  private static void positive(final Object v, final String key, final List<String> problems) {
    if (!(v instanceof Number n) || n.longValue() <= 0) {
      problems.add(key + " must be a positive integer");
    }
  }
}
