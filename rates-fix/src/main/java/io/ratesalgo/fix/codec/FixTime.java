package io.ratesalgo.fix.codec;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * FIX UTCTimestamp ({@code YYYYMMDD-HH:MM:SS[.sss[sss[sss]]]}) to and from epoch nanoseconds
 * without {@code java.time}, so neither direction allocates. Civil date arithmetic follows the
 * days-from-civil algorithm for the proleptic Gregorian calendar.
 */
public final class FixTime {

  /** Length of a millisecond-precision timestamp, the form this project writes. */
  public static final int MILLIS_LENGTH = 21;

  private static final long NANOS_PER_DAY = 86_400_000_000_000L;

  private FixTime() {}

  /** Days since 1970-01-01 for a civil date. */
  public static long daysFromCivil(final int year, final int month, final int day) {
    final int y = month <= 2 ? year - 1 : year;
    final int era = (y >= 0 ? y : y - 399) / 400;
    final int yoe = y - era * 400;
    final int doy = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
    final int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return (long) era * 146_097 + doe - 719_468;
  }

  /** Writes epoch nanoseconds with millisecond precision; returns {@link #MILLIS_LENGTH}. */
  public static int writeMillis(final MutableDirectBuffer buffer, final int offset, final long epochNanos) {
    final long days = Math.floorDiv(epochNanos, NANOS_PER_DAY);
    final long nanosOfDay = Math.floorMod(epochNanos, NANOS_PER_DAY);
    final long z = days + 719_468;
    final long era = (z >= 0 ? z : z - 146_096) / 146_097;
    final long doe = z - era * 146_097;
    final long yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    final long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    final long mp = (5 * doy + 2) / 153;
    final long day = doy - (153 * mp + 2) / 5 + 1;
    final long month = mp < 10 ? mp + 3 : mp - 9;
    final long year = yoe + era * 400 + (month <= 2 ? 1 : 0);
    final long millisOfDay = nanosOfDay / 1_000_000L;
    int p = offset;
    p += AsciiNumbers.writePadded(buffer, p, year, 4);
    p += AsciiNumbers.writePadded(buffer, p, month, 2);
    p += AsciiNumbers.writePadded(buffer, p, day, 2);
    buffer.putByte(p++, (byte) '-');
    p += AsciiNumbers.writePadded(buffer, p, millisOfDay / 3_600_000L, 2);
    buffer.putByte(p++, (byte) ':');
    p += AsciiNumbers.writePadded(buffer, p, (millisOfDay / 60_000L) % 60, 2);
    buffer.putByte(p++, (byte) ':');
    p += AsciiNumbers.writePadded(buffer, p, (millisOfDay / 1000L) % 60, 2);
    buffer.putByte(p++, (byte) '.');
    p += AsciiNumbers.writePadded(buffer, p, millisOfDay % 1000L, 3);
    return p - offset;
  }

  /** Parses a UTCTimestamp with 0, 3, 6 or 9 fractional digits into epoch nanoseconds. */
  public static long parse(final DirectBuffer buffer, final int offset, final int length, final int tag) {
    if (length != 17 && length != 21 && length != 24 && length != 27) {
      throw new FixFormatException(tag, "UTCTimestamp has length " + length);
    }
    if (buffer.getByte(offset + 8) != '-'
        || buffer.getByte(offset + 11) != ':'
        || buffer.getByte(offset + 14) != ':'
        || (length > 17 && buffer.getByte(offset + 17) != '.')) {
      throw new FixFormatException(tag, "UTCTimestamp separators");
    }
    final int year = digits(buffer, offset, 4, tag);
    final int month = digits(buffer, offset + 4, 2, tag);
    final int day = digits(buffer, offset + 6, 2, tag);
    final int hour = digits(buffer, offset + 9, 2, tag);
    final int minute = digits(buffer, offset + 12, 2, tag);
    final int second = digits(buffer, offset + 15, 2, tag);
    if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 60) {
      throw new FixFormatException(tag, "UTCTimestamp field out of range");
    }
    long fraction = 0;
    if (length > 17) {
      final int n = length - 18;
      fraction = digits(buffer, offset + 18, n, tag) * AsciiNumbers.pow10(9 - n);
    }
    return daysFromCivil(year, month, day) * NANOS_PER_DAY
        + ((hour * 60L + minute) * 60L + second) * 1_000_000_000L
        + fraction;
  }

  private static int digits(final DirectBuffer b, final int offset, final int n, final int tag) {
    int v = 0;
    for (int i = 0; i < n; i++) {
      final byte c = b.getByte(offset + i);
      if (c < '0' || c > '9') {
        throw new FixFormatException(tag, "UTCTimestamp digit expected");
      }
      v = v * 10 + (c - '0');
    }
    return v;
  }
}
