package io.ratesalgo.app.loop;

import io.ratesalgo.core.EventLoop;
import io.ratesalgo.core.HotPath;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.NewOrderEncoder;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.OrderCancelEncoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import io.ratesalgo.router.OrderGateway;
import java.nio.ByteBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * P4's {@link OrderGateway} for one router: child orders become SBE NewOrder (20) and cancels
 * OrderCancel (41), published to the venue's outbound ring with {@link EventLoop#publish}; the
 * gateway thread turns them into FIX D and F. A cancel's ClOrdID is the child's id with {@link
 * AppIds#CANCEL_FLAG} set, so the venue's cancel acknowledgement (which carries the cancel's
 * ClOrdID) maps back to the child without a table. Side and quantity of the cancel come from the
 * router's child book through {@link #childLookup}. Allocation-free.
 */
@HotPath
final class LoopOrderGateway implements OrderGateway {

  /** What the gateway needs to know about a resting child to cancel it. */
  interface ChildLookup {
    Side side(long clOrdId);

    long quantity(long clOrdId);
  }

  private final EventLoop loop;
  private final int instrumentId;
  private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final NewOrderEncoder newOrder = new NewOrderEncoder();
  private final OrderCancelEncoder cancel = new OrderCancelEncoder();
  private ChildLookup childLookup;

  LoopOrderGateway(final EventLoop loop, final int instrumentId) {
    this.loop = loop;
    this.instrumentId = instrumentId;
  }

  /** Binds the router's child book; set once the router exists. */
  void childLookup(final ChildLookup lookup) {
    this.childLookup = lookup;
  }

  @Override
  public void newOrder(
      final int venueId,
      final long clOrdId,
      final long parentId,
      final int instrument,
      final Side side,
      final OrdType ordType,
      final TimeInForce timeInForce,
      final long price,
      final long quantity) {
    final long now = loop.clock().epochNanos();
    newOrder
        .wrapAndApplyHeader(buffer, 0, header)
        .venueId(venueId)
        .clOrdId(clOrdId)
        .parentId(parentId)
        .instrumentId(instrument)
        .side(side)
        .ordType(ordType)
        .timeInForce(timeInForce)
        .quantity(quantity)
        .expireNanos(0L)
        .sentNanos(now);
    newOrder.price().mantissa(price);
    loop.publish(AppIds.gatewayFor(venueId), buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + newOrder.encodedLength());
  }

  @Override
  public void cancel(final int venueId, final long clOrdId) {
    cancel
        .wrapAndApplyHeader(buffer, 0, header)
        .venueId(venueId)
        .clOrdId(clOrdId | AppIds.CANCEL_FLAG)
        .origClOrdId(clOrdId)
        .instrumentId(instrumentId)
        .side(childLookup.side(clOrdId))
        .quantity(childLookup.quantity(clOrdId))
        .sentNanos(loop.clock().epochNanos());
    loop.publish(AppIds.gatewayFor(venueId), buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + cancel.encodedLength());
  }
}
