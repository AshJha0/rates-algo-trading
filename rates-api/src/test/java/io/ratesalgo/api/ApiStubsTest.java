package io.ratesalgo.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.messages.sbe.HeartbeatEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.Side;
import java.nio.ByteBuffer;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

class ApiStubsTest {

  @Property(tries = 500)
  void keyRateSplitIsAPartitionOfUnity(@ForAll @DoubleRange(min = 0.01, max = 50.0) final double years) {
    final double[] w = new double[KeyRateTenors.COUNT];
    KeyRateTenors.split(years, w);
    double sum = 0.0;
    double centre = 0.0;
    for (int k = 0; k < w.length; k++) {
      assertTrue(w[k] >= 0.0);
      sum += w[k];
      centre += w[k] * KeyRateTenors.years(k);
    }
    assertEquals(1.0, sum, 1e-12);
    final double clamped = Math.max(1.0, Math.min(30.0, years));
    assertEquals(clamped, centre, 1e-9, "weights preserve the maturity inside the pillar range");
  }

  @Test
  void keyRateTenorsLabelsAndValidation() {
    assertEquals("10Y", KeyRateTenors.label(5));
    assertEquals(30.0, KeyRateTenors.years(7), 0.0);
    final double[] w = new double[KeyRateTenors.COUNT];
    KeyRateTenors.split(6.0, w);
    assertEquals(0.5, w[3], 1e-12);
    assertEquals(0.5, w[4], 1e-12);
    assertThrows(IllegalArgumentException.class, () -> KeyRateTenors.split(1.0, new double[3]));
    assertThrows(IllegalArgumentException.class, () -> KeyRateTenors.split(0.0, w));
    assertThrows(IllegalArgumentException.class, () -> KeyRateTenors.split(Double.POSITIVE_INFINITY, w));
  }

