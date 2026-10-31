package io.ratesalgo.posttrade.marks;

import java.util.Locale;

/** Components of official daily P&amp;L, as named in the attribution file (API_SPEC.md 17.5). */
public enum PnlComponent {
  /** Closed against the average cost during the day. */
  REALISED,
  /** Open quantity revalued from average cost to the official close. */
  UNREALISED,
  /** Accrual on the closing position. */
  CARRY;

  /** The lower-case name used in files. */
  public String fileName() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Parses {@link #fileName()}. */
  public static PnlComponent parse(final String text) {
    return valueOf(text.toUpperCase(Locale.ROOT));
  }
}
