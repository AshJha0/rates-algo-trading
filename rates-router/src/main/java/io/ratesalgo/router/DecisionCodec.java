package io.ratesalgo.router;

import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.RouteDecisionDecoder;
import io.ratesalgo.messages.sbe.RouteDecisionEncoder;
import io.ratesalgo.messages.sbe.RouteDecisionVenueDecoder;
import io.ratesalgo.messages.sbe.RouteDecisionVenueEncoder;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Writes a {@link CostTable} as one RouteDecision (template 80) followed by one
 * RouteDecisionVenue (template 81) per row, and reads it back. Encoding does not allocate.
 *
 * <p>Decoding is incremental so it can sit behind a journal frame handler: feed every frame to
 * {@link #onFrame}; it returns true when a decision's last row has been read and {@link
 * #decoded()} holds the complete table.
 */
public final class DecisionCodec {

  private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
  private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
  private final RouteDecisionEncoder decisionEncoder = new RouteDecisionEncoder();
  private final RouteDecisionVenueEncoder venueEncoder = new RouteDecisionVenueEncoder();

  private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
  private final RouteDecisionDecoder decisionDecoder = new RouteDecisionDecoder();
  private final RouteDecisionVenueDecoder venueDecoder = new RouteDecisionVenueDecoder();
  private final CostTable decoded = new CostTable();
  private int rowsSeen;
  private boolean inDecision;

  /** Writes the table to the sink. */
  public void write(final CostTable t, final FrameSink sink) {
    decisionEncoder
        .wrapAndApplyHeader(buffer, 0, headerEncoder)
        .decisionId(t.decisionId)
        .parentId(t.parentId)
        .instrumentId(t.instrumentId)
        .side(t.side)
        .reason(t.reason)
        .configVersion(t.configVersion)
        .venueCount((short) t.venueCount)
        .sliceIndex(t.sliceIndex)
        .parentQuantity(t.parentQuantity)
        .sliceQuantity(t.sliceQuantity)
        .allocatedQuantity(t.allocatedQuantity);
    decisionEncoder.urgency().mantissa(t.urgency);
    decisionEncoder
        .passiveHorizonNanos(t.passiveHorizonNanos)
        .sliceIntervalNanos(t.sliceIntervalNanos);
    decisionEncoder.arrivalMid().mantissa(t.arrivalMid);
    decisionEncoder.expectedCost().mantissa(t.expectedCost);
    decisionEncoder.timestampNanos(t.timestampNanos);
    sink.append(
        RouteDecisionEncoder.TEMPLATE_ID,
        RouteDecisionEncoder.SCHEMA_VERSION,
        buffer,
        0,
        MessageHeaderEncoder.ENCODED_LENGTH + RouteDecisionEncoder.BLOCK_LENGTH);
    for (int r = 0; r < t.venueCount; r++) {
      writeRow(t, r, sink);
    }
  }

  private void writeRow(final CostTable t, final int r, final FrameSink sink) {
    final RouteDecisionVenueEncoder e = venueEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
    e.decisionId(t.decisionId).row((short) r).venueId(t.venueId[r]).kind(t.kind[r]);
    e.bestBid().mantissa(t.bestBid[r]);
    e.bestAsk().mantissa(t.bestAsk[r]);
    e.tick().mantissa(t.tick[r]);
    e.lotSize(t.lotSize[r])
        .minSize(t.minSize[r])
        .sameSideDepth(t.sameSideDepth[r])
        .oppositeDepth(t.oppositeDepth[r]);
    e.tradeRate().mantissa(t.tradeRate[r]);
    e.takerFee().mantissa(t.takerFee[r]);
    e.makerFee().mantissa(t.makerFee[r]);
    e.latencyNanos(t.latencyNanos[r])
        .participationCap(t.participationCap[r])
        .passiveAllowed(flag(t.passiveAllowed[r]))
        .aggressiveAllowed(flag(t.aggressiveAllowed[r]))
        .exclusion(t.exclusion[r])
        .evaluatedQuantity(t.evaluatedQuantity[r])
        .allocatedQuantity(t.allocated[r])
        .passive(flag(t.passive[r]));
    e.fillProbability().mantissa(t.fillProbability[r]);
    e.queueWaitNanos(t.queueWaitNanos[r]);
    e.impactCost().mantissa(t.impactCost[r]);
    e.passiveCost().mantissa(t.passiveCost[r]);
    e.aggressiveCost().mantissa(t.aggressiveCost[r]);
    e.chosenCost().mantissa(t.chosenCost[r]);
    sink.append(
        RouteDecisionVenueEncoder.TEMPLATE_ID,
        RouteDecisionVenueEncoder.SCHEMA_VERSION,
        buffer,
        0,
        MessageHeaderEncoder.ENCODED_LENGTH + RouteDecisionVenueEncoder.BLOCK_LENGTH);
  }

  /**
   * Consumes one journal frame payload (SBE header included).
   *
   * @return true when the frame completed a decision
   * @throws IllegalStateException on a schema mismatch or a row out of sequence
   */
  public boolean onFrame(
      final int templateId, final DirectBuffer src, final int offset, final int length) {
    if (templateId != RouteDecisionDecoder.TEMPLATE_ID
        && templateId != RouteDecisionVenueDecoder.TEMPLATE_ID) {
      return false;
    }
    headerDecoder.wrap(src, offset);
    if (headerDecoder.schemaId() != RouteDecisionDecoder.SCHEMA_ID) {
      throw new IllegalStateException(
          "schema id " + headerDecoder.schemaId() + ", expected " + RouteDecisionDecoder.SCHEMA_ID);
    }
    if (templateId == RouteDecisionDecoder.TEMPLATE_ID) {
      readHeader(src, offset);
      return decoded.venueCount == 0;
    }
    venueDecoder.wrapAndApplyHeader(src, offset, headerDecoder);
    final int r = venueDecoder.row();
    if (!inDecision
        || venueDecoder.decisionId() != decoded.decisionId
        || r != rowsSeen
        || r >= CostTable.MAX_VENUES) {
      throw new IllegalStateException(
          "RouteDecisionVenue row " + r + " of decision " + venueDecoder.decisionId()
              + " out of sequence (expected row " + rowsSeen + " of " + decoded.decisionId + ")");
    }
    readRow(r);
    rowsSeen++;
    if (rowsSeen == decoded.venueCount) {
      inDecision = false;
      return true;
    }
    return false;
  }

  private void readHeader(final DirectBuffer src, final int offset) {
    final RouteDecisionDecoder d = decisionDecoder.wrapAndApplyHeader(src, offset, headerDecoder);
    final CostTable t = decoded;
    t.decisionId = d.decisionId();
    t.parentId = d.parentId();
    t.instrumentId = d.instrumentId();
    t.side = d.side();
    t.reason = d.reason();
    t.configVersion = d.configVersion();
    t.venueCount = d.venueCount();
    t.sliceIndex = d.sliceIndex();
    t.parentQuantity = d.parentQuantity();
    t.sliceQuantity = d.sliceQuantity();
    t.allocatedQuantity = d.allocatedQuantity();
    t.urgency = d.urgency().mantissa();
    t.passiveHorizonNanos = d.passiveHorizonNanos();
    t.sliceIntervalNanos = d.sliceIntervalNanos();
    t.arrivalMid = d.arrivalMid().mantissa();
    t.expectedCost = d.expectedCost().mantissa();
    t.timestampNanos = d.timestampNanos();
    if (t.venueCount > CostTable.MAX_VENUES) {
      throw new IllegalStateException("venueCount " + t.venueCount + " above " + CostTable.MAX_VENUES);
    }
    rowsSeen = 0;
    inDecision = t.venueCount > 0;
  }

  private void readRow(final int r) {
    final RouteDecisionVenueDecoder e = venueDecoder;
    final CostTable t = decoded;
    t.venueId[r] = e.venueId();
    t.kind[r] = e.kind();
    t.bestBid[r] = e.bestBid().mantissa();
    t.bestAsk[r] = e.bestAsk().mantissa();
    t.tick[r] = e.tick().mantissa();
    t.lotSize[r] = e.lotSize();
    t.minSize[r] = e.minSize();
    t.sameSideDepth[r] = e.sameSideDepth();
    t.oppositeDepth[r] = e.oppositeDepth();
    t.tradeRate[r] = e.tradeRate().mantissa();
    t.takerFee[r] = e.takerFee().mantissa();
    t.makerFee[r] = e.makerFee().mantissa();
    t.latencyNanos[r] = e.latencyNanos();
    t.participationCap[r] = e.participationCap();
    t.passiveAllowed[r] = e.passiveAllowed() != 0;
    t.aggressiveAllowed[r] = e.aggressiveAllowed() != 0;
    t.exclusion[r] = e.exclusion();
    t.evaluatedQuantity[r] = e.evaluatedQuantity();
    t.allocated[r] = e.allocatedQuantity();
    t.passive[r] = e.passive() != 0;
    t.fillProbability[r] = e.fillProbability().mantissa();
    t.queueWaitNanos[r] = e.queueWaitNanos();
    t.impactCost[r] = e.impactCost().mantissa();
    t.passiveCost[r] = e.passiveCost().mantissa();
    t.aggressiveCost[r] = e.aggressiveCost().mantissa();
    t.chosenCost[r] = e.chosenCost().mantissa();
  }

  /** The last fully decoded decision; overwritten by the next one. */
  public CostTable decoded() {
    return decoded;
  }

  private static short flag(final boolean b) {
    return (short) (b ? 1 : 0);
  }
}
