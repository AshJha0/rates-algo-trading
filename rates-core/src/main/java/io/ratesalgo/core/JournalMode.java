package io.ratesalgo.core;

/**
 * How a loop's journals reach their files (API_SPEC.md Section 9.7, DECISIONS.md DR-R2J-03). Both
 * modes write the same RAJ1 segments, frame for frame, and replay reads either.
 */
public enum JournalMode {

  /**
   * The loop thread appends each frame to the memory-mapped segment itself: payload copy, chain
   * hash, header, length written last. Segments are prepared and every page touched on a
   * background thread, so the append is a memory copy with no system call.
   */
  DIRECT,

  /**
   * The loop thread copies each frame into an off-heap single-producer ring and a journal thread
   * per journal writes it to the mapped segment, computing the chain hash there. The loop thread
   * never touches the mapped file; a full ring makes it wait, counted, rather than drop a frame.
   */
  RING
}
