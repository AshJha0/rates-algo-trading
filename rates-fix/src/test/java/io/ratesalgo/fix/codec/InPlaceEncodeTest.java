package io.ratesalgo.fix.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

/**
 * The FIX send path without the body copy (review finding L11): the header is written into room
 * reserved in front of the body, CheckSum is summed eight bytes at a time and the date part of a
 * timestamp is cached; every one gives the bytes the plain construction gives.
 */
class InPlaceEncodeTest {

  /** The message built the slow, obvious way: header, body, byte-by-byte checksum. */
  private static String expected(final String body) {
    final String head = "8=FIX.4.4\u00019=" + body.length() + "\u0001";
    int sum = 0;
    for (final byte b : (head + body).getBytes(StandardCharsets.US_ASCII)) {
      sum += b & 0xFF;
    }
    return head + body + String.format(java.util.Locale.ROOT, "10=%03d\u0001", sum & 0xFF);
  }

  private static String message(final FixWriter w, final int length) {
    return w.buffer().getStringWithoutLengthAscii(w.messageOffset(), length);
  }

  @Test
  void theFinishedMessageIsTheObviousOneAndBothFinishesAgree() {
    final FixWriter w = new FixWriter(64 * 1024);
    w.begin(MsgType.QUOTE).put(FixTags.MSG_SEQ_NUM, 42).putText(FixTags.SYMBOL, "USD-5Y");
    final int n = w.finish();
    final String body = "35=S\u000134=42\u000155=USD-5Y\u0001";
    assertEquals(expected(body), message(w, n));
    assertEquals(n, w.messageLength());
    assertEquals(body.length(), w.bodyLength());
    // 64 KiB needs five digits of BodyLength; a 33-byte body uses two, so the message starts three in
    assertEquals(3, w.messageOffset());

    final UnsafeBuffer out = new UnsafeBuffer(new byte[256]);
    w.begin(MsgType.QUOTE).put(FixTags.MSG_SEQ_NUM, 42).putText(FixTags.SYMBOL, "USD-5Y");
    assertEquals(n, w.finish(out, 5));
    assertEquals(expected(body), out.getStringWithoutLengthAscii(5, n));
  }

  @Property(tries = 300)
  void anyBodyLengthGivesTheObviousMessage(@ForAll @IntRange(min = 0, max = 20_000) final int pad) {
    final FixWriter w = new FixWriter(64 * 1024);
    final String text = "x".repeat(pad);
    w.begin(MsgType.HEARTBEAT).putText(FixTags.TEXT, text);
    final int n = w.finish();
    assertEquals(expected("35=0\u000158=" + text + "\u0001"), message(w, n));
  }

  @Property(tries = 300)
  void theWordWiseChecksumIsTheByteSum(
      @ForAll @Size(max = 3000) final byte[] bytes, @ForAll @IntRange(min = 0, max = 7) final int skew) {
    final UnsafeBuffer b = new UnsafeBuffer(new byte[bytes.length + skew]);
    b.putBytes(skew, bytes);
    int sum = 0;
    for (final byte x : bytes) {
      sum += x & 0xFF;
    }
    assertEquals(sum & 0xFF, FixWriter.checksum(b, skew, skew + bytes.length));
  }

  @Test
  void digitsCountsDecimalDigits() {
    assertEquals(1, FixWriter.digits(0));
    assertEquals(1, FixWriter.digits(9));
    assertEquals(2, FixWriter.digits(10));
    assertEquals(5, FixWriter.digits(65_536));
    assertEquals(10, FixWriter.digits(Integer.MAX_VALUE));
  }

  @Test
  void wipeClearsHeaderBodyAndTrailer() {
    final FixWriter w = new FixWriter(1024);
    w.begin(MsgType.LOGON).putText(FixTags.PASSWORD, "secret-password");
    final int n = w.finish();
    assertTrue(message(w, n).contains("secret-password"));
    w.wipe();
    for (int i = 0; i < 128; i++) {
      assertEquals(0, w.buffer().getByte(i), "byte " + i);
    }
    assertEquals(0, w.messageLength());
    assertEquals(0, w.bodyLength());
  }

  @Property(tries = 500)
  void theCachedDateGivesTheSameBytesAcrossDays(
      @ForAll @LongRange(min = 0, max = 4_102_444_800_000_000_000L) final long first,
      @ForAll @LongRange(min = -200_000_000_000_000L, max = 200_000_000_000_000L) final long step) {
    final FixTime.DayCache cache = new FixTime.DayCache();
    final UnsafeBuffer cached = new UnsafeBuffer(new byte[32]);
    final UnsafeBuffer plain = new UnsafeBuffer(new byte[32]);
    long t = first;
    for (int i = 0; i < 4; i++) {
      final long stamp = Math.max(0, t);
      assertEquals(FixTime.MILLIS_LENGTH, FixTime.writeMillis(cached, 0, stamp, cache));
      FixTime.writeMillis(plain, 0, stamp);
      assertEquals(plain.getStringWithoutLengthAscii(0, 21), cached.getStringWithoutLengthAscii(0, 21));
      t += i % 2 == 0 ? 1_000_000L : step; // same day, then possibly another
    }
  }
}
