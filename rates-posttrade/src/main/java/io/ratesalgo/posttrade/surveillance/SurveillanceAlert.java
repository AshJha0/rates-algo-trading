package io.ratesalgo.posttrade.surveillance;

/**
 * One alert for the surveillance team. Alerts are leads, not findings: a person reviews each.
 *
 * @param detector name of the detector
 * @param instrumentId instrument concerned
 * @param journalSequence the frame that raised it
 * @param timestampNanos that frame's timestamp
 * @param detail what was seen
 */
public record SurveillanceAlert(
    String detector, int instrumentId, long journalSequence, long timestampNanos, String detail) {}
