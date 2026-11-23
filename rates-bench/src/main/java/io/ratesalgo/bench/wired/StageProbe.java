package io.ratesalgo.bench.wired;

import io.ratesalgo.core.LoopProbe;
import io.ratesalgo.fix.gateway.GatewayProbe;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.agrona.DirectBuffer;

/**
 * The stage stamps of each RFQ inside the desk, by RFQ id, from {@link System#nanoTime()} on the
 * thread that passes the stage. One array per stage, written by one thread each (the D2C session
 * thread or the loop thread) and read by the benchmark after the run; a stage seen twice for one
 * RFQ keeps the first stamp. Nothing allocates after construction.
 */
final class StageProbe implements LoopProbe, GatewayProbe {

  /** Stage columns, in path order. */
  static final int PUBLISHED = 0;

  static final int LOOP_READ = 1;
  static final int JOURNALED = 2;
  static final int HANDLED = 3;
  static final int DEQUEUED = 4;
  static final int ENCODED = 5;
  static final int WRITTEN = 6;
  static final int STAGES = 7;

  private static final int RFQ_ID_OFFSET =
      MessageHeaderDecoder.ENCODED_LENGTH + QuoteRequestDecoder.rfqIdEncodingOffset();

  private final long[][] stamps = new long[STAGES][];
  private final long firstId;
  private final int capacity;

  StageProbe(final long firstId, final int capacity) {
    this.firstId = firstId;
    this.capacity = capacity;
    for (int s = 0; s < STAGES; s++) {
      stamps[s] = new long[capacity];
    }
  }

  @Override
  public void onStage(final int stage, final int templateId, final DirectBuffer buffer, final int offset) {
    if (templateId == QuoteRequestDecoder.TEMPLATE_ID) {
      final long rfqId = buffer.getLong(offset + RFQ_ID_OFFSET, ByteOrder.LITTLE_ENDIAN);
      stamp(LOOP_READ + stage, rfqId);
    }
  }

  @Override
  public void onStage(final int stage, final long rfqId) {
    stamp(stage == GatewayProbe.REQUEST_PUBLISHED ? PUBLISHED : DEQUEUED + stage - GatewayProbe.QUOTE_DEQUEUED, rfqId);
  }

  private void stamp(final int column, final long rfqId) {
    final long i = rfqId - firstId;
    if (i >= 0 && i < capacity) {
      final long[] c = stamps[column];
      if (c[(int) i] == 0) {
        c[(int) i] = System.nanoTime();
      }
    }
  }

  /** The stamp of stage {@code column} for RFQ index {@code i}, or 0 if it was not seen. */
  long at(final int column, final int i) {
    return stamps[column][i];
  }

  /** Clears every stamp. */
  void clear() {
    for (final long[] c : stamps) {
      Arrays.fill(c, 0L);
    }
  }
}
