package io.ratesalgo.refdata;

/** Life-cycle state of a bond on a given date (API_SPEC.md Section 11). */
public enum BondStatus {
  /** Before the auction announcement: not tradable. */
  NOT_ANNOUNCED,
  /** Between announcement and issue: trades when-issued for settlement on or after issue. */
  WHEN_ISSUED,
  /** The newest issued bond of its market and tenor. */
  ON_THE_RUN,
  /** Issued, superseded by a newer issue of the same market and tenor. */
  OFF_THE_RUN,
  /** On or after maturity. */
  MATURED
}
