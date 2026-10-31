package io.ratesalgo.refdata;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** {@link CalendarSource} backed by {@code holidays.csv}; an unknown calendar name is an error. */
public final class FixtureCalendarSource implements CalendarSource {

  private final Map<String, Set<LocalDate>> holidays;

  private FixtureCalendarSource(final Map<String, Set<LocalDate>> holidays) {
    this.holidays = holidays;
  }

  /** Loads the holiday fixture. */
  public static FixtureCalendarSource load(final Path file) {
    final Csv csv = Csv.read(file);
    final Map<String, Set<LocalDate>> map = new HashMap<>();
    for (int r = 0; r < csv.size(); r++) {
      final String name = csv.get(r, "calendar");
      final LocalDate date = Fields.date(csv, r, "date");
      if (!map.computeIfAbsent(name, k -> new HashSet<>()).add(date)) {
        throw new RefDataException(csv.where(r) + ": duplicate holiday " + name + " " + date);
      }
    }
    return new FixtureCalendarSource(map);
  }

  @Override
  public boolean isHoliday(final String calendar, final LocalDate date) {
    final Set<LocalDate> set = holidays.get(calendar);
    if (set == null) {
      throw new RefDataException("unknown calendar '" + calendar + "'");
    }
    return set.contains(date);
  }

  @Override
  public boolean knows(final String calendar) {
    return holidays.containsKey(calendar);
  }
}
