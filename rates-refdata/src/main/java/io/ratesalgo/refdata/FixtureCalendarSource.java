package io.ratesalgo.refdata;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * {@link CalendarSource} backed by {@code holidays.csv}; an unknown calendar name is an error, and
 * so is a date outside the calendar years the file lists (from the year of its earliest holiday to
 * the year of its latest), since a missing year would otherwise read as all business days.
 */
public final class FixtureCalendarSource implements CalendarSource {

  private final Map<String, Set<LocalDate>> holidays;
  private final int firstYear;
  private final int lastYear;

  private FixtureCalendarSource(
      final Map<String, Set<LocalDate>> holidays, final int firstYear, final int lastYear) {
    this.holidays = holidays;
    this.firstYear = firstYear;
    this.lastYear = lastYear;
  }

  /** Loads the holiday fixture. */
  public static FixtureCalendarSource load(final Path file) {
    final Csv csv = Csv.read(file);
    final Map<String, Set<LocalDate>> map = new HashMap<>();
    int first = Integer.MAX_VALUE;
    int last = Integer.MIN_VALUE;
    for (int r = 0; r < csv.size(); r++) {
      final String name = csv.get(r, "calendar");
      final LocalDate date = Fields.date(csv, r, "date");
      first = Math.min(first, date.getYear());
      last = Math.max(last, date.getYear());
      if (!map.computeIfAbsent(name, k -> new HashSet<>()).add(date)) {
        throw new RefDataException(csv.where(r) + ": duplicate holiday " + name + " " + date);
      }
    }
    return new FixtureCalendarSource(map, first, last);
  }

  @Override
  public boolean isHoliday(final String calendar, final LocalDate date) {
    final Set<LocalDate> set = holidays.get(calendar);
    if (set == null) {
      throw new RefDataException("unknown calendar '" + calendar + "'");
    }
    if (date.getYear() < firstYear || date.getYear() > lastYear) {
      throw new RefDataException(
          "calendar '" + calendar + "' covers " + firstYear + " to " + lastYear + ", not " + date);
    }
    return set.contains(date);
  }

  /** First calendar year covered. */
  public int firstYear() {
    return firstYear;
  }

  /** Last calendar year covered. */
  public int lastYear() {
    return lastYear;
  }

  @Override
  public boolean knows(final String calendar) {
    return holidays.containsKey(calendar);
  }
}
