package io.ratesalgo.fix.codec;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * Flyweight index of one framed FIX message. {@link #wrap} records each field's tag, value offset
 * and value length in pre-sized arrays and checks BeginString, BodyLength and CheckSum; accessors
 * read values in place. Repeating groups are read by field position: {@link #indexOf(int, int)}
 * finds the next occurrence of a tag at or after a position.
 *
 * <p>Single-threaded. The parser does not copy the message, so the buffer must not change while
 * the parser is in use.
 */
public final class FixParser {

  /** Field separator. */
  public static final byte SOH = 1;

  /** Largest number of fields one message may carry. */
  public static final int MAX_FIELDS = 1024;

  /** {@link #wrap} result: well formed. */
  public static final int OK = 0;

  /** {@link #wrap} result: a field is not {@code tag=value<SOH>}. */
  public static final int MALFORMED = 1;

  /** {@link #wrap} result: the first three fields are not 8, 9, 35. */
  public static final int BAD_HEADER = 2;

  /** {@link #wrap} result: BodyLength does not match. */
  public static final int BAD_BODY_LENGTH = 3;

  /** {@link #wrap} result: CheckSum does not match or is not last. */
  public static final int BAD_CHECKSUM = 4;

  /** {@link #wrap} result: more than {@link #MAX_FIELDS} fields. */
  public static final int TOO_MANY_FIELDS = 5;

  private final int[] tags = new int[MAX_FIELDS];
  private final int[] offsets = new int[MAX_FIELDS];
  private final int[] lengths = new int[MAX_FIELDS];
  private DirectBuffer buffer;
  private int count;
  private int msgType;
  private int messageOffset;
  private int messageLength;

  /** Indexes a framed message; returns {@link #OK} or an error code. */
  public int wrap(final DirectBuffer buffer, final int offset, final int length) {
    this.buffer = buffer;
    this.messageOffset = offset;
    this.messageLength = length;
    this.count = 0;
    this.msgType = 0;
    final int end = offset + length;
    int pos = offset;
    int checksumStart = -1;
    while (pos < end) {
      final int fieldStart = pos;
      int tag = 0;
      byte c;
      while (pos < end && (c = buffer.getByte(pos)) != '=') {
        if (c < '0' || c > '9') {
          return MALFORMED;
        }
        tag = tag * 10 + (c - '0');
        pos++;
      }
      if (pos >= end || pos == fieldStart || tag == 0) {
        return MALFORMED;
      }
      pos++;
      final int valueStart = pos;
      while (pos < end && buffer.getByte(pos) != SOH) {
        pos++;
      }
      if (pos >= end) {
        return MALFORMED;
      }
      if (count == MAX_FIELDS) {
        return TOO_MANY_FIELDS;
      }
      tags[count] = tag;
      offsets[count] = valueStart;
      lengths[count] = pos - valueStart;
      count++;
      pos++;
      if (tag == FixTags.CHECK_SUM) {
        checksumStart = fieldStart;
        break;
      }
    }
    if (count < 4
        || tags[0] != FixTags.BEGIN_STRING
        || tags[1] != FixTags.BODY_LENGTH
        || tags[2] != FixTags.MSG_TYPE
        || lengths[2] < 1
        || lengths[2] > 2) {
      return BAD_HEADER;
    }
    if (checksumStart < 0 || pos != end || lengths[count - 1] != 3) {
      return BAD_CHECKSUM;
    }
    final int bodyStart = offsets[1] + lengths[1] + 1;
    final long declared;
    final long declaredSum;
    try {
      declared = getLongAt(1);
      declaredSum = getLongAt(count - 1);
    } catch (FixFormatException e) {
      return BAD_HEADER;
    }
    if (declared != checksumStart - bodyStart) {
      return BAD_BODY_LENGTH;
    }
    int sum = 0;
    for (int i = offset; i < checksumStart; i++) {
      sum += buffer.getByte(i) & 0xFF;
    }
    if ((sum & 0xFF) != declaredSum) {
      return BAD_CHECKSUM;
    }
    msgType =
        lengths[2] == 1
            ? buffer.getByte(offsets[2]) & 0xFF
            : ((buffer.getByte(offsets[2]) & 0xFF) << 8) | (buffer.getByte(offsets[2] + 1) & 0xFF);
    return OK;
  }

  /** The message type in the int form of {@link MsgType}. */
  public int msgType() {
    return msgType;
  }

  /** Number of fields including header and trailer. */
  public int fieldCount() {
    return count;
  }

  /** Tag of the field at a position. */
  public int tagAt(final int index) {
    return tags[index];
  }

  /** The wrapped buffer. */
  public DirectBuffer buffer() {
    return buffer;
  }

  /** Offset of the wrapped message in the buffer. */
  public int messageOffset() {
    return messageOffset;
  }

  /** Length of the wrapped message. */
  public int messageLength() {
    return messageLength;
  }

  /** Offset of a field value in the buffer. */
  public int valueOffset(final int index) {
    return offsets[index];
  }

  /** Length of a field value. */
  public int valueLength(final int index) {
    return lengths[index];
  }

  /** Position of the first field with the tag, or -1. */
  public int indexOf(final int tag) {
    return indexOf(tag, 0);
  }

  /** Position of the first field with the tag at or after {@code from}, or -1. */
  public int indexOf(final int tag, final int from) {
    for (int i = from; i < count; i++) {
      if (tags[i] == tag) {
        return i;
      }
    }
    return -1;
  }

  /** True if the tag is present. */
  public boolean has(final int tag) {
    return indexOf(tag) >= 0;
  }

  private int require(final int tag) {
    final int i = indexOf(tag);
    if (i < 0) {
      throw new FixFormatException(tag, "required field missing");
    }
    return i;
  }

  /** Integer value at a position. */
  public long getLongAt(final int index) {
    return AsciiNumbers.parseLong(buffer, offsets[index], lengths[index], tags[index]);
  }

  /** Integer value of a required tag. */
  public long getLong(final int tag) {
    return getLongAt(require(tag));
  }

  /** Integer value of an optional tag, or {@code absent}. */
  public long getLong(final int tag, final long absent) {
    final int i = indexOf(tag);
    return i < 0 ? absent : getLongAt(i);
  }

  /** Decimal value at a position, scaled by {@code 10^scale}, exactly. */
  public long getDecimalAt(final int index, final int scale) {
    return AsciiNumbers.parseDecimal(buffer, offsets[index], lengths[index], scale, tags[index]);
  }

  /** Decimal value of a required tag, scaled by {@code 10^scale}. */
  public long getDecimal(final int tag, final int scale) {
    return getDecimalAt(require(tag), scale);
  }

  /** Decimal value of an optional tag, or {@code absent}. */
  public long getDecimal(final int tag, final int scale, final long absent) {
    final int i = indexOf(tag);
    return i < 0 ? absent : getDecimalAt(i, scale);
  }

  /** UTCTimestamp value at a position in epoch nanoseconds. */
  public long getTimestampAt(final int index) {
    return FixTime.parse(buffer, offsets[index], lengths[index], tags[index]);
  }

  /** UTCTimestamp of an optional tag, or {@code absent}. */
  public long getTimestamp(final int tag, final long absent) {
    final int i = indexOf(tag);
    return i < 0 ? absent : getTimestampAt(i);
  }

  /** First byte of the value at a position. */
  public byte getCharAt(final int index) {
    if (lengths[index] < 1) {
      throw new FixFormatException(tags[index], "empty value");
    }
    return buffer.getByte(offsets[index]);
  }

  /** First byte of a required tag's value. */
  public byte getChar(final int tag) {
    return getCharAt(require(tag));
  }

  /** First byte of an optional tag's value, or {@code absent}. */
  public byte getChar(final int tag, final byte absent) {
    final int i = indexOf(tag);
    return i < 0 ? absent : getCharAt(i);
  }

  /** True if the value at a position equals the ASCII bytes. */
  public boolean valueEqualsAt(final int index, final byte[] ascii) {
    if (lengths[index] != ascii.length) {
      return false;
    }
    final int o = offsets[index];
    for (int i = 0; i < ascii.length; i++) {
      if (buffer.getByte(o + i) != ascii[i]) {
        return false;
      }
    }
    return true;
  }

  /** True if the tag is present and its value equals the ASCII bytes. */
  public boolean valueEquals(final int tag, final byte[] ascii) {
    final int i = indexOf(tag);
    return i >= 0 && valueEqualsAt(i, ascii);
  }

  /** True if the tag is present with value {@code Y}. */
  public boolean isYes(final int tag) {
    final int i = indexOf(tag);
    return i >= 0 && lengths[i] == 1 && buffer.getByte(offsets[i]) == 'Y';
  }

  /** FNV-1a 64 hash of the value bytes at a position; see {@link #hash(byte[])}. */
  public long hashAt(final int index) {
    long h = 0xcbf29ce484222325L;
    final int o = offsets[index];
    for (int i = 0; i < lengths[index]; i++) {
      h ^= buffer.getByte(o + i) & 0xFF;
      h *= 0x100000001b3L;
    }
    return h;
  }

  /** FNV-1a 64 hash of ASCII bytes, matching {@link #hashAt(int)}. */
  public static long hash(final byte[] ascii) {
    long h = 0xcbf29ce484222325L;
    for (final byte b : ascii) {
      h ^= b & 0xFF;
      h *= 0x100000001b3L;
    }
    return h;
  }

  /** Copies the value at a position into {@code dst}; returns its length. */
  public int copyValueAt(final int index, final MutableDirectBuffer dst, final int dstOffset) {
    dst.putBytes(dstOffset, buffer, offsets[index], lengths[index]);
    return lengths[index];
  }

  /** The value at a position as a String (allocates; diagnostics and tests). */
  public String getStringAt(final int index) {
    return buffer.getStringWithoutLengthAscii(offsets[index], lengths[index]);
  }

  /** A required tag's value as a String (allocates; diagnostics and tests). */
  public String getString(final int tag) {
    return getStringAt(require(tag));
  }

  /** The whole message with SOH shown as {@code |} (allocates; diagnostics and tests). */
  @Override
  public String toString() {
    if (buffer == null) {
      return "";
    }
    return buffer.getStringWithoutLengthAscii(messageOffset, messageLength).replace('\u0001', '|');
  }
}
