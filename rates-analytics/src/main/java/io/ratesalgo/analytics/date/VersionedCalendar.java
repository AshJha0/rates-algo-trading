package io.ratesalgo.analytics.date;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * A calendar made of a standing weekly and annual rule plus a list of ad hoc changes, each with
 * the date from which it was known. An ad hoc change is a one-off closure (a state funeral, a
 * jubilee) or a one-off opening.
 *
 * <p>The standing rule never changes once published, so adding an ad hoc holiday does not rewrite
 * history: {@link #asOf(int)} returns the calendar as it was known on a past date, which is what a
 * replay of that day must use. For example the UK bank holiday for the State Funeral of Queen
 * Elizabeth II on 2022-09-19 was announced on 2022-09-10; {@code LONDON.asOf(2022-09-09)} treats
 * 2022-09-19 as a business day and {@code LONDON.asOf(2022-09-10)} does not.
 *
 * <p>Business days from 1950 to 2149 are precomputed into a bit set at construction, so {@link
 * #isBusinessDay} is a shift and a mask on that range and evaluates the rule outside it.
 */
public final class VersionedCalendar implements HolidayCalendar {

  /** The standing rule: true if the date is a holiday (weekends are handled separately). */
  @FunctionalInterface
  public interface Rule {
    /**
     * Creates the instance from its parts.
     *
     * @param year calendar year
     * @param month 1 to 12
     * @param day day of month
     * @param dayOfWeek ISO day of week, 1 Monday to 7 Sunday
     * @param dayOfYear 1 for 1 January
     * @param easterMonday day of year of Easter Monday in this year
     */
    boolean isHoliday(int year, int month, int day, int dayOfWeek, int dayOfYear, int easterMonday);
  }

  /**
   * One ad hoc change: on {@code date} the market is closed ({@code closed} true) or open, as
   * known from {@code knownFrom} onwards.
   */
  public record AdHoc(int date, boolean closed, int knownFrom, String reason) {
    public AdHoc {
      if (knownFrom > date) {
        // announcements after the event (a retrospective closure) are not representable
        throw new IllegalArgumentException(
            "ad hoc change for " + Dates.format(date) + " known only after it: "
                + Dates.format(knownFrom));
      }
    }
  }

  private static final int FIRST = Dates.of(1950, 1, 1);
  private static final int LAST = Dates.of(2149, 12, 31);

  private final String name;
  private final Rule rule;
  private final List<AdHoc> adHoc;
  private final int knowledgeDate;
  private final long[] businessBits;

  /**
   * Builds the calendar with every ad hoc change applied (knowledge date unbounded).
   *
   * @param name identifier
   * @param rule the standing rule
   * @param adHoc ad hoc changes; copied and sorted by date
   */
  public VersionedCalendar(final String name, final Rule rule, final List<AdHoc> adHoc) {
    this(name, rule, adHoc, Integer.MAX_VALUE);
  }

  private VersionedCalendar(
      final String name, final Rule rule, final List<AdHoc> adHoc, final int knowledgeDate) {
    this.name = name;
    this.rule = rule;
    final List<AdHoc> sorted = new ArrayList<>(adHoc);
    sorted.sort(Comparator.comparingInt(AdHoc::date));
    this.adHoc = Collections.unmodifiableList(sorted);
    this.knowledgeDate = knowledgeDate;
    final int days = LAST - FIRST + 1;
    this.businessBits = new long[(days + 63) >>> 6];
    for (int d = FIRST; d <= LAST; d++) {
      if (evaluate(d)) {
        final int i = d - FIRST;
        businessBits[i >>> 6] |= 1L << (i & 63);
      }
    }
  }

  /** The calendar as known on {@code date}: ad hoc changes announced later are ignored. */
  public VersionedCalendar asOf(final int date) {
    return new VersionedCalendar(name, rule, adHoc, date);
  }

  /** Returns a calendar with one more ad hoc change; this instance is unchanged. */
  public VersionedCalendar withAdHoc(final AdHoc change) {
    final List<AdHoc> next = new ArrayList<>(adHoc);
    next.add(change);
    return new VersionedCalendar(name, rule, next, knowledgeDate);
  }

  /** The ad hoc changes in date order, including those not yet known at the knowledge date. */
  public List<AdHoc> adHocChanges() {
    return adHoc;
  }

  /** The knowledge date of this view; {@link Integer#MAX_VALUE} means every change applies. */
  public int knowledgeDate() {
    return knowledgeDate;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public boolean isBusinessDay(final int epochDay) {
    if (epochDay >= FIRST && epochDay <= LAST) {
      final int i = epochDay - FIRST;
      return (businessBits[i >>> 6] & (1L << (i & 63))) != 0;
    }
    return evaluate(epochDay);
  }

  private boolean evaluate(final int d) {
    for (int i = 0; i < adHoc.size(); i++) {
      final AdHoc a = adHoc.get(i);
      if (a.date() == d && a.knownFrom() <= knowledgeDate) {
        return !a.closed();
      }
    }
    final int dow = Dates.dayOfWeek(d);
    if (dow >= Dates.SATURDAY) {
      return false;
    }
    final int y = Dates.year(d);
    return !rule.isHoliday(
        y,
        Dates.month(d),
        Dates.dayOfMonth(d),
        dow,
        Dates.dayOfYear(d),
        Dates.easterMondayDayOfYear(y));
  }

  @Override
  public String toString() {
    return knowledgeDate == Integer.MAX_VALUE
        ? name
        : name + " as of " + Dates.format(knowledgeDate);
  }
}
