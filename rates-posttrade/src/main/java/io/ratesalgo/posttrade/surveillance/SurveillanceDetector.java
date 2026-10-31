package io.ratesalgo.posttrade.surveillance;

import io.ratesalgo.journal.FrameHandler;
import java.util.List;

/**
 * A market-abuse surveillance hook over journal frames (RTS 6 Article 13; API_SPEC.md Section
 * 17.10). A detector reads frames in order, live from a journal tail or in a batch replay, and
 * accumulates alerts. Detectors never act on the trading process; they report.
 */
public interface SurveillanceDetector extends FrameHandler {

  /** Short stable name used in alerts. */
  String name();

  /** Alerts raised so far, oldest first. */
  List<SurveillanceAlert> alerts();
}
