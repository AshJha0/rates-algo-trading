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
    Map<String, Double> guardMinimums) {

  public SessionDesign {
    treatments = List.copyOf(treatments);
    bandParameters = List.copyOf(bandParameters);
    guardMinimums = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(guardMinimums));
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
    return new SessionDesign(
        prereg.id(),
        ((Number) dataSet.get("seed_base")).longValue(),
        dataSet.get("first") instanceof Number f ? f.intValue() : 0,
        dataSet.get("sessions") instanceof Number s ? s.intValue() : -1,
        prereg.baseline(),
        prereg.treatments(),
        band,
        (int) Yaml.integer(st, "block_sessions", "statistics"),
        (int) Yaml.integer(st, "resamples", "statistics"),
        Yaml.integer(st, "bootstrap_seed", "statistics"),
        effect,
        Yaml.integer(seeds, "pilot_base", "seeds"),
        prereg.isDraft(),
        guards);
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
