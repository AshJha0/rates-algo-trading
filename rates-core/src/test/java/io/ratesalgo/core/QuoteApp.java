package io.ratesalgo.core;

import io.ratesalgo.messages.sbe.HeartbeatEncoder;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.KillSwitchEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.QuoteEncoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import java.nio.ByteBuffer;
import org.agrona.DirectBuffer;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * A small application for loop tests: answers each QuoteRequest with a Quote on gateway 0 whose
 * prices depend on state and time, schedules an expiry timer that sends a Heartbeat on gateway 1,
 * and sends a KillSwitch on gateway 1 on backpressure and on gateway disconnect.
 */
final class QuoteApp implements LoopFactory {

  private final boolean timers;
  private final long spread;
  private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
  private final QuoteRequestDecoder request = new QuoteRequestDecoder();
  private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
  private final QuoteEncoder quote = new QuoteEncoder();
  private final HeartbeatEncoder heartbeat = new HeartbeatEncoder();
  private final KillSwitchEncoder kill = new KillSwitchEncoder();
  private final UnsafeBuffer out = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
  private final Long2LongHashMap timerToRfq = new Long2LongHashMap(-1L);

  EventLoop loop;
  long quotes;
  long timersFired;
  long backpressureEvents;
  long statusEvents;

  QuoteApp(final boolean timers) {
    this(timers, 2_000_000L);
  }

  QuoteApp(final boolean timers, final long spread) {
    this.timers = timers;
    this.spread = spread;
  }

  @Override
  public EventLoop create(final EventLoop.Builder builder) {
    loop = builder.timers(1_000_000L, 256, 256).build();
    loop.register(QuoteRequestDecoder.TEMPLATE_ID, this::onQuoteRequest);
    loop.register(ControlMessages.GATEWAY_STATUS_TEMPLATE_ID, this::onGatewayStatus);
    loop.backpressureListener(this::onBackpressure);
    return loop;
  }

  private void onQuoteRequest(
      final int templateId, final DirectBuffer buffer, final int offset, final int length) {
    request.wrapAndApplyHeader(buffer, offset, headerDecoder);
    quotes++;
    final long rfqId = request.rfqId();
    final long now = loop.clock().epochNanos();
    final long mid = 100_000_000_000L + (rfqId % 50) * 1_000_000L + quotes * 1_000L + (now % 997);
    quote
        .wrapAndApplyHeader(out, 0, headerEncoder)
        .venueId(request.venueId())
        .rfqId(rfqId)
        .quoteId(quotes)
        .instrumentId(request.instrumentId());
    quote.bidPrice().mantissa(mid - spread);
    quote.askPrice().mantissa(mid + spread);
    quote.bidQuantity(request.quantity()).askQuantity(request.quantity());
    quote.referenceNanos(now).validUntilNanos(now + 1_000_000_000L).sentNanos(now);
    loop.publish(0, out, 0, quote.limit());
    if (timers) {
      final long id =
          loop.timers()
              .schedule(loop.clock().nanoTime() + (1 + rfqId % 3) * 1_000_000L, this::onTimer);
      timerToRfq.put(id, rfqId);
    }
  }

  private void onTimer(final long timerId, final long nowNanos) {
    timersFired++;
    final long rfqId = timerToRfq.remove(timerId);
    heartbeat
        .wrapAndApplyHeader(out, 0, headerEncoder)
        .sourceId((int) rfqId)
        .timestampNanos(loop.clock().epochNanos());
    loop.publish(1, out, 0, heartbeat.limit());
  }

  private void onBackpressure(final BackpressureEvent event) {
    backpressureEvents++;
    sendKill(KillReason.StaleReference, event.gatewayId(), event.timestampNanos());
  }

  private void onGatewayStatus(
      final int templateId, final DirectBuffer buffer, final int offset, final int length) {
    statusEvents++;
    if (ControlMessages.gatewayStatus(buffer, offset) == ControlMessages.STATUS_DISCONNECTED) {
      sendKill(
          KillReason.SessionLoss,
          ControlMessages.gatewayStatusGateway(buffer, offset),
          loop.clock().epochNanos());
    }
  }

  private void sendKill(final KillReason reason, final int scope, final long at) {
    kill.wrapAndApplyHeader(out, 0, headerEncoder)
        .reason(reason)
        .scopeId(scope)
        .requestedBy(0)
        .timestampNanos(at);
    loop.publish(1, out, 0, kill.limit());
  }
}
