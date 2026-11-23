package io.ratesalgo.core;

import io.ratesalgo.common.time.TimerWheel;
import io.ratesalgo.messages.sbe.HeartbeatDecoder;
import io.ratesalgo.messages.sbe.HeartbeatEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.RingBuffer;

/**
 * One instrument group's single-writer event loop (API_SPEC.md Section 9).
 *
 * <p>Each call to {@link #doWork()} is one duty cycle:
 *
 * <ol>
 *   <li>latch the clock ({@link EventClock#latch()});
 *   <li>drain each inbound ring up to the read limit: check the SBE header, append the message to
 *       the inbound journal, dispatch it to the handler registered for its template id, then raise
 *       any backpressure the handler caused;
 *   <li>poll the timer wheel; if any timer fired, append a clock tick (a Heartbeat with source id
 *       0) to the inbound journal and raise any backpressure the timers caused;
 *   <li>call each agent, raising backpressure after each.
 * </ol>
 *
 * <p>Handlers send with {@link #publish}: the loop appends the message to the outbound journal and
 * writes it to the gateway's ring. A full ring drops the message, counts it and raises a {@link
 * BackpressureEvent} after the handler returns; the event is journaled as an input so replay raises
 * it at the same point. Template ids named to {@link Builder#retryOnDrop} (cancels and withdrawals)
 * are not lost with the drop: the loop keeps a copy and writes it to the ring at the start of later
 * duty cycles until the gateway has room. The retry touches the ring only, never the journals or a
 * handler, so replay is unaffected.
 *
 * <p>Conflation ({@link Builder#conflate}, off unless asked for; DECISIONS.md DR-R6-L05). A level
 * input such as a curve quote, whose next value supersedes the last, can be held back instead of
 * dispatched where it stands in the ring: the loop keeps only the newest per key, dispatches the
 * rest of the ring first, and then at most {@link Builder#conflatedPerCycle} held inputs per duty
 * cycle. A held input is journaled when it is dispatched, not when it is read, and one that a newer
 * one replaced is never journaled (it is counted in {@link LoopCounters#CONFLATED}); the inbound
 * journal therefore holds exactly what the handlers saw, in the order they saw it, and replay
 * reproduces it. The cost of the input's handler (a curve rebuild and reprice) then stands in front
 * of at most one duty cycle's worth of other inputs: an RFQ or a KillSwitch waits for at most
 * the number of held inputs dispatched per cycle, not for every curve input queued before it.
 *
 * <p>In replay ({@link LoopReplay}) the same handlers receive the journaled inbound frames, clock
 * ticks poll the timers, backpressure frames reach the listener, and outbound messages go to a sink
 * that hashes them and compares with the recorded outbound journal.
 *
 * <p>Not thread-safe by design: every method except {@link #counters()} is called on the loop
 * thread only.
 */
public final class EventLoop {

  /** Heartbeat source id reserved for the loop's clock tick; no gateway may publish it. */
  public static final int CLOCK_TICK_SOURCE_ID = 0;

  /** Messages read from each inbound ring per duty cycle, by default. */
  public static final int DEFAULT_READ_LIMIT = 256;

  private static final int TEMPLATE_IDS = 1 << 16;
  private static final int SCHEMA_ID = HeartbeatDecoder.SCHEMA_ID;
  private static final int SCHEMA_VERSION = HeartbeatDecoder.SCHEMA_VERSION;
  private static final int HEADER = RingBufferContract.SBE_HEADER_LENGTH;

