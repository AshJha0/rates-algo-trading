package io.ratesalgo.analytics.date;

import java.util.Arrays;

/**
 * A day is a business day on the joint calendar only if it is a business day on every member.
 * This is the payment calendar of a USD SOFR swap (New York Fed and SIFMA) and of any
 * cross-market instrument.
 */
public final class JointCalendar implements HolidayCalendar {

  private final String name;
  private final HolidayCalendar[] members;

  /** Builds the joint calendar; at least one member is required. */
  public JointCalendar(final HolidayCalendar... members) {
    if (members.length == 0) {
      throw new IllegalArgumentException("a joint calendar needs at least one member");
    }
    this.members = Arrays.copyOf(members, members.length);
    final StringBuilder sb = new StringBuilder();
    for (int i = 0; i < members.length; i++) {
      if (i > 0) {
        sb.append('+');
      }
      sb.append(members[i].name());
    }
    this.name = sb.toString();
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public boolean isBusinessDay(final int epochDay) {
    for (final HolidayCalendar member : members) {
      if (!member.isBusinessDay(epochDay)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public String toString() {
    return name;
  }
}
