package io.ratesalgo.app.sim;

import io.ratesalgo.analytics.date.Calendars;
import io.ratesalgo.app.loop.AppIds;
import io.ratesalgo.app.pricing.BusinessDate;
import io.ratesalgo.app.pricing.PricingV2;
import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.app.pricing.V2InputIds;
import io.ratesalgo.common.time.Clock;
import io.ratesalgo.fix.gateway.MessageSink;
import io.ratesalgo.messages.sbe.CurveInputEncoder;
import io.ratesalgo.messages.sbe.CurveInputKind;
import io.ratesalgo.messages.sbe.HeartbeatEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.venues.sim.SimV2CurveRound;
import io.ratesalgo.venues.sim.SimV2Session;
import java.nio.ByteBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * The curve market data of a simulator v2 day for pricing v2: at a fixed interval one round of
 * CurveInput messages, every one with the same timestamp, then a Heartbeat from {@link
 * AppIds#CURVE_HEARTBEAT_SOURCE}. A round is exactly the build set of the pricing binding on the
 * business date (docs/research/PRICING_V2.md Section 12): the overnight fixing, the SR1 and SR3
 * contracts and a par swap at each of the eight risk tenors, plus any quoted swap that is not a
 * pillar. Nothing is left for the binding to seed.
 *
 * <p>The values come from the session's factor model ({@link SimV2CurveRound}), so the desk's
 * curve and the venue's competitors are on one market. The list of inputs is taken from a pricing
 * binding built for the purpose and rebuilt when the business date changes, because the futures
 * contracts of the build set change with the date; that allocates, once a day, on the feed's
 * thread.
 */
public final class CurveFeedV2 {

  private final UsdUniverse universe;
  private final SimV2CurveRound round;
  private final MessageSink inbound;
  private final Clock clock;
  private final long intervalNanos;
  private final PricingV2.Options options;
  private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final CurveInputEncoder input = new CurveInputEncoder();
  private final HeartbeatEncoder heartbeat = new HeartbeatEncoder();
  private int[] inputIds = new int[0];
  private int businessDate = Integer.MIN_VALUE;
  private long nextNanos = Long.MIN_VALUE;
  private long published;
  private long rounds;

  /**
   * A feed for one session.
   *
   * @param universe the desk's universe, which names the quoted swaps
   * @param session the simulator v2 session the values come from
   * @param inbound where the messages go
   * @param clock the simulated clock
   * @param intervalNanos time between rounds
   */
  public CurveFeedV2(
      final UsdUniverse universe,
      final SimV2Session session,
      final MessageSink inbound,
      final Clock clock,
      final long intervalNanos) {
    this.universe = universe;
    this.round = new SimV2CurveRound(session);
    this.inbound = inbound;
    this.clock = clock;
    this.intervalNanos = intervalNanos;
    this.options = PricingV2.Options.defaults();
  }

  /** The inputs a binding needs on the clock's business date: its build set, then other quoted swaps. */
  private void layout() {
    final int today = BusinessDate.tradeDate(clock.epochNanos(), options.cutHour(), Calendars.US_SOFR);
    if (today == businessDate) {
      return;
    }
    businessDate = today;
    final PricingV2 probe = new PricingV2(universe, clock, options);
    final java.util.List<Integer> ids = new java.util.ArrayList<>();
    for (int i = 0; i < probe.inputCount(); i++) {
      ids.add(probe.inputId(i));
    }
    for (final UsdUniverse.SwapSpec s : universe.swaps()) {
      if (!ids.contains(s.instrumentId()) && !ids.contains(V2InputIds.parSwap(s.tenorMonths()))) {
        ids.add(s.instrumentId());
      }
    }
    inputIds = ids.stream().mapToInt(Integer::intValue).toArray();
  }

  /** Publishes a round if one is due; returns the messages published. */
  public int doWork() {
    final long now = clock.nanoTime();
    if (nextNanos != Long.MIN_VALUE && now < nextNanos) {
      return 0;
    }
    nextNanos = now + intervalNanos;
    layout();
    final long stamp = clock.epochNanos();
    int n = 0;
    for (final int id : inputIds) {
      final CurveInputKind kind;
      final long value;
      final String tenor;
      if (id == V2InputIds.OVERNIGHT) {
        kind = CurveInputKind.Fixing;
        value = round.overnight();
        tenor = "ON  ";
      } else if (V2InputIds.isSr1(id) || V2InputIds.isSr3(id)) {
        kind = CurveInputKind.Future;
        value = round.futurePrice();
        tenor = V2InputIds.isSr1(id) ? "SR1 " : "SR3 ";
      } else {
        final UsdUniverse.SwapSpec s = universe.swap(id);
        final int months = s != null ? s.tenorMonths() : V2InputIds.parSwapMonths(id);
        kind = CurveInputKind.ParSwap;
        value = round.parSwap(months);
        tenor = padded(months % 12 == 0 ? (months / 12) + "Y" : months + "M");
      }
      input
          .wrapAndApplyHeader(buffer, 0, header)
          .curveId(AppIds.SOFR_CURVE)
          .kind(kind)
          .instrumentId(id)
          .tenor(tenor)
          .timestampNanos(stamp);
      input.value().mantissa(value);
      if (inbound.offer(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + input.encodedLength())) {
        n++;
      }
    }
    heartbeat.wrapAndApplyHeader(buffer, 0, header).sourceId(AppIds.CURVE_HEARTBEAT_SOURCE).timestampNanos(stamp);
    if (inbound.offer(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + heartbeat.encodedLength())) {
      n++;
    }
    published += n;
    rounds++;
    return n;
  }

  private static String padded(final String tenor) {
    return (tenor + "    ").substring(0, 4);
  }

  /** Inputs in one round on the current business date (the heartbeat not counted). */
  public int inputsPerRound() {
    layout();
    return inputIds.length;
  }

  /** Messages published so far. */
  public long published() {
    return published;
  }

  /** Rounds published so far. */
  public long rounds() {
    return rounds;
  }
}