  private final EventClock clock;
  private final TimerWheel timers;
  private final RingBuffer[] inbound;
  private final RingBuffer[] outbound;
  private final JournalSink inboundJournal;
  /** The outbound journal; during {@link LoopRecovery} the sink that checks what replay produces. */
  private JournalSink outboundJournal;
  private final int readLimit;
  private final boolean replay;
  private final MessageHandler[] handlers = new MessageHandler[TEMPLATE_IDS];
  private final LoopCounters counters = new LoopCounters();
  private final BackpressureEvent backpressureEvent = new BackpressureEvent();
  private final long[] pendingDropped;
  private final int[] pendingFirstTemplate;
  private final UnsafeBuffer scratch = new UnsafeBuffer(ByteBuffer.allocateDirect(512));
  private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
  private final HeartbeatEncoder heartbeatEncoder = new HeartbeatEncoder();
  private final org.agrona.concurrent.MessageHandler ringHandler = this::onRingMessage;
  /** Template ids kept for retry when their ring is full, or null if none. */
  private final boolean[] retryTemplates;
  /** Per gateway: held messages as [int length][int templateId][bytes], 8-byte aligned. */
  private final UnsafeBuffer[] retryBuffers;
  private final int[] retryHead;
  private final int[] retryTail;
  private int retryGateways;

  /** Per template id: conflated when held back, or null when nothing is. */
  private final boolean[] conflating;
  private final int[] conflateKeyOffset;
  private final int[] conflateKeyLength;
  private final int conflatedPerCycle;
  private final int heldSlotBytes;
  /** Held inputs as [int length][int templateId][int version][int spare][bytes], one slot each. */
  private final UnsafeBuffer held;
  private final long[] heldKey;
  private final int[] heldTemplate;
  /** Slots in arrival order of their key's first held input; a ring of slot indices. */
  private final int[] heldOrder;
  private int heldHead;
  private int heldCount;

  private Agent[] agents = new Agent[0];
  private BackpressureListener backpressureListener = event -> {};
  private int pendingCount;
  private boolean stopped;
  /** True while a live loop replays its own journals before it goes live. */
  private boolean recovering;
  private JournalSink liveOutboundJournal;

  private EventLoop(final Builder b) {
    this.clock = b.clock;
    this.timers = new TimerWheel(b.timerTickNanos, b.timerWheelSize, b.timerCapacity, clock.nanoTime());
    this.inbound = b.inbound.clone();
    this.outbound = b.outbound.clone();
    this.inboundJournal = b.inboundJournal;
    this.outboundJournal = b.outboundJournal;
    this.readLimit = b.readLimit;
    this.replay = b.replay;
    this.pendingDropped = new long[outbound.length];
    this.pendingFirstTemplate = new int[outbound.length];
    this.retryTemplates = b.retryTemplates == null ? null : b.retryTemplates.clone();
    this.retryBuffers = new UnsafeBuffer[outbound.length];
    this.retryHead = new int[outbound.length];
    this.retryTail = new int[outbound.length];
    if (retryTemplates != null) {
      for (int g = 0; g < outbound.length; g++) {
        retryBuffers[g] = new UnsafeBuffer(ByteBuffer.allocateDirect(b.retryCapacityBytes));
      }
    }
    final boolean conflate = b.conflating != null && !b.replay;
    this.conflating = conflate ? b.conflating.clone() : null;
    this.conflateKeyOffset = conflate ? b.conflateKeyOffset.clone() : null;
    this.conflateKeyLength = conflate ? b.conflateKeyLength.clone() : null;
    this.conflatedPerCycle = b.conflatedPerCycle;
    this.heldSlotBytes = HELD_HEADER + b.conflateSlotBytes;
    final int slots = conflate ? b.conflateSlots : 0;
    this.held = conflate ? new UnsafeBuffer(ByteBuffer.allocateDirect(slots * heldSlotBytes)) : null;
    this.heldKey = new long[slots];
    this.heldTemplate = new int[slots];
    this.heldOrder = new int[slots];
    // Load and initialise the backpressure path's classes now, on the building thread. The loop
    // reads ControlMessages only through compile-time constants until the first dropped message,
    // so without this the first backpressure event of a run loads the class on the loop thread,
    // which allocates (DR-INT-02). The scratch buffer is overwritten before every use.
    ControlMessages.encodeBackpressure(scratch, 0, 0L, 0L, 0, 0);
    backpressureEvent.set(0, 0L, 0, 0L);
  }

