package io.ratesalgo.refdata;

/** Kinds of party in the party master. */
public enum PartyType {
  /** A dealer, including this desk. */
  DEALER,
  /** A buy-side client. */
  CLIENT,
  /** A trading venue. */
  VENUE,
  /** A futures commission merchant providing clearing limits. */
  FCM
}
