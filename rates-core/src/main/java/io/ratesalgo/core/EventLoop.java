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
 * it at the same point.
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
  private final JournalSink outboundJournal;
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

  private Agent[] agents = new Agent[0];
  private BackpressureListener backpressureListener = event -> {};
  private int pendingCount;
  private boolean stopped;

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
    int work = 0;
    for (final RingBuffer ring : inbound) {
      work += ring.read(ringHandler, readLimit);
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
    inboundJournal.append(templateId, version, buffer, index, length);
    dispatch(templateId, buffer, index, length);
    flushBackpressure();
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
    if (replay) {
      return;
    }
    if (!outbound[gatewayId].write(templateId, buffer, offset, length)) {
      counters.increment(LoopCounters.OUTBOUND_DROPPED);
      if (pendingDropped[gatewayId]++ == 0) {
        pendingFirstTemplate[gatewayId] = templateId;
        pendingCount++;
      }
    }
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