  /** A builder over the loop's clock. Journals given to the builder should use the same clock. */
  public static Builder builder(final EventClock clock) {
    return new Builder(clock, false);
  }

  /**
   * Registers the handler for one template id. Call before the loop runs.
   *
   * @throws IllegalArgumentException if the id is out of range or already has a handler
   */
  public EventLoop register(final int templateId, final MessageHandler handler) {
    if (templateId < 1 || templateId >= TEMPLATE_IDS) {
      throw new IllegalArgumentException("template id out of range: " + templateId);
    }
    if (handlers[templateId] != null) {
      throw new IllegalArgumentException("template id " + templateId + " already has a handler");
    }
    handlers[templateId] = handler;
    return this;
  }

  /** Adds an agent, called once per live duty cycle after the timers. Call before the loop runs. */
  public EventLoop addAgent(final Agent agent) {
    agents = Arrays.copyOf(agents, agents.length + 1);
    agents[agents.length - 1] = agent;
    return this;
  }

  /** Sets the listener told about dropped outbound messages. Call before the loop runs. */
  public EventLoop backpressureListener(final BackpressureListener listener) {
    this.backpressureListener = listener;
    return this;
  }

  /** The loop's latched clock; handlers read time here and nowhere else. */
  public EventClock clock() {
    return clock;
  }

  /** The loop's timer wheel; deadlines are in {@code clock().nanoTime()} units. */
  public TimerWheel timers() {
    return timers;
  }

  /** Counters readable from any thread. */
  public LoopCounters counters() {
    return counters;
  }

  /** Number of outbound gateways; gateway ids are 0 to this minus 1. */
  public int gatewayCount() {
    return outbound.length;
  }

  /** True if this loop is replaying a journal. */
  public boolean isReplay() {
    return replay;
  }

  /** True once a journaled LoopError has been replayed; live loops are stopped by their runner. */
  public boolean isStopped() {
    return stopped;
  }

  /**
   * One duty cycle.
   *
   * @return the work done: messages read plus timers fired plus agent work
   */
  public int doWork() {
    clock.latch();
    counters.increment(LoopCounters.DUTY_CYCLES);
    int work = retryGateways == 0 ? 0 : flushRetries();
    for (final RingBuffer ring : inbound) {
      work += ring.read(ringHandler, readLimit);
    }
    if (heldCount != 0) {
      work += dispatchHeld();
    }
    final int fired = timers.poll(clock.nanoTime());
    if (fired > 0) {
      journalClockTick();
      flushBackpressure();
      work += fired;
    }
    for (final Agent agent : agents) {
      work += agent.doWork();
      flushBackpressure();
    }
    return work;
  }

  private void onRingMessage(
      final int msgTypeId, final MutableDirectBuffer buffer, final int index, final int length) {
    if (length < HEADER || length > RingBufferContract.MAX_MESSAGE_LENGTH) {
      throw ContractBreach.length(length);
    }
    final int templateId = RingBufferContract.templateId(buffer, index);
    if (templateId != msgTypeId) {
      throw ContractBreach.typeMismatch(msgTypeId, templateId);
    }
    final int schemaId = RingBufferContract.schemaId(buffer, index);
    final int version = RingBufferContract.version(buffer, index);
    if (schemaId == SCHEMA_ID && version == SCHEMA_VERSION) {
      if (isClockTick(templateId, buffer, index)) {
        throw ContractBreach.reserved(templateId);
      }
    } else if (schemaId == ControlMessages.SCHEMA_ID && version == ControlMessages.SCHEMA_VERSION) {
      if (templateId != ControlMessages.GATEWAY_STATUS_TEMPLATE_ID) {
        throw ContractBreach.reserved(templateId);
      }
    } else {
      throw ContractBreach.schema(schemaId, version, templateId);
    }
    if (LoopProbe.ENABLED) {
      LoopProbe.Installed.probe.onStage(LoopProbe.READ, templateId, buffer, index);
    }
    if (conflating != null && conflating[templateId] && holdBack(templateId, version, buffer, index, length)) {
      return;
    }
    inboundJournal.append(templateId, version, buffer, index, length);
    if (LoopProbe.ENABLED) {
      LoopProbe.Installed.probe.onStage(LoopProbe.JOURNALED, templateId, buffer, index);
    }
    dispatch(templateId, buffer, index, length);
    flushBackpressure();
    if (LoopProbe.ENABLED) {
      LoopProbe.Installed.probe.onStage(LoopProbe.HANDLED, templateId, buffer, index);
    }
  }

