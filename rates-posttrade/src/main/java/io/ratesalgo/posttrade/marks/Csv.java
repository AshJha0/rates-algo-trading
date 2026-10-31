package io.ratesalgo.posttrade.marks;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Minimal CSV reading for the fixture and attribution files: comma-separated, no quoting. */
final class Csv {

  private static final Pattern COMMA = Pattern.compile(",");

  private Csv() {}

  /**
   * Reads every data row of a file whose first non-comment line is {@code expectedHeader}. Blank
   * lines and lines starting with {@code #} are skipped.
   */
  static List<String[]> read(final Reader source, final String expectedHeader, final String name) {
    final List<String[]> rows = new ArrayList<>();
    final int columns = COMMA.split(expectedHeader, -1).length;
    try (BufferedReader r = new BufferedReader(source)) {
      boolean headerSeen = false;
      int lineNo = 0;
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        lineNo++;
        final String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        if (!headerSeen) {
          if (!trimmed.equals(expectedHeader)) {
            throw new IllegalArgumentException(
                name + " line " + lineNo + ": header '" + trimmed + "', expected '" + expectedHeader + "'");
          }
          headerSeen = true;
          continue;
        }
        final String[] f = COMMA.split(trimmed, -1);
        if (f.length != columns) {
          throw new IllegalArgumentException(
              name + " line " + lineNo + ": " + f.length + " fields, expected " + columns);
        }
        rows.add(f);
      }
      if (!headerSeen) {
        throw new IllegalArgumentException(name + ": no header line");
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    return rows;
  }
}
