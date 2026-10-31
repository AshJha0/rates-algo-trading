package io.ratesalgo.api;

/** The currencies P3 quotes and risk-manages. {@link #index()} is a dense array index. */
public enum CurrencyCode {
  USD(0, 'U', 'S', 'D'),
  EUR(1, 'E', 'U', 'R'),
  GBP(2, 'G', 'B', 'P');

  /** Number of currencies, for sizing arrays without calling {@code values()}. */
  public static final int COUNT = 3;

  private final int index;
  private final byte c0;
  private final byte c1;
  private final byte c2;

  CurrencyCode(final int index, final char c0, final char c1, final char c2) {
    this.index = index;
    this.c0 = (byte) c0;
    this.c1 = (byte) c1;
    this.c2 = (byte) c2;
  }

  /** Dense index in {@code [0, COUNT)}, stable across releases (unlike the ordinal). */
  public int index() {
    return index;
  }

  /** True if the three ASCII bytes spell this currency; allocation-free. */
  public boolean matches(final byte b0, final byte b1, final byte b2) {
    return b0 == c0 && b1 == c1 && b2 == c2;
  }

  /** The currency with the given index, without allocating. */
  public static CurrencyCode ofIndex(final int index) {
    return switch (index) {
      case 0 -> USD;
      case 1 -> EUR;
      case 2 -> GBP;
      default -> throw new IllegalArgumentException("no currency with index " + index);
    };
  }
}