  private static final int HELD_HEADER = 16;

  /**
   * Holds a conflatable input in its key's slot, replacing a held one with the same key. False if
   * it cannot be held (no free slot, or too long for one): the caller then dispatches it at once,
   * which is always correct, only not conflated.
   */
  private boolean holdBack(
      final int templateId, final int version, final DirectBuffer buffer, final int index, final int length) {
    if (length > heldSlotBytes - HELD_HEADER) {
      return false;
    }
    final long key = conflationKey(templateId, buffer, index);
    final int slots = heldOrder.length;
    int slot = -1;
    for (int i = 0; i < heldCount; i++) {
      final int s = heldOrder[(heldHead + i) % slots];
      if (heldTemplate[s] == templateId && heldKey[s] == key) {
        slot = s;
        break;
      }
    }
    if (slot >= 0) {
      counters.increment(LoopCounters.CONFLATED);
    } else {
      if (heldCount == slots) {
        return false;
      }
      slot = freeSlot();
      heldTemplate[slot] = templateId;
      heldKey[slot] = key;
      heldOrder[(heldHead + heldCount) % slots] = slot;
      heldCount++;
    }
    final int at = slot * heldSlotBytes;
    held.putInt(at, length);
    held.putInt(at + Integer.BYTES, templateId);
    held.putInt(at + 2 * Integer.BYTES, version);
    held.putBytes(at + HELD_HEADER, buffer, index, length);
    return true;
  }

  /** A slot no held input occupies; called only when one exists. */
  private int freeSlot() {
    final int slots = heldOrder.length;
    for (int s = 0; s < slots; s++) {
      boolean used = false;
      for (int i = 0; i < heldCount && !used; i++) {
        used = heldOrder[(heldHead + i) % slots] == s;
      }
      if (!used) {
        return s;
      }
    }
    throw new IllegalStateException();
  }

  private long conflationKey(final int templateId, final DirectBuffer buffer, final int index) {
    final int from = index + conflateKeyOffset[templateId];
    long key = 0;
    for (int i = conflateKeyLength[templateId] - 1; i >= 0; i--) {
      key = (key << 8) | (buffer.getByte(from + i) & 0xFFL);
    }
    return key;
  }

  /** Journals and dispatches the oldest held inputs, at most {@link Builder#conflatedPerCycle}. */
  private int dispatchHeld() {
    int done = 0;
    while (heldCount != 0 && done < conflatedPerCycle) {
      final int slot = heldOrder[heldHead];
      heldHead = (heldHead + 1) % heldOrder.length;
      heldCount--;
      final int at = slot * heldSlotBytes;
      final int length = held.getInt(at);
      final int templateId = held.getInt(at + Integer.BYTES);
      inboundJournal.append(templateId, held.getInt(at + 2 * Integer.BYTES), held, at + HELD_HEADER, length);
      dispatch(templateId, held, at + HELD_HEADER, length);
      flushBackpressure();
      done++;
    }
    // inputs still held are work: the idle strategy must not back off while they wait
    return done + heldCount;
  }

  /** Conflatable inputs read and not yet dispatched. Loop thread only. */
  public int heldInputs() {
    return heldCount;
  }

