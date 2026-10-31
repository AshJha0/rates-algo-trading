package io.ratesalgo.fix.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

class CodecTest {

  private static UnsafeBuffer ascii(final String s) {
    return new UnsafeBuffer(s.replace('|', '\u0001').getBytes(StandardCharsets.US_ASCII));
  }

  private static long dec(final String s, final int scale) {
    final UnsafeBuffer b = ascii(s);
    return AsciiNumbers.parseDecimal(b, 0, b.capacity(), scale, 44);
  }

  private static String fmt(final long v, final int scale) {
    final UnsafeBuffer b = new UnsafeBuffer(new byte[64]);
    final int n = AsciiNumbers.writeDecimal(b, 0, v, scale);
    return b.getStringWithoutLengthAscii(0, n);
  }

  @Test
  void decimalsConvertExactlyToExponentMinusNine() {
    assertEquals(99_123_456_789L, dec("99.123456789", 9));
    assertEquals(99_500_000_000L, dec("99.5", 9));
    assertEquals(99_500_000_000L, dec("99.500000000000", 9));
    assertEquals(-250_000_000L, dec("-0.25", 9));
    assertEquals(4_250_000_000L, dec("+4.25", 9));
    assertEquals(100_000_000_000L, dec("1000000000", 2));
    assertEquals(7L, dec("7", 0));
    assertThrows(FixFormatException.class, () -> dec("99.1234567891", 9));
    assertThrows(FixFormatException.class, () -> dec("1.2.3", 9));
    assertThrows(FixFormatException.class, () -> dec("12a", 9));
    assertThrows(FixFormatException.class, () -> dec("-", 9));
    assertThrows(FixFormatException.class, () -> dec("99999999999", 9));
    assertThrows(FixFormatException.class, () -> AsciiNumbers.parseDecimal(ascii("1"), 0, 0, 9, 44));
    assertEquals("99.123456789", fmt(99_123_456_789L, 9));
    assertEquals("99.5", fmt(99_500_000_000L, 9));
    assertEquals("-0.25", fmt(-250_000_000L, 9));
    assertEquals("100", fmt(100_000_000_000L, 9));
    assertEquals("0", fmt(0, 9));
    assertThrows(IllegalArgumentException.class, () -> fmt(Long.MIN_VALUE, 9));
    assertThrows(
        IllegalArgumentException.class,
        () -> AsciiNumbers.writeNatural(new UnsafeBuffer(new byte[4]), 0, -1));
  }

  @Property
  void decimalRoundTrip(
      @ForAll @LongRange(min = -9_000_000_000_000_000L, max = 9_000_000_000_000_000L) final long v,
      @ForAll @IntRange(min = 0, max = 9) final int scale) {
    assertEquals(v, dec(fmt(v, scale), scale));
  }

