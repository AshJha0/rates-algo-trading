package io.ratesalgo.venues.sim;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader for presets and certification scripts. Objects keep key order (a script's
 * fields are written to FIX in the order given), numbers are {@link BigDecimal}.
 */
public final class Json {

  private final String text;
  private int pos;

  private Json(final String text) {
    this.text = text;
  }

  /** Parses a JSON document. */
  public static Object parse(final String text) {
    final Json j = new Json(text);
    j.ws();
    final Object v = j.value();
    j.ws();
    if (j.pos != text.length()) {
      throw j.error("trailing characters");
    }
    return v;
  }

  private IllegalArgumentException error(final String what) {
    return new IllegalArgumentException("JSON " + what + " at offset " + pos);
  }

  private void ws() {
    while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
      pos++;
    }
  }

  private Object value() {
    if (pos >= text.length()) {
      throw error("unexpected end");
    }
    final char c = text.charAt(pos);
    if (c == '{') {
      return object();
    }
    if (c == '[') {
      return array();
    }
    if (c == '"') {
      return string();
    }
    if (text.startsWith("true", pos)) {
      pos += 4;
      return Boolean.TRUE;
    }
    if (text.startsWith("false", pos)) {
      pos += 5;
      return Boolean.FALSE;
    }
    if (text.startsWith("null", pos)) {
      pos += 4;
      return null;
    }
    return number();
  }

  private Map<String, Object> object() {
    final Map<String, Object> map = new LinkedHashMap<>();
    pos++;
    ws();
    if (peek() == '}') {
      pos++;
      return map;
    }
    while (true) {
      ws();
      if (peek() != '"') {
        throw error("expected a key");
      }
      final String key = string();
      ws();
      expect(':');
      ws();
      map.put(key, value());
      ws();
      if (peek() == ',') {
        pos++;
        continue;
      }
      expect('}');
      return map;
    }
  }

  private List<Object> array() {
    final List<Object> list = new ArrayList<>();
    pos++;
    ws();
    if (peek() == ']') {
      pos++;
      return list;
    }
    while (true) {
      ws();
      list.add(value());
      ws();
      if (peek() == ',') {
        pos++;
        continue;
      }
      expect(']');
      return list;
    }
  }

  private char peek() {
    if (pos >= text.length()) {
      throw error("unexpected end");
    }
    return text.charAt(pos);
  }

  private void expect(final char c) {
    if (peek() != c) {
      throw error("expected '" + c + "'");
    }
    pos++;
  }

  private String string() {
    pos++;
    final StringBuilder sb = new StringBuilder();
    while (true) {
      final char c = peek();
      pos++;
      if (c == '"') {
        return sb.toString();
      }
      if (c == '\\') {
        final char e = peek();
        pos++;
        switch (e) {
          case 'n' -> sb.append('\n');
          case 't' -> sb.append('\t');
          case 'r' -> sb.append('\r');
          case 'u' -> {
            if (pos + 4 > text.length()) {
              throw error("bad unicode escape");
            }
            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
            pos += 4;
          }
          default -> sb.append(e);
        }
      } else {
        sb.append(c);
      }
    }
  }

  private BigDecimal number() {
    final int start = pos;
    while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
      pos++;
    }
    if (start == pos) {
      throw error("unexpected character '" + text.charAt(pos) + "'");
    }
    return new BigDecimal(text.substring(start, pos));
  }

  /** Typed access helpers for parsed documents. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> obj(final Object o, final String key) {
    final Object v = ((Map<String, Object>) o).get(key);
    if (!(v instanceof Map)) {
      throw new IllegalArgumentException("JSON key '" + key + "' is not an object");
    }
    return (Map<String, Object>) v;
  }

  /** List under a key. */
  @SuppressWarnings("unchecked")
  public static List<Object> list(final Object o, final String key) {
    final Object v = ((Map<String, Object>) o).get(key);
    if (!(v instanceof List)) {
      throw new IllegalArgumentException("JSON key '" + key + "' is not an array");
    }
    return (List<Object>) v;
  }

  /** Number under a key. */
  @SuppressWarnings("unchecked")
  public static BigDecimal num(final Object o, final String key) {
    final Object v = ((Map<String, Object>) o).get(key);
    if (v instanceof BigDecimal b) {
      return b;
    }
    if (v instanceof String s) {
      return new BigDecimal(s);
    }
    throw new IllegalArgumentException("JSON key '" + key + "' is not a number");
  }

  /** String under a key. */
  @SuppressWarnings("unchecked")
  public static String str(final Object o, final String key) {
    final Object v = ((Map<String, Object>) o).get(key);
    if (v == null) {
      throw new IllegalArgumentException("JSON key '" + key + "' is missing");
    }
    return v instanceof BigDecimal b ? b.toPlainString() : v.toString();
  }
}