  private static boolean isClockTick(final int templateId, final DirectBuffer buffer, final int index) {
    return templateId == HeartbeatDecoder.TEMPLATE_ID
        && buffer.getInt(index + HEADER, ByteOrder.LITTLE_ENDIAN) == CLOCK_TICK_SOURCE_ID;
  }

  private void dispatch(
      final int templateId, final DirectBuffer buffer, final int offset, final int length) {
    counters.increment(LoopCounters.INBOUND);
    final MessageHandler handler = handlers[templateId];
    if (handler == null) {
      counters.increment(LoopCounters.UNHANDLED);
    } else {
      handler.onMessage(templateId, buffer, offset, length);
    }
  }

  /**
   * Sends one SBE message (header plus body) to a gateway. The message is journaled as outbound
   * whether or not the ring accepts it; a full ring drops it and raises a {@link BackpressureEvent}
   * after the current handler, timer poll or agent returns. The caller learns nothing here, by
   * design: handler control flow must not depend on how fast a gateway drains (DR-P2C-05).
   *
   * @throws IllegalArgumentException if the gateway is unknown or the length is outside the
   *     contract
   */
  public void publish(final int gatewayId, final DirectBuffer buffer, final int offset, final int length) {
    if (length < HEADER || length > RingBufferContract.MAX_MESSAGE_LENGTH) {
      throw ContractBreach.length(length);
    }
    if (!replay && (gatewayId < 0 || gatewayId >= outbound.length)) {
      throw ContractBreach.gateway(gatewayId, outbound.length);
    }
    final int templateId = RingBufferContract.templateId(buffer, offset);
    outboundJournal.append(templateId, RingBufferContract.version(buffer, offset), buffer, offset, length);
    counters.increment(LoopCounters.OUTBOUND);
    if (replay || recovering) {
      return;
    }
    final boolean retry = retryTemplates != null && retryTemplates[templateId];
    if (retry && retryTail[gatewayId] != 0) {
      // older cancels are still waiting for this ring: keep their order
      hold(gatewayId, templateId, buffer, offset, length);
      return;
    }
    if (!outbound[gatewayId].write(templateId, buffer, offset, length)) {
      counters.increment(LoopCounters.OUTBOUND_DROPPED);
      if (pendingDropped[gatewayId]++ == 0) {
        pendingFirstTemplate[gatewayId] = templateId;
        pendingCount++;
      }
      if (retry) {
        hold(gatewayId, templateId, buffer, offset, length);
      }
    }
  }

  private void hold(
      final int gatewayId, final int templateId, final DirectBuffer buffer, final int offset, final int length) {
    final UnsafeBuffer held = retryBuffers[gatewayId];
    final int tail = retryTail[gatewayId];
    final int record = (2 * Integer.BYTES + length + 7) & ~7;
    if (tail + record > held.capacity()) {
      counters.increment(LoopCounters.OUTBOUND_RETRY_LOST);
      return;
    }
    if (tail == 0) {
      retryGateways++;
    }
    held.putInt(tail, length);
    held.putInt(tail + Integer.BYTES, templateId);
    held.putBytes(tail + 2 * Integer.BYTES, buffer, offset, length);
    retryTail[gatewayId] = tail + record;
    counters.increment(LoopCounters.OUTBOUND_RETRY_QUEUED);
  }

  /** Writes held must-deliver messages to their rings, oldest first, as far as the rings allow. */
  private int flushRetries() {
    int delivered = 0;
    for (int g = 0; g < retryBuffers.length; g++) {
      final int tail = retryTail[g];
      if (tail == 0) {
        continue;
      }
      final UnsafeBuffer held = retryBuffers[g];
      int head = retryHead[g];
      while (head < tail) {
        final int length = held.getInt(head);
        if (!outbound[g].write(held.getInt(head + Integer.BYTES), held, head + 2 * Integer.BYTES, length)) {
          break;
        }
        head += (2 * Integer.BYTES + length + 7) & ~7;
        counters.increment(LoopCounters.OUTBOUND_RETRIED);
        delivered++;
      }
      if (head == tail) {
        retryHead[g] = 0;
        retryTail[g] = 0;
        retryGateways--;
      } else {
        retryHead[g] = head;
      }
    }
    return delivered;
  }

