package io.ratesalgo.venues.sim;

import quickfix.FieldNotFound;
import quickfix.Group;
import quickfix.Message;

/** QuickFIX/J message helpers that keep decimals as text (QuickFIX/J price fields use double). */
public final class Fix {

  private Fix() {}

  /** New message of a type. */
  public static Message message(final String msgType) {
    final Message m = new Message();
    m.getHeader().setString(35, msgType);
    return m;
  }

  /** String value or null. */
  public static String get(final quickfix.FieldMap m, final int tag) {
    try {
      return m.isSetField(tag) ? m.getString(tag) : null;
    } catch (FieldNotFound e) {
      return null;
    }
  }

  /** Required string value. */
  public static String req(final quickfix.FieldMap m, final int tag) {
    final String v = get(m, tag);
    if (v == null) {
      throw new IllegalArgumentException("required tag " + tag + " missing");
    }
    return v;
  }

  /** Message type of a message. */
  public static String type(final Message m) {
    return get(m.getHeader(), 35);
  }

  /** First group entry for a group tag, or null. */
  public static Group group(final Message m, final int countTag, final int delimiter) {
    try {
      if (!m.isSetField(countTag)) {
        return null;
      }
      final Group g = new Group(countTag, delimiter);
      m.getGroup(1, g);
      return g;
    } catch (FieldNotFound e) {
      return null;
    }
  }

  /** A field read from the message body or, if absent, from the first entry of a group. */
  public static String bodyOrGroup(final Message m, final int tag, final int countTag, final int delimiter) {
    final String v = get(m, tag);
    if (v != null) {
      return v;
    }
    final Group g = group(m, countTag, delimiter);
    return g == null ? null : get(g, tag);
  }

  /** Writes a price mantissa as decimal text. */
  public static void price(final quickfix.FieldMap m, final int tag, final long mantissa) {
    m.setString(tag, Px.text(mantissa));
  }

  /** Writes epoch nanoseconds as a millisecond UTCTimestamp. */
  public static void time(final quickfix.FieldMap m, final int tag, final long epochNanos) {
    final java.time.Instant t = java.time.Instant.ofEpochSecond(0, epochNanos);
    m.setString(
        tag,
        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS")
            .withZone(java.time.ZoneOffset.UTC)
            .format(t));
  }
}
