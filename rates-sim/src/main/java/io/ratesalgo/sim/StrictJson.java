package io.ratesalgo.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small strict JSON reader for the two parameter files. Objects become insertion-ordered maps,
 * arrays lists, numbers doubles. A duplicate key, a trailing comma, a non-finite number or text
 * after the document is an error: a parameter file that is not exactly what it claims to be must
 * not load.
 */
final class StrictJson {

  private final String text;
  private int at;

  private StrictJson(final String text) {
    this.text = text;
  }

  static Object parse(final String text) {
    final StrictJson p = new StrictJson(text);
    final Object value = p.value();
    p.blank();
    if (p.at != text.length()) {
      throw p.fail("text after the document");
    }
    return value;
  }

  private IllegalArgumentException fail(final String what) {
    return new IllegalArgumentException("JSON: " + what + " at offset " + at);
  }

  private void blank() {
    while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
      at++;
    }
  }

  private char peek() {
    blank();
    if (at >= text.length()) {
      throw fail("unexpected end");
    }
    return text.charAt(at);
  }

  private Object value() {
    final char c = peek();
    if (c == '{') {
      return object();
    }
    if (c == '[') {
      return array();
    }
    if (c == '"') {
      return string();
    }
    if (text.startsWith("true", at)) {
      at += 4;
      return true;
    }
    if (text.startsWith("false", at)) {
      at += 5;
      return false;
    }
    if (text.startsWith("null", at)) {
      at += 4;
      return null;
    }
    return number();
  }

  private Map<String, Object> object() {
    final Map<String, Object> out = new LinkedHashMap<>();
    at++;
    if (peek() == '}') {
      at++;
      return out;
    }
    while (true) {
      if (peek() != '"') {
        throw fail("object key expected");
      }
      final String key = string();
      if (peek() != ':') {
        throw fail("':' expected");
      }
      at++;
      final Object v = value();
      if (out.containsKey(key)) {
        throw fail("duplicate key '" + key + "'");
      }
      out.put(key, v);
      final char c = peek();
      at++;
      if (c == '}') {
        return out;
      }
      if (c != ',') {
        throw fail("',' or '}' expected");
      }
    }
  }

  private List<Object> array() {
    final List<Object> out = new ArrayList<>();
    at++;
    if (peek() == ']') {
      at++;
      return out;
    }
    while (true) {
      out.add(value());
      final char c = peek();
      at++;
      if (c == ']') {
        return out;
      }
      if (c != ',') {
        throw fail("',' or ']' expected");
      }
    }
  }

  private String string() {
    final StringBuilder out = new StringBuilder();
    at++;
    while (true) {
      if (at >= text.length()) {
        throw fail("unterminated string");
      }
      final char c = text.charAt(at++);
      if (c == '"') {
        return out.toString();
      }
      if (c != '\\') {
        out.append(c);
        continue;
      }
      if (at >= text.length()) {
        throw fail("unterminated escape");
      }
      final char e = text.charAt(at++);
      switch (e) {
        case '"', '\\', '/' -> out.append(e);
        case 'n' -> out.append('\n');
        case 't' -> out.append('\t');
        case 'r' -> out.append('\r');
        default -> throw fail("unsupported escape '\\" + e + "'");
      }
    }
  }

  private Double number() {
    final int start = at;
    while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
      at++;
    }
    if (at == start) {
      throw fail("value expected");
    }
    final double d;
    try {
      d = Double.parseDouble(text.substring(start, at));
    } catch (final NumberFormatException ex) {
      at = start;
      throw fail("bad number");
    }
    if (Double.isInfinite(d)) {
      throw fail("non-finite number");
    }
    return d;
  }
}
