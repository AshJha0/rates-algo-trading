package io.ratesalgo.fix;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.fix.codec.FixParser;
import io.ratesalgo.fix.codec.FixTags;
import io.ratesalgo.fix.codec.FixWriter;
import io.ratesalgo.fix.session.FixSession;
import io.ratesalgo.fix.session.FixTransport;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/** A counterparty driven by the test: captures what the engine sends and writes what it receives. */
public final class ScriptedVenue implements FixTransport {

  public final List<String> sent = new ArrayList<>();
  public boolean disconnected;
  public final ManualClock clock;
  private final FixWriter writer = new FixWriter(8192);
  private final UnsafeBuffer out = new UnsafeBuffer(ByteBuffer.allocateDirect(8300));
  private final FixParser parser = new FixParser();
  private long seq = 1;
  private boolean record = true;
  private long sentCount;

  public ScriptedVenue(final ManualClock clock) {
    this.clock = clock;
  }

  /** Stops recording sent messages as strings (allocation test). */
  public void quiet() {
    record = false;
  }

  public long sentCount() {
    return sentCount;
  }

  @Override
  public void send(final DirectBuffer buffer, final int offset, final int length) {
    sentCount++;
    if (record) {
      if (parser.wrap(buffer, offset, length) != FixParser.OK) {
        throw new AssertionError("engine sent a malformed message");
      }
      sent.add(parser.toString());
    }
  }

  @Override
  public void disconnect() {
    disconnected = true;
  }

  public String last() {
    return sent.get(sent.size() - 1);
  }

  public long nextSeq() {
    return seq;
  }

  public FixWriter begin(final int msgType) {
    return beginSeq(msgType, seq++, false);
  }

  public FixWriter beginSeq(final int msgType, final long msgSeq, final boolean possDup) {
    writer
        .begin(msgType)
        .putText(FixTags.SENDER_COMP_ID, "VENUE")
        .putText(FixTags.TARGET_COMP_ID, "ENGINE")
        .put(FixTags.MSG_SEQ_NUM, msgSeq);
    if (possDup) {
      writer.putBool(FixTags.POSS_DUP_FLAG, true);
    }
    writer.putTimestamp(FixTags.SENDING_TIME, clock.epochNanos());
    return writer;
  }

  public void skip() {
    seq++;
  }

  /** Finishes the message and hands it to the session. */
  public void deliver(final FixSession session) {
    final int length = writer.finish(out, 0);
    session.onMessage(out, 0, length);
  }

  /** Finishes the message into the scratch buffer and returns its length (for corruption tests). */
  public int finish() {
    return writer.finish(out, 0);
  }

  public UnsafeBuffer buffer() {
    return out;
  }
}