  @Property
  void timestampsMatchJavaTime(
      @ForAll @LongRange(min = 0, max = 7_258_118_400_000L) final long millis) {
    final long nanos = millis * 1_000_000L;
    final UnsafeBuffer b = new UnsafeBuffer(new byte[32]);
    final int n = FixTime.writeMillis(b, 0, nanos);
    final String expected =
        DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS")
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(millis));
    assertEquals(expected, b.getStringWithoutLengthAscii(0, n));
    assertEquals(nanos, FixTime.parse(b, 0, n, 52));
  }

  @Test
  void timestampPrecisionsAndErrors() {
    final UnsafeBuffer s = ascii("20261102-14:30:05");
    assertEquals(1_793_629_805_000_000_000L, FixTime.parse(s, 0, s.capacity(), 52));
    final UnsafeBuffer micro = ascii("20261102-14:30:05.123456");
    assertEquals(1_793_629_805_123_456_000L, FixTime.parse(micro, 0, micro.capacity(), 52));
    final UnsafeBuffer nano = ascii("20261102-14:30:05.123456789");
    assertEquals(1_793_629_805_123_456_789L, FixTime.parse(nano, 0, nano.capacity(), 52));
    for (final String bad :
        new String[] {"2026110-14:30:05", "20261102 14:30:05", "20261302-14:30:05", "2026110x-14:30:05", "20261102-14:30:05,123"}) {
      final UnsafeBuffer b = ascii(bad);
      assertThrows(FixFormatException.class, () -> FixTime.parse(b, 0, b.capacity(), 52), bad);
    }
    final UnsafeBuffer pre = new UnsafeBuffer(new byte[32]);
    final int n = FixTime.writeMillis(pre, 0, -1_000_000L);
    assertEquals("19691231-23:59:59.999", pre.getStringWithoutLengthAscii(0, n));
  }

  private static UnsafeBuffer message(final FixWriter w) {
    final UnsafeBuffer out = new UnsafeBuffer(new byte[4096]);
    final int n = w.finish(out, 0);
    return new UnsafeBuffer(out.byteArray(), 0, n);
  }

  @Test
  void writerAndParserRoundTripWithGroups() {
    final FixWriter w = new FixWriter(1024);
    w.begin(MsgType.MARKET_DATA_INCREMENTAL)
        .putText(49, "V")
        .putText(56, "E")
        .put(34, 12)
        .putTimestamp(52, 1_793_629_805_123_000_000L)
        .put(268, 2)
        .putChar(279, (byte) '0')
        .putChar(269, (byte) '0')
        .putDecimal(270, 99_500_000_000L, 9)
        .putChar(279, (byte) '2')
        .putChar(269, (byte) '1')
        .putDecimal(270, 99_515_625_000L, 9)
        .putBool(43, false)
        .putChars(9503, (byte) 'T', (byte) 'A')
        .putAscii(58, new byte[] {'o', 'k'})
        .putAscii(1, ascii("ACCT"), 0, 4);
    final UnsafeBuffer msg = message(w);
    assertEquals(msg.capacity(), FixFramer.frame(msg, 0, msg.capacity()));
    final FixParser p = new FixParser();
    assertEquals(FixParser.OK, p.wrap(msg, 0, msg.capacity()));
    assertEquals(MsgType.MARKET_DATA_INCREMENTAL, p.msgType());
    assertEquals(12, p.getLong(34));
    final int first = p.indexOf(270);
    final int second = p.indexOf(270, first + 1);
    assertEquals(99_500_000_000L, p.getDecimalAt(first, 9));
    assertEquals(99_515_625_000L, p.getDecimalAt(second, 9));
    assertEquals(-1, p.indexOf(270, second + 1));
    assertEquals('2', p.getCharAt(p.indexOf(279, first)));
    assertEquals("TA", p.getString(9503));
    assertTrue(p.valueEquals(58, new byte[] {'o', 'k'}));
    assertFalse(p.valueEquals(58, new byte[] {'o', 'K'}));
    assertFalse(p.valueEquals(58, new byte[] {'o'}));
    assertFalse(p.isYes(43));
    assertEquals(1_793_629_805_123_000_000L, p.getTimestamp(52, 0));
    assertEquals(-5, p.getLong(999, -5));
    assertEquals(7, p.getDecimal(999, 9, 7));
    assertEquals((byte) 'x', p.getChar(999, (byte) 'x'));
    assertThrows(FixFormatException.class, () -> p.getLong(999));
    assertEquals(FixParser.hash("ACCT".getBytes(StandardCharsets.US_ASCII)), p.hashAt(p.indexOf(1)));
    final UnsafeBuffer copy = new UnsafeBuffer(new byte[8]);
    assertEquals(4, p.copyValueAt(p.indexOf(1), copy, 0));
    assertTrue(p.toString().startsWith("8=FIX.4.4|9="));
    assertEquals(msg.capacity(), p.messageLength());
    assertEquals(0, p.messageOffset());
    assertEquals(35, p.tagAt(2));
    assertEquals("", new FixParser().toString());
  }

  @Test
  void parserRejectsDamagedMessages() {
    final FixWriter w = new FixWriter(256);
    w.begin(MsgType.HEARTBEAT).putText(49, "V").putText(56, "E").put(34, 1);
    final UnsafeBuffer good = message(w);
    final String text = good.getStringWithoutLengthAscii(0, good.capacity());
    final FixParser p = new FixParser();
    assertEquals(FixParser.BAD_CHECKSUM, p.wrap(ascii(text.replace("49=V", "49=W")), 0, good.capacity()));
    final String longer = text.replace("49=V", "49=VV");
    assertEquals(FixParser.BAD_BODY_LENGTH, p.wrap(ascii(longer), 0, longer.length()));
    final String noType = "8=FIX.4.4|9=5|49=V|10=000|";
    assertEquals(FixParser.BAD_HEADER, p.wrap(ascii(noType), 0, noType.length()));
    final String bad = "8=FIX.4.4|9=5|35=0|4x9=V|10=000|";
    assertEquals(FixParser.MALFORMED, p.wrap(ascii(bad), 0, bad.length()));
    final String unterminated = "8=FIX.4.4|9=5|35=0|49=V";
    assertEquals(FixParser.MALFORMED, p.wrap(ascii(unterminated), 0, unterminated.length()));
    final String noTrailer = "8=FIX.4.4|9=5|35=0|49=V|";
    assertEquals(FixParser.BAD_CHECKSUM, p.wrap(ascii(noTrailer), 0, noTrailer.length()));
    final String badLength = "8=FIX.4.4|9=x|35=0|10=000|";
    assertEquals(FixParser.BAD_HEADER, p.wrap(ascii(badLength), 0, badLength.length()));
    final StringBuilder many = new StringBuilder("8=FIX.4.4|9=1|35=0|");
    for (int i = 0; i < FixParser.MAX_FIELDS; i++) {
      many.append("58=x|");
    }
    many.append("10=000|");
    assertEquals(FixParser.TOO_MANY_FIELDS, p.wrap(ascii(many.toString()), 0, many.length()));
  }

  @Test
  void framerHandlesPartialAndGarbage() {
    final FixWriter w = new FixWriter(256);
    w.begin(MsgType.HEARTBEAT).putText(49, "V");
    final UnsafeBuffer msg = message(w);
    for (int i = 0; i < msg.capacity(); i++) {
      assertEquals(0, FixFramer.frame(msg, 0, i), "prefix " + i);
    }
    assertEquals(FixFramer.GARBAGE, FixFramer.frame(ascii("xx8=FIX"), 0, 7));
    assertEquals(FixFramer.GARBAGE, FixFramer.frame(ascii("8=FIX.4.4|8=1|"), 0, 14));
    assertEquals(FixFramer.GARBAGE, FixFramer.frame(ascii("8=FIX.4.4|9=a|"), 0, 14));
    assertEquals(FixFramer.GARBAGE, FixFramer.frame(ascii("8=FIX.4.4|9=|"), 0, 13));
    assertEquals(FixFramer.GARBAGE, FixFramer.frame(ascii("8=" + "F".repeat(40)), 0, 42));
  }

  @Test
  void msgTypeText() {
    assertEquals("AJ", MsgType.toText(MsgType.QUOTE_RESPONSE));
    assertEquals("8", MsgType.toText(MsgType.EXECUTION_REPORT));
    assertEquals(MsgType.QUOTE_STATUS_REPORT, MsgType.fromText("AI"));
    assertEquals(MsgType.QUOTE_STATUS_REQUEST, MsgType.fromText("a"));
    assertThrows(IllegalArgumentException.class, () -> MsgType.fromText("ABC"));
    assertTrue(MsgType.isAdmin(MsgType.LOGON));
    assertTrue(MsgType.isAdmin(MsgType.SEQUENCE_RESET));
    assertFalse(MsgType.isAdmin(MsgType.QUOTE));
    assertEquals(44, new FixFormatException(44, "x").tag());
  }
}