  @Test
  void decimal9RoundingRules() {
    assertEquals(99_123_456_789L, Decimal9.round(99.123456789));
    assertEquals(99.123456789, Decimal9.toDouble(99_123_456_789L), 1e-12);
    assertEquals(1_000_000_000L, Decimal9.floor(1.0000000004));
    assertEquals(1_000_000_001L, Decimal9.ceil(1.0000000004));
    assertEquals(4_250_000_000L, Decimal9.floor(4.25));
    assertEquals(4_250_000_000L, Decimal9.ceil(4.25));
    assertThrows(IllegalArgumentException.class, () -> Decimal9.round(Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> Decimal9.floor(1e10));
    assertEquals(Long.MAX_VALUE, Decimal9.saturating(1e12));
    assertEquals(Long.MIN_VALUE + 1, Decimal9.saturating(-1e12));
    assertEquals(Long.MIN_VALUE, Decimal9.saturating(Double.NaN));
    assertEquals(2_500_000_000L, Decimal9.saturating(2.5));
    assertEquals(1_000_000_000L, Decimal9.ONE);
  }

  @Test
  void dv01VectorArithmetic() {
    final Dv01Vector a = new Dv01Vector(CurrencyCode.USD);
    a.set(0, 10.0);
    a.add(5, -30.0);
    assertEquals(-20.0, a.parallel(), 0.0);
    assertEquals(40.0, a.gross(), 0.0);
    final Dv01Vector b = new Dv01Vector(CurrencyCode.EUR);
    b.copyFrom(a);
    assertEquals(CurrencyCode.USD, b.currency());
    b.addScaled(a, 2.0);
    assertEquals(-90.0, b.get(5), 0.0);
    final double[] out = new double[KeyRateTenors.COUNT];
    b.copyTo(out);
    assertEquals(30.0, out[0], 0.0);
    assertTrue(b.toString().startsWith("USD [1Y=30.00"));
    b.currency(CurrencyCode.GBP);
    assertThrows(IllegalArgumentException.class, () -> b.addScaled(a, 1.0));
    b.clear();
    assertEquals(0.0, b.gross(), 0.0);
  }

  @Test
  void currencies() {
    assertTrue(CurrencyCode.USD.matches((byte) 'U', (byte) 'S', (byte) 'D'));
    assertFalse(CurrencyCode.EUR.matches((byte) 'U', (byte) 'S', (byte) 'D'));
    assertEquals(CurrencyCode.GBP, CurrencyCode.ofIndex(2));
    assertEquals(1, CurrencyCode.ofIndex(1).index());
    assertThrows(IllegalArgumentException.class, () -> CurrencyCode.ofIndex(3));
    assertEquals(2, ProductType.SWAP.index());
  }

  @Test
  void instrumentsAndDirectory() {
    final InMemoryInstrumentDirectory d = new InMemoryInstrumentDirectory();
    final Instrument swap = Instrument.swap(3, "S", CurrencyCode.USD, 10.0, 8.2, 2, 0.9, 5_000_000_000L);
    assertEquals(0, d.add(Instrument.bond(1, "B", CurrencyCode.USD, 10.0, 100L)));
    assertEquals(1, d.add(Instrument.future(2, "F", CurrencyCode.USD, 7.0, 100L)));
    assertEquals(2, d.add(swap));
    assertThrows(IllegalArgumentException.class, () -> d.add(Instrument.bond(1, "B", CurrencyCode.USD, 10.0, 100L)));
    assertThrows(IllegalArgumentException.class, () -> Instrument.bond(0, "Z", CurrencyCode.USD, 1.0, 1L));
    assertThrows(IllegalArgumentException.class, () -> Instrument.bond(9, "Z", CurrencyCode.USD, 0.0, 1L));
    assertEquals(3, d.size());
    assertEquals(swap, d.byId(3));
    assertNull(d.byId(4));
    assertEquals(-1, d.indexOf(4));
    assertEquals(swap, d.byIndex(2));
    assertEquals(0.082, swap.quoteValue(), 1e-15);
    assertEquals(2, swap.signalInstrumentId());
    assertEquals(0.9, swap.signalBeta(), 0.0);
    assertEquals(50_000_000.0, swap.major(5_000_000_000L), 0.0);
    assertEquals(100L, swap.minorPerMajor());
    assertEquals("S", swap.symbol());
    assertEquals(ProductType.SWAP, swap.type());
    assertEquals(10.0, swap.tenorYears(), 0.0);
    assertEquals(5_000_000_000L, swap.typicalQuantity());
    assertTrue(swap.toString().contains("SWAP"));
  }

  @Test
  void fairValueBookAndSensitivityStubs() {
    final InMemoryInstrumentDirectory d = new InMemoryInstrumentDirectory();
    d.add(Instrument.bond(1, "B", CurrencyCode.USD, 6.0, 100L));
    d.add(Instrument.bond(2, "C", CurrencyCode.EUR, 2.0, 100L));
    final InMemoryFairValueSource fv = new InMemoryFairValueSource();
    assertEquals(FairValueSource.NO_VALUE, fv.fairMid(1));
    fv.set(1, 5L, 6L);
    assertEquals(5L, fv.fairMid(1));
    assertEquals(6L, fv.referenceNanos(1));
    fv.remove(1);
    assertEquals(FairValueSource.NO_VALUE, fv.referenceNanos(1));

    final InMemoryBookSource book = new InMemoryBookSource(1);
    assertEquals(FairValueSource.NO_VALUE, book.bestBid(1));
    assertEquals(0L, book.askSize(1));
    book.set(1, 10L, 2L, 12L, 3L, 99L);
    assertEquals(10L, book.bestBid(1));
    assertEquals(12L, book.bestAsk(1));
    assertEquals(2L, book.bidSize(1));
    assertEquals(3L, book.askSize(1));
    assertEquals(99L, book.updateNanos(1));
    assertEquals(FairValueSource.NO_VALUE, book.bestAsk(2));
    assertEquals(0L, book.bidSize(2));
    assertEquals(FairValueSource.NO_VALUE, book.updateNanos(2));
    assertThrows(IllegalStateException.class, () -> book.set(2, 1L, 1L, 2L, 1L, 1L));

    final ParametricSensitivityProvider s = new ParametricSensitivityProvider(d);
    final Dv01Vector v = new Dv01Vector(CurrencyCode.GBP);
    assertFalse(s.pv01(1, 100L, v), "undefined");
    assertFalse(s.pv01(9, 100L, v), "unknown");
    s.defineAtTenor(1, -5e-4, 0.01);
    assertTrue(s.pv01(1, 100_000_000L, v));
    assertEquals(CurrencyCode.USD, v.currency());
    assertEquals(-500.0, v.parallel(), 1e-9);
    assertEquals(-250.0, v.get(3), 1e-9);
    s.shiftCurve(CurrencyCode.USD, 10.0);
    assertEquals(10.0, s.curveShiftBp(CurrencyCode.USD), 0.0);
    s.pv01(1, 100_000_000L, v);
    assertEquals(-450.0, v.parallel(), 1e-9);
    s.defineLadder(2, new double[] {1, 2, 3, 4, 5, 6, 7, 8}, 0.0);
    s.pv01(2, 100L, v);
    assertArrayEquals(new double[] {1, 2, 3, 4, 5, 6, 7, 8}, ladder(v), 0.0);
    assertThrows(IllegalArgumentException.class, () -> s.defineLadder(2, new double[2], 0.0));
    assertThrows(IllegalArgumentException.class, () -> s.defineAtTenor(9, 1.0, 0.0));
  }

  @Test
  void sinks() {
    final UnsafeBuffer b = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
    new HeartbeatEncoder().wrapAndApplyHeader(b, 0, new MessageHeaderEncoder()).sourceId(3).timestampNanos(4L);
    final int len = MessageHeaderEncoder.ENCODED_LENGTH + HeartbeatEncoder.BLOCK_LENGTH;
    final CountingOutboundSink counting = new CountingOutboundSink();
    counting.send(b, 0, len);
    assertEquals(1L, counting.count(HeartbeatEncoder.TEMPLATE_ID));
    assertEquals(1L, counting.total());
    final RecordingOutboundSink rec = new RecordingOutboundSink();
    final int[] heard = new int[1];
    rec.listener((t, buf, o, l) -> heard[0] = t);
    rec.send(b, 0, len);
    assertEquals(1, heard[0]);
    assertEquals(1, rec.size());
    assertEquals(1, rec.templateId(0));
    assertEquals(1, rec.count(1));
    assertEquals(3, rec.message(0).getInt(8));
    rec.keep(false);
    rec.send(b, 0, len);
    assertEquals(1, rec.size());
    rec.clear();
    assertEquals(0, rec.size());

    final RecordingHedgeOrderSink h = new RecordingHedgeOrderSink();
    assertTrue(h.submit(1L, 2, Side.Buy, 3L, 4L, 5L, 6L));
    h.cancel(1L);
    h.cancelAll();
    assertEquals(1, h.parents().size());
    assertEquals(Side.Buy, h.parents().get(0).side());
    assertEquals(1L, h.cancelled().get(0).longValue());
    assertEquals(1, h.cancelAllCount());
    h.accepting(false);
    assertFalse(h.submit(2L, 2, Side.Sell, 3L, 4L, 5L, 6L));
    h.clear();
    assertEquals(0, h.parents().size());
  }

  private static double[] ladder(final Dv01Vector v) {
    final double[] out = new double[KeyRateTenors.COUNT];
    v.copyTo(out);
    return out;
  }
}
