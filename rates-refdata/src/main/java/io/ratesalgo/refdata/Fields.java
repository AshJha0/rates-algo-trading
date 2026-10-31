package io.ratesalgo.refdata;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Typed cell parsing with the file and line in every error. */
final class Fields {

  private Fields() {}

  static int integer(final Csv csv, final int row, final String column) {
    final String v = csv.get(row, column);
    try {
      return Integer.parseInt(v);
    } catch (NumberFormatException e) {
      throw new RefDataException(csv.where(row) + ": " + column + " '" + v + "' is not an integer", e);
    }
  }

  static long longValue(final Csv csv, final int row, final String column) {
    final String v = csv.get(row, column);
    try {
      return Long.parseLong(v);
    } catch (NumberFormatException e) {
      throw new RefDataException(csv.where(row) + ": " + column + " '" + v + "' is not an integer", e);
    }
  }

  static LocalDate date(final Csv csv, final int row, final String column) {
    final String v = csv.get(row, column);
    try {
      return LocalDate.parse(v);
    } catch (DateTimeParseException e) {
      throw new RefDataException(csv.where(row) + ": " + column + " '" + v + "' is not an ISO date", e);
    }
  }

  static boolean bool(final Csv csv, final int row, final String column) {
    final String v = csv.get(row, column);
    if ("true".equals(v)) {
      return true;
    }
    if ("false".equals(v)) {
      return false;
    }
    throw new RefDataException(csv.where(row) + ": " + column + " '" + v + "' is not true or false");
  }

  /** Decimal text to a mantissa with exponent -9, exactly; more than nine decimals is an error. */
  static long decimal9(final Csv csv, final int row, final String column) {
    final String v = csv.get(row, column);
    try {
      return new java.math.BigDecimal(v).movePointRight(9).longValueExact();
    } catch (ArithmeticException | NumberFormatException e) {
      throw new RefDataException(
          csv.where(row) + ": " + column + " '" + v + "' is not a decimal with at most 9 places", e);
    }
  }
}
