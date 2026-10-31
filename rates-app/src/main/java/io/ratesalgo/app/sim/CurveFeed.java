package io.ratesalgo.app.sim;

import io.ratesalgo.app.loop.AppIds;
import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.common.time.Clock;
import io.ratesalgo.fix.gateway.MessageSink;
import io.ratesalgo.messages.sbe.CurveInputEncoder;
import io.ratesalgo.messages.sbe.CurveInputKind;
import io.ratesalgo.messages.sbe.HeartbeatEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.venues.d2c.D2cRfqSimulator;
import java.nio.ByteBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * The curve market-data source of a simulated day: at a fixed interval, one CurveInput (kind
 * ParSwap, curve {@link AppIds#SOFR_CURVE}) per SOFR swap template carrying the D2C simulator's
 * current mid, then one Heartbeat from source {@link AppIds#CURVE_HEARTBEAT_SOURCE}. Taking the
 * curve from the venue's own mid keeps the desk's fair value and the venue's dealers on the same
 * market, as a real curve feed and a real venue would be (DECISIONS.md DR-R2W-09).
 */
public final class CurveFeed {

  private final UsdUniverse universe;
  private final D2cRfqSimulator venue;
  private final MessageSink inbound;
  private final Clock clock;
  private final long intervalNanos;
  private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final CurveInputEncoder input = new CurveInputEncoder();
  private final HeartbeatEncoder heartbeat = new HeartbeatEncoder();
  private long nextNanos = Long.MIN_VALUE;
  private long published;

  public CurveFeed(
      final UsdUniverse universe,
      final D2cRfqSimulator venue,
      final MessageSink inbound,
      final Clock clock,
      final long intervalNanos) {
    this.universe = universe;
    this.venue = venue;
    this.inbound = inbound;
    this.clock = clock;
    this.intervalNanos = intervalNanos;
  }

  /** Publishes a round if one is due; returns the messages published. */
  public int doWork() {
    final long now = clock.nanoTime();
    if (nextNanos != Long.MIN_VALUE && now < nextNanos) {
      return 0;
    }
    nextNanos = now + intervalNanos;
    final long stamp = clock.epochNanos();
    int n = 0;
    for (final UsdUniverse.SwapSpec s : universe.swaps()) {
      input
          .wrapAndApplyHeader(buffer, 0, header)
          .curveId(AppIds.SOFR_CURVE)
          .kind(CurveInputKind.ParSwap)
          .instrumentId(s.instrumentId())
          .tenor(padded(s.tenor()))
          .timestampNanos(stamp);
      input.value().mantissa(venue.mid(s.securityId()));
      if (inbound.offer(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + input.encodedLength())) {
        n++;
      }
    }
    heartbeat.wrapAndApplyHeader(buffer, 0, header).sourceId(AppIds.CURVE_HEARTBEAT_SOURCE).timestampNanos(stamp);
    if (inbound.offer(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + heartbeat.encodedLength())) {
      n++;
    }
    published += n;
    return n;
  }

  private static String padded(final String tenor) {
    return (tenor + "    ").substring(0, 4);
  }

  /** Messages published so far. */
  public long published() {
    return published;
  }
}