  /** Must-deliver messages waiting for room in a gateway ring. Loop thread only. */
  public boolean hasHeldOutbound() {
    return retryGateways != 0;
  }

  private void flushBackpressure() {
    if (pendingCount == 0) {
      return;
    }
    for (int g = 0; g < pendingDropped.length; g++) {
      final long dropped = pendingDropped[g];
      if (dropped > 0) {
        pendingDropped[g] = 0;
        pendingCount--;
        final int first = pendingFirstTemplate[g];
        final long now = clock.epochNanos();
        final int length = ControlMessages.encodeBackpressure(scratch, 0, now, dropped, g, first);
        inboundJournal.append(
            ControlMessages.BACKPRESSURE_TEMPLATE_ID, ControlMessages.SCHEMA_VERSION, scratch, 0, length);
        raiseBackpressure(g, dropped, first, now);
      }
    }
  }

  private void raiseBackpressure(
      final int gatewayId, final long dropped, final int firstTemplate, final long now) {
    counters.increment(LoopCounters.BACKPRESSURE_EVENTS);
    backpressureEvent.set(gatewayId, dropped, firstTemplate, now);
    backpressureListener.onBackpressure(backpressureEvent);
  }

  private void journalClockTick() {
    heartbeatEncoder
        .wrapAndApplyHeader(scratch, 0, headerEncoder)
        .sourceId(CLOCK_TICK_SOURCE_ID)
        .timestampNanos(clock.epochNanos());
    inboundJournal.append(
        HeartbeatEncoder.TEMPLATE_ID, SCHEMA_VERSION, scratch, 0, heartbeatEncoder.limit());
    counters.increment(LoopCounters.TIMER_TICKS);
  }

  /**
   * Replays one journaled inbound frame. The caller has set the source clock to the frame's stamp.
   * Clock ticks poll the timers, Backpressure frames reach the listener, a LoopError stops the
   * loop, and every other frame is dispatched as it was live.
   */
  void onReplayFrame(
      final int templateId, final DirectBuffer buffer, final int offset, final int length) {
    if (stopped) {
      return;
    }
    clock.latch();
    final int schemaId = RingBufferContract.schemaId(buffer, offset);
    if (schemaId == SCHEMA_ID && isClockTick(templateId, buffer, offset)) {
      timers.poll(clock.nanoTime());
    } else if (schemaId == ControlMessages.SCHEMA_ID
        && templateId == ControlMessages.BACKPRESSURE_TEMPLATE_ID) {
      raiseBackpressure(
          ControlMessages.backpressureGateway(buffer, offset),
          ControlMessages.backpressureDropped(buffer, offset),
          ControlMessages.backpressureFirstTemplate(buffer, offset),
          ControlMessages.timestampNanos(buffer, offset));
    } else if (schemaId == ControlMessages.SCHEMA_ID
        && templateId == ControlMessages.LOOP_ERROR_TEMPLATE_ID) {
      stopped = true;
    } else {
      dispatch(templateId, buffer, offset, length);
    }
  }

  /**
   * Puts a live loop into recovery: until {@link #endRecovery} every published message goes to
   * {@code produced} instead of the outbound journal and no ring is written, exactly as in replay.
   * {@link LoopRecovery} then feeds the loop its own inbound journal through {@link
   * #onReplayFrame}, so the handlers rebuild their state by the same path that replay checks.
   */
  void beginRecovery(final JournalSink produced) {
    if (replay || recovering) {
      throw new IllegalStateException("recovery starts once, on a live loop");
    }
    recovering = true;
    liveOutboundJournal = outboundJournal;
    outboundJournal = produced;
  }

