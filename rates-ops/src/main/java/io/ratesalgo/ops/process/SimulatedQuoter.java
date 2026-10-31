package io.ratesalgo.ops.process;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.journal.JournalWriter;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.KillSwitchEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.ops.http.OpsHttpServer;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * A stand-in for the quoting engine in the deployment rehearsal: it holds one live quote per
 * configured instrument, and the kill switch pulls them all and journals a KillSwitch message.
 * P3's quoting engine replaces it at integration through the same {@link OpsHttpServer.Control}
 * surface; the rehearsal then exercises the real thing.
 *
 * <p>The HTTP thread calls {@link #kill}; the methods are synchronized because this class is the
 * one place two threads meet, and it is not on the hot path.
 */
public final class SimulatedQuoter implements OpsHttpServer.Control {

  private final JournalWriter journal;
  private final Clock clock;
  private final int instruments;
  private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
  private final KillSwitchEncoder kill = new KillSwitchEncoder();
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private int liveQuotes;
  private boolean quoting;
  private boolean killed;
  private long frames;

  /** Creates a quoter for {@code instruments} instruments, quoting at once if {@code enabled}. */
  public SimulatedQuoter(final JournalWriter journal, final Clock clock, final List<int[]> instruments,
      final boolean enabled) {
    this.journal = journal;
    this.clock = clock;
    this.instruments = instruments.size();
    this.quoting = enabled;
    this.liveQuotes = enabled ? this.instruments : 0;
  }

  @Override
  public synchronized void kill(final int requestedBy) {
    if (killed) {
      return;
    }
    kill.wrapAndApplyHeader(buffer, 0, header).reason(KillReason.Manual).scopeId(0).requestedBy(requestedBy)
        .timestampNanos(clock.epochNanos());
    journal.append(KillSwitchEncoder.TEMPLATE_ID, KillSwitchEncoder.SCHEMA_VERSION, buffer, 0,
        MessageHeaderEncoder.ENCODED_LENGTH + kill.encodedLength());
    journal.sync();
    frames++;
    liveQuotes = 0;
    quoting = false;
    killed = true;
  }

  @Override
  public synchronized String statusJson() {
    return String.format(Locale.ROOT, "{\"quoting\":%s,\"killSwitch\":%s,\"liveQuotes\":%d,\"instruments\":%d}",
        quoting, killed, liveQuotes, instruments);
  }

  /** Quotes live now. */
  public synchronized int liveQuotes() {
    return liveQuotes;
  }

  /** True once the kill switch has been engaged. */
  public synchronized boolean killed() {
    return killed;
  }

  /** True while quoting. */
  public synchronized boolean quoting() {
    return quoting;
  }

  /** Frames this quoter appended. */
  public synchronized long frames() {
    return frames;
  }
}
