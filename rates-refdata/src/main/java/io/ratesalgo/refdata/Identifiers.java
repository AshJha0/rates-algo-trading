package io.ratesalgo.refdata;

/** Check-digit validation for ISIN (ISO 6166) and CUSIP identifiers. */
public final class Identifiers {

  private Identifiers() {}

  /** True if the string is two letters, nine alphanumerics and a valid check digit. */
  public static boolean isValidIsin(final String isin) {
    if (isin == null || isin.length() != 12) {
      return false;
    }
    if (!isUpper(isin.charAt(0)) || !isUpper(isin.charAt(1))) {
      return false;
    }
    final StringBuilder digits = new StringBuilder(24);
    for (int i = 0; i < 11; i++) {
      final char c = isin.charAt(i);
      if (c >= '0' && c <= '9') {
        digits.append(c);
      } else if (isUpper(c)) {
        digits.append(c - 'A' + 10);
      } else {
        return false;
      }
    }
    final char check = isin.charAt(11);
    if (check < '0' || check > '9') {
      return false;
    }
    int total = 0;
    for (int i = digits.length() - 1, pos = 0; i >= 0; i--, pos++) {
      int v = digits.charAt(i) - '0';
      if (pos % 2 == 0) {
        v *= 2;
      }
      total += v / 10 + v % 10;
    }
    return (10 - total % 10) % 10 == check - '0';
  }

  /** True if the string is nine characters with a valid CUSIP check digit. */
  public static boolean isValidCusip(final String cusip) {
    if (cusip == null || cusip.length() != 9) {
      return false;
    }
    int total = 0;
    for (int i = 0; i < 8; i++) {
      final int v0 = cusipValue(cusip.charAt(i));
      if (v0 < 0) {
        return false;
      }
      final int v = (i & 1) == 1 ? v0 * 2 : v0;
      total += v / 10 + v % 10;
    }
    final char check = cusip.charAt(8);
    return check >= '0' && check <= '9' && (10 - total % 10) % 10 == check - '0';
  }

  private static int cusipValue(final char c) {
    if (c >= '0' && c <= '9') {
      return c - '0';
    }
    if (isUpper(c)) {
      return c - 'A' + 10;
    }
    return switch (c) {
      case '*' -> 36;
      case '@' -> 37;
      case '#' -> 38;
      default -> -1;
    };
  }

  private static boolean isUpper(final char c) {
    return c >= 'A' && c <= 'Z';
  }
}
