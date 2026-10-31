package io.ratesalgo.backtest.util;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A small JSON writer for results, reports and ledger lines.
 *
 * <p>Output is canonical so that hashes of it are stable: object keys are sorted, there is no
 * whitespace, integral doubles are written as {@code 12.0} and others as the shortest round-trip
 * text of {@link Double#toString}, and non-finite doubles are refused (no silent NaN). Reading goes through
 * SnakeYAML, which accepts JSON.
 */
public final class Json {

  private Json() {}

  /** Writes maps, collections, strings, numbers, booleans and null. */
  public static String write(final Object value) {
    final StringBuilder out = new StringBuilder(256);
    append(out, value);
    return out.toString();
  }

  private static void append(final StringBuilder out, final Object value) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof String s) {
      string(out, s);
    } else if (value instanceof Double d) {
      number(out, d);
    } else if (value instanceof Float f) {
      number(out, f.doubleValue());
    } else if (value instanceof Number n) {
      out.append(n.longValue());
    } else if (value instanceof Boolean b) {
      out.append(b.booleanValue() ? "true" : "false");
    } else if (value instanceof Map<?, ?> map) {
      final TreeMap<String, Object> sorted = new TreeMap<>();
      for (final Map.Entry<?, ?> e : map.entrySet()) {
        sorted.put(String.valueOf(e.getKey()), e.getValue());
      }
      out.append('{');
      boolean first = true;
      for (final Map.Entry<String, Object> e : sorted.entrySet()) {
        if (!first) {
          out.append(',');
        }
        first = false;
        string(out, e.getKey());
        out.append(':');
        append(out, e.getValue());
      }
      out.append('}');
    } else if (value instanceof Collection<?> list) {
      out.append('[');
      boolean first = true;
      for (final Object item : list) {
        if (!first) {
          out.append(',');
        }
        first = false;
        append(out, item);
      }
      out.append(']');
    } else if (value instanceof double[] arr) {
      out.append('[');
      for (int i = 0; i < arr.length; i++) {
        if (i > 0) {
          out.append(',');
        }
        number(out, arr[i]);
      }
      out.append(']');
    } else {
      throw new IllegalArgumentException("cannot write " + value.getClass().getName() + " as JSON");
    }
  }

  private static void number(final StringBuilder out, final double d) {
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      throw new IllegalArgumentException("non-finite number in JSON output: " + d);
    }
    if (d == Math.rint(d) && Math.abs(d) < 1e15) {
      out.append(String.format(Locale.ROOT, "%.1f", d));
    } else {
      // "1.5E7" is a string to a YAML 1.1 reader; "1.5E+7" is a number to JSON and YAML alike
      final String text = Double.toString(d);
      final int e = text.indexOf('E');
      out.append(e >= 0 && text.charAt(e + 1) != '-' ? text.substring(0, e + 1) + '+' + text.substring(e + 1) : text);
    }
  }

  private static void string(final StringBuilder out, final String s) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      final char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
