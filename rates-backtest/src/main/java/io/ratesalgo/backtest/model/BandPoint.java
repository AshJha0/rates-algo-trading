package io.ratesalgo.backtest.model;

import java.util.Locale;

/** The three points of a declared assumption band at which every result is reported (DR-008). */
public enum BandPoint {
  LOW,
  CENTRAL,
  HIGH;

  public String label() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static BandPoint parse(final String text) {
    return valueOf(text.trim().toUpperCase(Locale.ROOT));
  }
}
