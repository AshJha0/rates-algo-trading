package io.ratesalgo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.journal.SegmentedJournal;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

class LoopReplayTest {

  @TempDir Path dir;

  /** A live run with timers, backpressure, a disconnect and several journal segments. */
  private TestRig liveRun(final QuoteApp app) {
    return liveRun(app, null);
  }

  /** As {@link #liveRun(QuoteApp)} with journals in {@code mode}, or plain ones if it is null. */
  private TestRig liveRun(final QuoteApp app, final JournalMode mode) {
    final TestRig rig = new TestRig(dir, app, 4096, RingBufferContract.MIN_CAPACITY, mode);
    long rfq = 1;
    for (int round = 0; round < 12; round++) {
      for (int i = 0; i < 40; i++) {
        rig.offerRequest(rfq++);
      }
      rig.cycle();
      rig.advance(700_000L);
      rig.cycle();
      if (round % 3 == 0) {
        rig.drain(0);
      }
      rig.drain(1);
    }
    rig.publisher.offer(
        rig.scratch,
        0,
        ControlMessages.encodeGatewayStatus(rig.scratch, 0, 1L, 0, ControlMessages.STATUS_DISCONNECTED));
    rig.advance(5_000_000L);
    rig.cycle();
    rig.close();
    return rig;
  }

  @ParameterizedTest
  @ValueSource(strings = {"plain", "DIRECT", "RING"})
  void replayReproducesTheOutboundStreamExactly(final String journals) {
    final QuoteApp live = new QuoteApp(true);
    final TestRig rig = liveRun(live, "plain".equals(journals) ? null : JournalMode.valueOf(journals));
    assertTrue(live.backpressureEvents > 0, "run should hit backpressure");
    assertTrue(live.timersFired > 0);
    assertEquals(1L, live.statusEvents);
    assertTrue(SegmentedJournal.segmentIndices(dir, "in").length > 1, "inbound journal should roll");

    final QuoteApp replayed = new QuoteApp(true);
    final ReplayResult r = rig.replay(replayed);
    assertTrue(r.matches(), r.toString());
    assertEquals(rig.loop.counters().get(LoopCounters.OUTBOUND), r.outboundProduced());
    assertEquals(rig.recordedOutboundHash(), r.recordedHash());
    assertEquals(live.quotes, replayed.quotes);
    assertEquals(live.timersFired, replayed.timersFired);
    assertEquals(live.backpressureEvents, replayed.backpressureEvents);
    assertFalse(r.stoppedOnError());
  }

  @Test
  void aChangedHandlerIsReportedAtItsFirstDifferentMessage() {
    final TestRig rig = liveRun(new QuoteApp(true));
    final ReplayResult r = rig.replay(new QuoteApp(true, 3_000_000L));
    assertFalse(r.matches());
    assertEquals(1L, r.firstMismatch());
  }

  @Test
  void aHandlerThatProducesLessIsReportedAfterItsLastMessage() {
    final TestRig rig = liveRun(new QuoteApp(true));
    final ReplayResult r = rig.replay(new QuoteApp(false));
    assertFalse(r.matches());
    assertTrue(r.firstMismatch() > 0);
    assertTrue(r.outboundRecorded() > 0);
  }

  @Test
  void replayStopsAtAJournaledLoopError() {
    final QuoteApp app = new QuoteApp(false);
    final TestRig rig = new TestRig(dir, app, 1 << 16, RingBufferContract.MIN_CAPACITY);
    rig.offerRequest(1);
    rig.cycle();
    rig.loop.recordError(new IllegalStateException("handler failed"));
    rig.close();
    final ReplayResult r = rig.replay(new QuoteApp(false));
    assertTrue(r.stoppedOnError());
    assertTrue(r.matches());
    assertEquals(2L, r.inboundFrames());
  }

  @Test
  void aFactoryMustUseTheBuilderItIsGiven() {
    final TestRig rig = new TestRig(dir, new QuoteApp(false), 1 << 16, RingBufferContract.MIN_CAPACITY);
    rig.offerRequest(1);
    rig.cycle();
    rig.close();
    final LoopFactory rogue = b -> EventLoop.builder(b.clock()).build();
    assertThrows(IllegalStateException.class, () -> rig.replay(rogue));
  }

  @Test
  void emptyJournalsReplayToAMatch() {
    final TestRig rig = new TestRig(dir, new QuoteApp(false), 1 << 16, RingBufferContract.MIN_CAPACITY);
    rig.close();
    final ReplayResult r = rig.replay(new QuoteApp(false));
    assertTrue(r.matches());
    assertEquals(0L, r.inboundFrames());
  }
}
