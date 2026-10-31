package io.ratesalgo.fix.codec;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * Exact conversions between ASCII decimal text and scaled {@code long} values. A price with scale
 * 9 is the int64 mantissa of API_SPEC.md Section 0; there is no floating point anywhere on the
 * path, so {@code "99.123456789"} becomes {@code 99123456789L} and back without rounding.
 */
public final class AsciiNumbers {

  private static final long[] POW10 = new long[19];

  static {
    long p = 1;
    for (int i = 0; i < POW10.length; i++) {
      POW10[i] = p;
      p *= 10;
    }
  }

  private AsciiNumbers() {}

  /** Ten to the power {@code n}, for {@code 0 <= n <= 18}. */
  public static long pow10(final int n) {
    return POW10[n];
  }

  /**
   * Parses decimal text into a value scaled by {@code 10^scale}. Digits beyond {@code scale}
   * fractional places must be zero, otherwise the value is not representable and the call throws.
   */
  public static long parseDecimal(
      final DirectBuffer buffer, final int offset, final int length, final int scale, final int tag) {
    if (length <= 0) {
      throw new FixFormatException(tag, "empty decimal");
    }
    int i = offset;
    final int end = offset + length;
    boolean negative = false;
    final byte first = buffer.getByte(i);
    if (first == '-') {
      negative = true;
      i++;
    } else if (first == '+') {
      i++;
    }
    long value = 0;
    int fraction = -1;
    boolean digits = false;
    try {
      for (; i < end; i++) {
        final byte c = buffer.getByte(i);
        if (c == '.') {
          if (fraction >= 0) {
            throw new FixFormatException(tag, "two decimal points");
          }
          fraction = 0;
          continue;
        }
        if (c < '0' || c > '9') {
          throw new FixFormatException(tag, "not a decimal");
        }
        digits = true;
        if (fraction >= 0) {
          if (fraction == scale) {
            if (c != '0') {
              throw new FixFormatException(tag, "more than " + scale + " decimal places");
            }
            continue;
          }
          fraction++;
        }
        value = Math.addExact(Math.multiplyExact(value, 10L), c - '0');
      }
      if (!digits) {
        throw new FixFormatException(tag, "no digits");
      }
      for (int f = Math.max(fraction, 0); f < scale; f++) {
        value = Math.multiplyExact(value, 10L);
      }
    } catch (ArithmeticException e) {
      throw new FixFormatException(tag, "decimal out of range");
    }
    return negative ? -value : value;
  }

  /** Parses a signed integer. */
  public static long parseLong(
      final DirectBuffer buffer, final int offset, final int length, final int tag) {
    return parseDecimal(buffer, offset, length, 0, tag);
  }

  /**
   * Writes a scaled value as decimal text with trailing fractional zeros removed. Returns the
   * number of bytes written.
   */
  public static int writeDecimal(
      final MutableDirectBuffer buffer, final int offset, final long value, final int scale) {
    if (value == Long.MIN_VALUE) {
      throw new IllegalArgumentException("Long.MIN_VALUE has no decimal form here");
    }
    int pos = offset;
    long abs = value;
    if (value < 0) {
      buffer.putByte(pos++, (byte) '-');
      abs = -value;
    }
    final long unit = POW10[scale];
    final long integer = abs / unit;
    long fraction = abs % unit;
    pos += writeNatural(buffer, pos, integer);
    if (fraction != 0) {
      int digits = scale;
      while (fraction % 10 == 0) {
        fraction /= 10;
        digits--;
      }
      buffer.putByte(pos++, (byte) '.');
      for (int d = digits - 1; d >= 0; d--) {
        buffer.putByte(pos + d, (byte) ('0' + fraction % 10));
        fraction /= 10;
      }
      pos += digits;
    }
    return pos - offset;
  }

  /** Writes a non-negative integer; returns bytes written. */
  public static int writeNatural(final MutableDirectBuffer buffer, final int offset, final long v) {
    if (v < 0) {
      throw new IllegalArgumentException("negative: " + v);
    }
    int digits = 1;
    for (long t = v / 10; t != 0; t /= 10) {
      digits++;
    }
    long r = v;
    for (int d = digits - 1; d >= 0; d--) {
      buffer.putByte(offset + d, (byte) ('0' + r % 10));
      r /= 10;
    }
    return digits;
  }

  /** Writes a non-negative integer left-padded with zeros to {@code width}; returns width. */
  public static int writePadded(
      final MutableDirectBuffer buffer, final int offset, final long v, final int width) {
    long r = v;
    for (int d = width - 1; d >= 0; d--) {
      buffer.putByte(offset + d, (byte) ('0' + r % 10));
      r /= 10;
    }
    return width;
  }
}
