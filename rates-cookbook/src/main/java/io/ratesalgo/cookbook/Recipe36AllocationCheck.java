package io.ratesalgo.cookbook;

import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.QuoteDecoder;
import io.ratesalgo.messages.sbe.QuoteEncoder;
import java.lang.management.ManagementFactory;
import org.agrona.concurrent.UnsafeBuffer;

/** COOKBOOK.md recipe 36: check that a hot path allocates nothing, with the thread's byte counter. */
public final class Recipe36AllocationCheck {

  private static final int WINDOW = 1_000_000;
  private static final int WINDOWS = 3;
  private static final long LATER_WINDOW_BOUND = 1024L;

  private static final UnsafeBuffer BUFFER = new UnsafeBuffer(new byte[128]);
  private static final MessageHeaderEncoder HEADER_OUT = new MessageHeaderEncoder();
  private static final MessageHeaderDecoder HEADER_IN = new MessageHeaderDecoder();
  private static final QuoteEncoder ENCODER = new QuoteEncoder();
  private static final QuoteDecoder DECODER = new QuoteDecoder();
  private static long checksum;
  private static String lastLine = "";

  private Recipe36AllocationCheck() {}

  /** The hot path: encode a Quote into a reused buffer and decode it back, flyweights only. */
  static void flyweightQuote(final long i) {
    ENCODER.wrapAndApplyHeader(BUFFER, 0, HEADER_OUT).venueId(101).instrumentId(1008)
        .bidQuantity(1_000_000_000L).askQuantity(1_000_000_000L).validUntilNanos(i).sentNanos(i);
    ENCODER.bidPrice().mantissa(99_500_000_000L + i);
    ENCODER.askPrice().mantissa(99_515_625_000L + i);
    HEADER_IN.wrap(BUFFER, 0);
    DECODER.wrapAndApplyHeader(BUFFER, 0, HEADER_IN);
    checksum += DECODER.askPrice().mantissa() - DECODER.bidPrice().mantissa();
  }

  /** The same quote logged the convenient way: a String per message. */
  static void stringQuote(final long i) {
    final String line = "quote venue=101 instrument=1008 bid=" + (99_500_000_000L + i) + " ask=" + (99_515_625_000L + i);
    lastLine = line; // escapes, so the JIT cannot remove the allocation
  }

  interface Path {
    void run(long i);
  }

  /** Warm up, then measure consecutive windows; returns the bytes this thread allocated in each. */
  static long[] measure(final Path path) {
    final com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    final long thread = Thread.currentThread().threadId();
    mx.getThreadAllocatedBytes(thread); // the first call may allocate; keep it outside the windows
    long i = 0;
    for (; i < WINDOW; i++) {
      path.run(i);
    }
    final long[] bytes = new long[WINDOWS];
    for (int w = 0; w < WINDOWS; w++) {
      final long before = mx.getThreadAllocatedBytes(thread);
      for (final long end = i + WINDOW; i < end; i++) {
        path.run(i);
      }
      bytes[w] = mx.getThreadAllocatedBytes(thread) - before;
    }
    return bytes;
  }

  public static void main(final String[] args) {
    final long[] hot = measure(Recipe36AllocationCheck::flyweightQuote);
    System.out.println("flyweight encode and decode, " + WINDOWS + " windows of " + WINDOW + " quotes after "
        + WINDOW + " warm-up");
    System.out.println("  last window allocated " + hot[WINDOWS - 1] + " bytes");
    boolean bounded = true;
    for (int w = 1; w < WINDOWS; w++) {
      bounded &= hot[w] <= LATER_WINDOW_BOUND;
    }
    System.out.println("  every window after the first within " + LATER_WINDOW_BOUND + " bytes: " + bounded);
    System.out.println("  quoted spread checksum " + (checksum == (long) (WINDOWS + 1) * WINDOW * 15_625_000L));

    final long[] cold = measure(Recipe36AllocationCheck::stringQuote);
    System.out.println("String per quote, same windows");
    System.out.println("  last window allocated more than one byte per quote: " + (cold[WINDOWS - 1] > WINDOW));
    System.out.println("  last line: " + lastLine);
  }
}
