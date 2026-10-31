package io.ratesalgo.refdata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal CSV reader for the fixtures: comma separated, no quoting, lines starting with {@code #}
 * are comments, the first other line is the header. Every row must have the header's width.
 */
final class Csv {

  private final Path path;
  private final List<String> header;
  private final Map<String, Integer> index;
  private final List<String[]> rows;
  private final List<Integer> lineNumbers;

  private Csv(
      final Path path,
      final List<String> header,
      final List<String[]> rows,
      final List<Integer> lineNumbers) {
    this.path = path;
    this.header = header;
    this.rows = rows;
    this.lineNumbers = lineNumbers;
    this.index = new HashMap<>();
    for (int i = 0; i < header.size(); i++) {
      index.put(header.get(i), i);
    }
  }

  static Csv read(final Path path) {
    final List<String> lines;
    try {
      lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RefDataException("cannot read " + path, e);
    }
    List<String> header = null;
    final List<String[]> rows = new ArrayList<>();
    final List<Integer> numbers = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      final String line = lines.get(i).strip();
      if (line.isEmpty() || line.charAt(0) == '#') {
        continue;
      }
      final String[] cells = line.split(",", -1);
      if (header == null) {
        header = List.of(cells);
        continue;
      }
      if (cells.length != header.size()) {
        throw new RefDataException(
            path.getFileName()
                + ":"
                + (i + 1)
                + ": expected "
                + header.size()
                + " columns, got "
                + cells.length);
      }
      rows.add(cells);
      numbers.add(i + 1);
    }
    if (header == null) {
      throw new RefDataException(path.getFileName() + ": no header line");
    }
    return new Csv(path, header, rows, numbers);
  }

  int size() {
    return rows.size();
  }

  String get(final int row, final String column) {
    final Integer col = index.get(column);
    if (col == null) {
      throw new RefDataException(path.getFileName() + ": no column '" + column + "' in " + header);
    }
    return rows.get(row)[col];
  }

  String where(final int row) {
    return path.getFileName() + ":" + lineNumbers.get(row);
  }
}