  /** Ends recovery: publishes are journaled and written to the rings again. */
  void endRecovery() {
    if (!recovering) {
      throw new IllegalStateException("the loop is not recovering");
    }
    recovering = false;
    outboundJournal = liveOutboundJournal;
    liveOutboundJournal = null;
  }

  /** True between the start of a recovery and its end. */
  public boolean isRecovering() {
    return recovering;
  }

  /**
   * Journals a LoopError for {@code failure}. Called by {@link LoopRunner} on the loop thread when
   * a duty cycle throws; allocates, which is acceptable because the loop is stopping.
   */
  public void recordError(final Throwable failure) {
    clock.latch();
    counters.increment(LoopCounters.ERRORS);
    final String text = failure.getClass().getName() + ": " + failure.getMessage();
    final int length =
        ControlMessages.encodeLoopError(
            scratch, 0, clock.epochNanos(), counters.get(LoopCounters.INBOUND), text);
    inboundJournal.append(
        ControlMessages.LOOP_ERROR_TEMPLATE_ID, ControlMessages.SCHEMA_VERSION, scratch, 0, length);
  }

  /** Configuration of a loop. Rings and journals are set by the caller that owns them. */
  public static final class Builder {

    private final EventClock clock;
    private final boolean replay;
    private RingBuffer[] inbound = new RingBuffer[0];
    private RingBuffer[] outbound = new RingBuffer[0];
    private JournalSink inboundJournal = JournalSink.NONE;
    private JournalSink outboundJournal = JournalSink.NONE;
    private long timerTickNanos = 1_000_000L;
    private int timerWheelSize = 1024;
    private int timerCapacity = 1024;
    private int readLimit = DEFAULT_READ_LIMIT;
    private boolean[] retryTemplates;
    private int retryCapacityBytes = 64 * 1024;
    private boolean[] conflating;
    private int[] conflateKeyOffset;
    private int[] conflateKeyLength;
    private int conflatedPerCycle = 1;
    private int conflateSlots = 64;
    private int conflateSlotBytes = 256;

    Builder(final EventClock clock, final boolean replay) {
      this.clock = clock;
      this.replay = replay;
    }

    /** Adds an inbound ring; rings are drained in the order added. */
    public Builder inbound(final RingBuffer ring) {
      checkLive("inbound rings");
      inbound = Arrays.copyOf(inbound, inbound.length + 1);
      inbound[inbound.length - 1] = ring;
      return this;
    }

    /** Adds an outbound ring; its gateway id is the number of rings added before it. */
    public Builder outbound(final RingBuffer ring) {
      checkLive("outbound rings");
      outbound = Arrays.copyOf(outbound, outbound.length + 1);
      outbound[outbound.length - 1] = ring;
      return this;
    }

    /** Where inbound messages, clock ticks, backpressure and errors are journaled. */
    public Builder inboundJournal(final JournalSink sink) {
      checkLive("journals");
      inboundJournal = sink;
      return this;
    }

    /** Where outbound messages are journaled. */
    public Builder outboundJournal(final JournalSink sink) {
      checkLive("journals");
      outboundJournal = sink;
      return this;
    }

    /**
     * Sets both journals from a pair opened in one {@link JournalMode}; the same as {@link
     * #inboundJournal} with {@code journals.inbound()} and {@link #outboundJournal} with {@code
     * journals.outbound()}.
     */
    public Builder journals(final LoopJournals journals) {
      return inboundJournal(journals.inbound()).outboundJournal(journals.outbound());
    }

    Builder replayOutbound(final JournalSink sink) {
      outboundJournal = sink;
      return this;
    }

    /** Timer wheel geometry (API_SPEC.md Section 3.2). Defaults: 1 ms ticks, 1024 slots and timers. */
    public Builder timers(final long tickNanos, final int wheelSize, final int capacity) {
      timerTickNanos = tickNanos;
      timerWheelSize = wheelSize;
      timerCapacity = capacity;
      return this;
    }

