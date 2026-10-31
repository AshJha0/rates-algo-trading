package io.ratesalgo.backtest.prereg;

import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.util.Hashing;
import io.ratesalgo.backtest.util.Yaml;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A pre-registration: what an experiment will test, on which data, against which threshold,
 * written and hashed before the experiment runs (API_SPEC.md Section 16.8).
 *
 * <p>The file is YAML with these keys, all required: {@code prereg_version} (1), {@code id},
 * {@code title}, {@code hypothesis}, {@code metric}, {@code baseline}, {@code treatments} (list),
 * {@code period} and {@code fitting_period} (each {@code start}, {@code end}), {@code holdout}
 * (list of dates), {@code success_threshold}, {@code assumption_band} ({@code preset},
 * {@code parameter}, {@code points}), {@code author}, {@code date}, {@code config} (path relative
 * to the repository root) and {@code config_sha256}.
 *
 * <p>{@link #validate} returns every problem it finds rather than the first, so one review fixes
 * them all. The rules: every key present and non-blank; dates in ISO form with start not after
 * end; the evaluation period and every holdout date outside the fitting period; no holdout date
 * inside the evaluation period; the band points are exactly low, central and high (DR-008: never
 * one number); the config file exists and its SHA-256 (text, LF line endings) matches.
 * {@code ratesresearch.prereg.validate} applies the same rules.
 */
public final class PreRegistration {

  public static final int VERSION = 1;

  private static final List<String> REQUIRED =
      List.of(
          "prereg_version", "id", "title", "hypothesis", "metric", "baseline", "treatments", "period",
          "fitting_period", "holdout", "success_threshold", "assumption_band", "author", "date",
          "config", "config_sha256");

  private final Path file;
  private final Map<String, Object> raw;
  private final String sha256;

  private PreRegistration(final Path file, final Map<String, Object> raw, final String sha256) {
    this.file = file;
    this.raw = raw;
    this.sha256 = sha256;
  }

  /**
   * Loads a pre-registration.
   *
   * @throws PreRegistrationException if the file does not exist or is not a YAML mapping
   */
  public static PreRegistration load(final Path file) {
    if (!Files.isRegularFile(file)) {
      throw new PreRegistrationException(List.of("pre-registration not found: " + file));
    }
    try {
      return new PreRegistration(file, Yaml.loadMap(file), Hashing.sha256TextFile(file));
    } catch (final IllegalArgumentException e) {
      throw new PreRegistrationException(List.of(file + ": " + e.getMessage()));
    }
  }

  /** Every problem with this pre-registration; empty when it is valid. */
  public List<String> validate(final Path repoRoot) {
    final List<String> problems = new ArrayList<>();
    for (final String key : REQUIRED) {
      final Object v = raw.get(key);
      if (v == null || (v instanceof String s && s.isBlank())) {
        problems.add("missing or blank key '" + key + "'");
      }
    }
    if (!problems.isEmpty()) {
      return problems;
    }
    if (!Integer.valueOf(VERSION).equals(raw.get("prereg_version"))) {
      problems.add("prereg_version must be " + VERSION + ", found " + raw.get("prereg_version"));
    }
    final DateRange period = range("period", problems);
    final DateRange fitting = range("fitting_period", problems);
    final List<LocalDate> holdout = new ArrayList<>();
    final Object rawHoldout = raw.get("holdout");
    if (rawHoldout instanceof List<?> list && !list.isEmpty()) {
      for (final Object o : list) {
        final LocalDate d = date(String.valueOf(o), "holdout", problems);
        if (d != null) {
          holdout.add(d);
        }
      }
    } else {
      problems.add("holdout must be a non-empty list of dates");
    }
    if (holdout.size() == 1) {
      problems.add("holdout needs at least two dates: the daily statistics need two days");
    }
    if (period != null && period.businessDays().size() < 2) {
      problems.add("evaluation period needs at least two business days");
    }
    date(String.valueOf(raw.get("date")), "date", problems);
    if (period != null && fitting != null && period.overlaps(fitting)) {
      problems.add("evaluation period " + period + " overlaps the fitting period " + fitting);
    }
    for (final LocalDate d : holdout) {
      if (fitting != null && fitting.contains(d)) {
        problems.add("holdout date " + d + " overlaps the fitting period " + fitting);
      }
      if (period != null && period.contains(d)) {
        problems.add("holdout date " + d + " lies inside the evaluation period " + period);
      }
    }
    if (!(raw.get("treatments") instanceof List<?> t) || t.isEmpty()) {
      problems.add("treatments must be a non-empty list");
    }
    checkBand(problems);
    final Path config = repoRoot.resolve(String.valueOf(raw.get("config")));
    if (!Files.isRegularFile(config)) {
      problems.add("config file not found: " + raw.get("config"));
    } else {
      final String actual = Hashing.sha256TextFile(config);
      if (!actual.equals(String.valueOf(raw.get("config_sha256")))) {
        problems.add("config_sha256 " + raw.get("config_sha256") + " does not match the config file (" + actual + ")");
      }
    }
    return problems;
  }

  /** Throws {@link PreRegistrationException} listing every problem, if there are any. */
  public PreRegistration requireValid(final Path repoRoot) {
    final List<String> problems = validate(repoRoot);
    if (!problems.isEmpty()) {
      throw new PreRegistrationException(problems);
    }
    return this;
  }

  private void checkBand(final List<String> problems) {
    final Object b = raw.get("assumption_band");
    if (!(b instanceof Map<?, ?> band)) {
      problems.add("assumption_band must be a mapping");
      return;
    }
    for (final String key : List.of("preset", "parameter", "points")) {
      if (band.get(key) == null) {
        problems.add("assumption_band." + key + " is missing");
      }
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
    } else if (band.get("points") != null) {
      problems.add("assumption_band.points must be a list");
    }
  }

  private DateRange range(final String key, final List<String> problems) {
    final Object v = raw.get(key);
    if (!(v instanceof Map<?, ?> m)) {
      problems.add(key + " must have start and end");
      return null;
    }
    final LocalDate start = date(String.valueOf(m.get("start")), key + ".start", problems);
    final LocalDate end = date(String.valueOf(m.get("end")), key + ".end", problems);
    if (start == null || end == null) {
      return null;
    }
    if (start.isAfter(end)) {
      problems.add(key + " starts after it ends: " + start + " > " + end);
      return null;
    }
    return new DateRange(start, end);
  }

  private static LocalDate date(final String text, final String key, final List<String> problems) {
    try {
      return LocalDate.parse(text.trim());
    } catch (final DateTimeParseException e) {
      problems.add(key + " is not an ISO date: " + text);
      return null;
    }
  }

  /** An inclusive range of dates. */
  public record DateRange(LocalDate start, LocalDate end) {
    public boolean contains(final LocalDate d) {
      return !d.isBefore(start) && !d.isAfter(end);
    }

    public boolean overlaps(final DateRange o) {
      return !o.end.isBefore(start) && !o.start.isAfter(end);
    }

    /** Monday-to-Friday dates in the range, ascending. */
    public List<LocalDate> businessDays() {
      final List<LocalDate> days = new ArrayList<>();
      for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
        if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
          days.add(d);
        }
      }
      return days;
    }

    @Override
    public String toString() {
      return start + ".." + end;
    }
  }

  public String id() {
    return String.valueOf(raw.get("id"));
  }

  public String sha256() {
    return sha256;
  }

  public Path file() {
    return file;
  }

  public String baseline() {
    return String.valueOf(raw.get("baseline"));
  }

  public List<String> treatments() {
    final List<String> out = new ArrayList<>();
    for (final Object o : Yaml.asList(raw.get("treatments"), "treatments")) {
      out.add(String.valueOf(o));
    }
    return out;
  }

  public DateRange period() {
    final Map<String, Object> m = Yaml.asMap(raw.get("period"), "period");
    return new DateRange(LocalDate.parse(String.valueOf(m.get("start"))), LocalDate.parse(String.valueOf(m.get("end"))));
  }

  public DateRange fittingPeriod() {
    final Map<String, Object> m = Yaml.asMap(raw.get("fitting_period"), "fitting_period");
    return new DateRange(LocalDate.parse(String.valueOf(m.get("start"))), LocalDate.parse(String.valueOf(m.get("end"))));
  }

  public List<LocalDate> holdout() {
    final List<LocalDate> out = new ArrayList<>();
    for (final Object o : Yaml.asList(raw.get("holdout"), "holdout")) {
      out.add(LocalDate.parse(String.valueOf(o)));
    }
    return out;
  }

  public String config() {
    return String.valueOf(raw.get("config"));
  }

  public String configSha256() {
    return String.valueOf(raw.get("config_sha256"));
  }
}
