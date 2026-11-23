package io.ratesalgo.bench.jmh;

import io.ratesalgo.fix.codec.AsciiNumbers;
import io.ratesalgo.fix.codec.FixParser;
import io.ratesalgo.fix.codec.FixTags;
import io.ratesalgo.fix.codec.FixTime;
import java.nio.ByteBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * The FIX encoder as it was before round 6, kept verbatim (renamed; the methods the benchmark does not call removed) as the
 * "before" of {@link FixEncodeBenchmark}: the body goes to a scratch buffer and {@link #finish}
 * copies it behind BeginString and BodyLength, then sums every byte one at a time for CheckSum.
 */
final class LegacyFixWriter {

  private static final byte[] BEGIN = "8=FIX.4.4\u00019=".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

  private final UnsafeBuffer body;
  private int position;

  /** Creates a writer whose body may be up to {@code capacity} bytes. */
  LegacyFixWriter(final int capacity) {
    this.body = new UnsafeBuffer(ByteBuffer.allocateDirect(capacity));
  }

  /** Starts a new message with the given MsgType. */
  public LegacyFixWriter begin(final int msgType) {
    position = 0;
    putTag(FixTags.MSG_TYPE);
    if (msgType > 0xFF) {
      body.putByte(position++, (byte) (msgType >> 8));
    }
    body.putByte(position++, (byte) (msgType & 0xFF));
    body.putByte(position++, FixParser.SOH);
    return this;
  }

  private void putTag(final int tag) {
    position += AsciiNumbers.writeNatural(body, position, tag);
    body.putByte(position++, (byte) '=');
  }

  private void end() {
    body.putByte(position++, FixParser.SOH);
  }

  /** Integer field. */
  public LegacyFixWriter put(final int tag, final long value) {
    putTag(tag);
    position += AsciiNumbers.writeDecimal(body, position, value, 0);
    end();
    return this;
  }

  /** Decimal field from a value scaled by {@code 10^scale}; trailing zeros are dropped. */
  public LegacyFixWriter putDecimal(final int tag, final long scaled, final int scale) {
    putTag(tag);
    position += AsciiNumbers.writeDecimal(body, position, scaled, scale);
    end();
    return this;
  }




  /** ASCII field from a byte array. */
  public LegacyFixWriter putAscii(final int tag, final byte[] value) {
    putTag(tag);
    body.putBytes(position, value);
    position += value.length;
    end();
    return this;
  }



  /** UTCTimestamp field with millisecond precision. */
  public LegacyFixWriter putTimestamp(final int tag, final long epochNanos) {
    putTag(tag);
    position += FixTime.writeMillis(body, position, epochNanos);
    end();
    return this;
  }

  /**
   * Writes the complete message (BeginString, BodyLength, body, CheckSum) into {@code out}.
   *
   * @return total length written
   */
  public int finish(final MutableDirectBuffer out, final int offset) {
    int p = offset;
    out.putBytes(p, BEGIN);
    p += BEGIN.length;
    p += AsciiNumbers.writeNatural(out, p, position);
    out.putByte(p++, FixParser.SOH);
    out.putBytes(p, body, 0, position);
    p += position;
    int sum = 0;
    for (int i = offset; i < p; i++) {
      sum += out.getByte(i) & 0xFF;
    }
    out.putByte(p++, (byte) '1');
    out.putByte(p++, (byte) '0');
    out.putByte(p++, (byte) '=');
    p += AsciiNumbers.writePadded(out, p, sum & 0xFF, 3);
    out.putByte(p++, FixParser.SOH);
    return p - offset;
  }
}