    /** Messages read from each inbound ring per duty cycle. */
    public Builder readLimit(final int limit) {
      if (limit < 1) {
        throw new IllegalArgumentException("read limit must be positive: " + limit);
      }
      readLimit = limit;
      return this;
    }

    /**
     * Names outbound template ids that must reach their gateway: when the ring is full the loop
     * keeps the message and writes it on later duty cycles instead of only counting the drop. Meant
     * for cancels and withdrawals, where a lost message leaves a quote or an order working at the
     * venue. Each gateway holds up to {@link #retryCapacity} bytes; beyond that a message is lost
     * and counted in {@link LoopCounters#OUTBOUND_RETRY_LOST}. Ignored in replay.
     */
    public Builder retryOnDrop(final int... templateIds) {
      if (retryTemplates == null) {
        retryTemplates = new boolean[TEMPLATE_IDS];
      }
      for (final int id : templateIds) {
        if (id < 1 || id >= TEMPLATE_IDS) {
          throw new IllegalArgumentException("template id out of range: " + id);
        }
        retryTemplates[id] = true;
      }
      return this;
    }

    /** Bytes of held must-deliver messages per gateway; 64 KiB by default. */
    public Builder retryCapacity(final int bytes) {
      if (bytes < 64) {
        throw new IllegalArgumentException("retry capacity too small: " + bytes);
      }
      retryCapacityBytes = bytes;
      return this;
    }

    /**
     * Conflates inputs of {@code templateId}: they are held back per key and dispatched after the
     * rest of the ring, the newest per key only (see the class comment). The key is {@code
     * keyLength} bytes (1 to 8) at {@code keyOffset} from the start of the message, SBE header
     * included. Ignored in replay, which dispatches the journal as it stands.
     *
     * <p>Only for level inputs whose newest value supersedes the older ones, such as curve quotes.
     * Never for session messages, book deltas or anything whose loss changes state.
     */
    public Builder conflate(final int templateId, final int keyOffset, final int keyLength) {
      if (templateId < 1 || templateId >= TEMPLATE_IDS) {
        throw new IllegalArgumentException("template id out of range: " + templateId);
      }
      if (keyOffset < HEADER || keyLength < 1 || keyLength > Long.BYTES) {
        throw new IllegalArgumentException("a conflation key is 1 to 8 bytes after the SBE header");
      }
      if (conflating == null) {
        conflating = new boolean[TEMPLATE_IDS];
        conflateKeyOffset = new int[TEMPLATE_IDS];
        conflateKeyLength = new int[TEMPLATE_IDS];
      }
      conflating[templateId] = true;
      conflateKeyOffset[templateId] = keyOffset;
      conflateKeyLength[templateId] = keyLength;
      return this;
    }

    /**
     * Held inputs dispatched per duty cycle (1 by default): the most expensive handler work that can
     * stand between two reads of the rings.
     */
    public Builder conflatedPerCycle(final int perCycle) {
      if (perCycle < 1) {
        throw new IllegalArgumentException("at least one held input per cycle: " + perCycle);
      }
      conflatedPerCycle = perCycle;
      return this;
    }

    /**
     * Distinct keys held at once (64 by default) and the largest message a slot holds (256 bytes
     * by default). An input that finds no slot is dispatched where it stands.
     */
    public Builder conflationSlots(final int slots, final int slotBytes) {
      if (slots < 1 || slotBytes < HEADER) {
        throw new IllegalArgumentException("conflation slots too small");
      }
      conflateSlots = slots;
      conflateSlotBytes = slotBytes;
      return this;
    }

    /** The clock the loop will latch. */
    public EventClock clock() {
      return clock;
    }

    /** True if the loop being built will replay a journal. */
    public boolean isReplay() {
      return replay;
    }

    public EventLoop build() {
      return new EventLoop(this);
    }

    private void checkLive(final String what) {
      if (replay) {
        throw new IllegalStateException(
            what + " are set by LoopReplay in replay; a LoopFactory sets timers and handlers only");
      }
    }
  }
}
