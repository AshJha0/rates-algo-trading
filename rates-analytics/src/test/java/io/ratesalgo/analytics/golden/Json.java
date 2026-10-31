package io.ratesalgo.analytics.golden;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader for the golden file (objects, arrays, strings, numbers, booleans, null).
 * Numbers are read with {@link Double#parseDouble}, which round-trips the shortest representation
 * Python writes, so golden values arrive bit for bit.
 */
final class Json {

  private final String s;
  private int pos;

  private Json(final String text) {
    this.s = text;
  }

  static Object parse(final String text) {
    final Json j = new Json(text);
    final Object v = j.value();
    j.ws();
    if (j.pos != j.s.length()) {
      throw new IllegalArgumentException("trailing content at " + j.pos);
    }
    return v;
  }

  private void ws() {
    while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
      pos++;
    }
  }

  private Object value() {
    ws();
    final char c = s.charAt(pos);
    if (c == '{') {
      return object();
    }
    if (c == '[') {
      return array();
    }
    if (c == '"') {
      return string();
    }
    if (s.startsWith("true", pos)) {
      pos += 4;
      return true;
    }
    if (s.startsWith("false", pos)) {
      pos += 5;
      return false;
    }
    if (s.startsWith("null", pos)) {
      pos += 4;
      return null;
    }
    final int start = pos;
    while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
      pos++;
    }
    return Double.parseDouble(s.substring(start, pos));
  }

  private Map<String, Object> object() {
    final Map<String, Object> m = new LinkedHashMap<>();
    pos++;
    ws();
    if (s.charAt(pos) == '}') {
      pos++;
      return m;
    }
    for (; ; ) {
      ws();
      final String k = string();
      ws();
      pos++; // ':'
      m.put(k, value());
      ws();
      if (s.charAt(pos++) == '}') {
        return m;
      }
    }
  }

  private List<Object> array() {
    final List<Object> l = new ArrayList<>();
    pos++;
    ws();
    if (s.charAt(pos) == ']') {
      pos++;
      return l;
    }
    for (; ; ) {
      l.add(value());
      ws();
      if (s.charAt(pos++) == ']') {
        return l;
      }
    }
  }

  private String string() {
    final StringBuilder sb = new StringBuilder();
    pos++;
    for (; ; ) {
      final char c = s.charAt(pos++);
      if (c == '"') {
        return sb.toString();
      }
      if (c == '\\') {
        final char e = s.charAt(pos++);
        switch (e) {
          case 'n' -> sb.append('\n');
          case 't' -> sb.append('\t');
          case 'u' -> {
            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
            pos += 4;
          }
          default -> sb.append(e);
        }
      } else {
        sb.append(c);
      }
    }
  }
}
