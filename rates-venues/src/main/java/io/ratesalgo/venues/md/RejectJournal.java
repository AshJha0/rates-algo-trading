package io.ratesalgo.venues.md;

import io.ratesalgo.fix.gateway.MessageSink;
import io.ratesalgo.messages.sbe.MarketDataRejectEncoder;
import io.ratesalgo.messages.sbe.MdRejectReason;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import org.agrona.concurrent.UnsafeBuffer;

/** Encodes {@code MarketDataReject} (template 40) and hands it to a sink such as the journal. */
public final class RejectJournal {

  private final MessageSink sink;
  private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[128]);
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final MarketDataRejectEncoder encoder = new MarketDataRejectEncoder();
  private final long[] counts = new long[16];

  /** Creates a journal writing to the sink. */
  public RejectJournal(final MessageSink sink) {
    this.sink = sink;
  }

  /** Records one rejection. */
  public void reject(
      final int sourceId,
      final int instrumentId,
      final MdRejectReason reason,
      final long bid,
      final long ask,
      final long reference,
      final long sourceSeq,
      final long sourceNanos,
      final long nowNanos) {
    encoder
        .wrapAndApplyHeader(buffer, 0, header)
        .sourceId(sourceId)
        .instrumentId(instrumentId)
        .reason(reason)
        .sourceSeqNo(sourceSeq)
        .sourceNanos(sourceNanos)
        .timestampNanos(nowNanos);
    encoder.bidPrice().mantissa(bid);
    encoder.askPrice().mantissa(ask);
    encoder.referencePrice().mantissa(reference);
    counts[reason.value()]++;
    sink.offer(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength());
  }

  /** Rejections recorded with a reason. */
  public long count(final MdRejectReason reason) {
    return counts[reason.value()];
  }

  /** All rejections recorded. */
  public long total() {
    long t = 0;
    for (final long c : counts) {
      t += c;
    }
    return t;
  }
}
