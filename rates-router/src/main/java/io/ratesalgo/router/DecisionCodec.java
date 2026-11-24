package io.ratesalgo.router;

import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.RouteDecisionDecoder;
import io.ratesalgo.messages.sbe.RouteDecisionEncoder;
import io.ratesalgo.messages.sbe.RouteDecisionLadderDecoder;
import io.ratesalgo.messages.sbe.RouteDecisionLadderEncoder;
import io.ratesalgo.messages.sbe.RouteDecisionModelDecoder;
import io.ratesalgo.messages.sbe.RouteDecisionModelEncoder;
import io.ratesalgo.messages.sbe.RouteDecisionVenueDecoder;
import io.ratesalgo.messages.sbe.RouteDecisionVenueEncoder;
import java.util.Arrays;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Writes a {@link CostTable} as one RouteDecision (template 80) followed by one
 * RouteDecisionVenue (template 81) per row, and reads it back. Encoding does not allocate.
 *
 * <p>A decision taken with the ladder model also carries, before each RouteDecisionVenue row, one
 * RouteDecisionModel (template 84) and that row's RouteDecisionLadder levels (template 83). A
 * touch-model decision has none, so its frames are exactly those of earlier releases.
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
  private final RouteDecisionModelEncoder modelEncoder = new RouteDecisionModelEncoder();
  private final RouteDecisionLadderEncoder ladderEncoder = new RouteDecisionLadderEncoder();

  private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
  private final RouteDecisionDecoder decisionDecoder = new RouteDecisionDecoder();
  private final RouteDecisionVenueDecoder venueDecoder = new RouteDecisionVenueDecoder();
  private final RouteDecisionModelDecoder modelDecoder = new RouteDecisionModelDecoder();
  private final RouteDecisionLadderDecoder ladderDecoder = new RouteDecisionLadderDecoder();
  private final CostTable decoded = new CostTable();
  private int rowsSeen;
  private boolean inDecision;
  private boolean modelSeen;
  private int levelsSeen;

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
      if (t.ladder) {
        writeModel(t, r, sink);
      }
      writeRow(t, r, sink);
    }
  }

  private void writeModel(final CostTable t, final int r, final FrameSink sink) {
    final RouteDecisionModelEncoder e = modelEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
    e.decisionId(t.decisionId)
        .row((short) r)
        .modelVersion(t.modelVersion)
        .levelCount((short) t.levelCount[r]);
    e.limitPrice().mantissa(t.limitPrice[r]);
    e.ownOpposite().mantissa(t.ownOpposite[r]);
    e.rejectRate().mantissa(t.rejectRate[r]);
    e.cancelRejectRate().mantissa(t.cancelRejectRate[r]);
    e.fillRatio().mantissa(t.fillRatio[r]);
    e.markout1s().mantissa(t.markout1s[r]);
    e.markout5s().mantissa(t.markout5s[r]);
    e.markout30s().mantissa(t.markout30s[r]);
    e.ackLatencyNanos(t.ackLatencyNanos[r]);
    e.cancelRate().mantissa(t.cancelRate[r]);
    e.calibrationObserved().mantissa(t.calObserved[r]);
    e.calibrationPredicted().mantissa(t.calPredicted[r]);
    e.queueBucket((short) t.queueBucket[r]);
    e.penalty().mantissa(t.penalty[r]);
    e.sweepQuantity(t.sweepQuantity[r]);
    e.childLimit().mantissa(t.childLimit[r]);
    e.walkCost().mantissa(t.walkCost[r]);
    e.riskCost().mantissa(t.riskCost[r]);
    sink.append(
        RouteDecisionModelEncoder.TEMPLATE_ID,
        RouteDecisionModelEncoder.SCHEMA_VERSION,
        buffer,
        0,
        MessageHeaderEncoder.ENCODED_LENGTH + RouteDecisionModelEncoder.BLOCK_LENGTH);
    for (int k = 0; k < t.levelCount[r]; k++) {
      final int i = r * CostTable.MAX_LEVELS + k;
      final RouteDecisionLadderEncoder l =
          ladderEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
      l.decisionId(t.decisionId).row((short) r).level((short) k);
      l.price().mantissa(t.levelPrice[i]);
      l.displayed(t.levelDisplayed[i]).own(t.levelOwn[i]);
      sink.append(
          RouteDecisionLadderEncoder.TEMPLATE_ID,
          RouteDecisionLadderEncoder.SCHEMA_VERSION,
          buffer,
          0,
          MessageHeaderEncoder.ENCODED_LENGTH + RouteDecisionLadderEncoder.BLOCK_LENGTH);
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
        && templateId != RouteDecisionVenueDecoder.TEMPLATE_ID
        && templateId != RouteDecisionModelDecoder.TEMPLATE_ID
        && templateId != RouteDecisionLadderDecoder.TEMPLATE_ID) {
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
    if (templateId == RouteDecisionModelDecoder.TEMPLATE_ID) {
      readModel(src, offset);
      return false;
    }
    if (templateId == RouteDecisionLadderDecoder.TEMPLATE_ID) {
      readLevel(src, offset);
      return false;
    }
    venueDecoder.wrapAndApplyHeader(src, offset, headerDecoder);
    final int r = venueDecoder.row();
    if (!inDecision
        || venueDecoder.decisionId() != decoded.decisionId
        || r != rowsSeen
        || r >= CostTable.MAX_VENUES
        || (decoded.ladder && (!modelSeen || levelsSeen != decoded.levelCount[r]))) {
      throw new IllegalStateException(
          "RouteDecisionVenue row " + r + " of decision " + venueDecoder.decisionId()
              + " out of sequence (expected row " + rowsSeen + " of " + decoded.decisionId + ")");
    }
    readRow(r);
    rowsSeen++;
    modelSeen = false;
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
    modelSeen = false;
    t.ladder = false;
    t.modelVersion = 0;
    Arrays.fill(t.levelCount, 0);
  }

  private void readModel(final DirectBuffer src, final int offset) {
    final RouteDecisionModelDecoder e = modelDecoder.wrapAndApplyHeader(src, offset, headerDecoder);
    final int r = e.row();
    if (!inDecision
        || e.decisionId() != decoded.decisionId
        || r != rowsSeen
        || modelSeen
        || e.levelCount() > CostTable.MAX_LEVELS
        || (rowsSeen > 0 && !decoded.ladder)) {
      throw new IllegalStateException(
          "RouteDecisionModel row " + r + " of decision " + e.decisionId() + " out of sequence");
    }
    final CostTable t = decoded;
    t.ladder = true;
    t.modelVersion = e.modelVersion();
    t.levelCount[r] = e.levelCount();
    t.limitPrice[r] = e.limitPrice().mantissa();
    t.ownOpposite[r] = e.ownOpposite().mantissa();
    t.rejectRate[r] = e.rejectRate().mantissa();
    t.cancelRejectRate[r] = e.cancelRejectRate().mantissa();
    t.fillRatio[r] = e.fillRatio().mantissa();
    t.markout1s[r] = e.markout1s().mantissa();
    t.markout5s[r] = e.markout5s().mantissa();
    t.markout30s[r] = e.markout30s().mantissa();
    t.ackLatencyNanos[r] = e.ackLatencyNanos();
    t.cancelRate[r] = e.cancelRate().mantissa();
    t.calObserved[r] = e.calibrationObserved().mantissa();
    t.calPredicted[r] = e.calibrationPredicted().mantissa();
    t.queueBucket[r] = e.queueBucket();
    t.penalty[r] = e.penalty().mantissa();
    t.sweepQuantity[r] = e.sweepQuantity();
    t.childLimit[r] = e.childLimit().mantissa();
    t.walkCost[r] = e.walkCost().mantissa();
    t.riskCost[r] = e.riskCost().mantissa();
    modelSeen = true;
    levelsSeen = 0;
  }

  private void readLevel(final DirectBuffer src, final int offset) {
    final RouteDecisionLadderDecoder e =
        ladderDecoder.wrapAndApplyHeader(src, offset, headerDecoder);
    final int r = e.row();
    if (!modelSeen
        || e.decisionId() != decoded.decisionId
        || r != rowsSeen
        || e.level() != levelsSeen
        || levelsSeen >= decoded.levelCount[r]) {
      throw new IllegalStateException(
          "RouteDecisionLadder level " + e.level() + " of row " + r + " out of sequence");
    }
    final int i = r * CostTable.MAX_LEVELS + levelsSeen;
    decoded.levelPrice[i] = e.price().mantissa();
    decoded.levelDisplayed[i] = e.displayed();
    decoded.levelOwn[i] = e.own();
    levelsSeen++;
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
