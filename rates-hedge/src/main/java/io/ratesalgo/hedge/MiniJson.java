package io.ratesalgo.hedge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict reader for the JSON the calibration writes (RFC 8259 objects, arrays, numbers,
 * strings, true, false, null). Objects become insertion-ordered maps, arrays lists, numbers
 * doubles. The project has no JSON library in the trading modules and this file is read once at
 * start-up, so a small reader is cheaper than a dependency. Not for the hot path.
 */
final class MiniJson {

  private final String s;
  private int i;

  private MiniJson(final String text) {
    s = text;
  }

  /** Parses {@code text}; throws {@link IllegalArgumentException} with the offset on bad input. */
  static Object parse(final String text) {
    final MiniJson p = new MiniJson(text);
    p.space();
    final Object v = p.value();
    p.space();
    if (p.i != p.s.length()) {
      throw p.error("trailing characters");
    }
    return v;
  }

  private Object value() {
    if (i >= s.length()) {
      throw error("unexpected end");
    }
    return switch (s.charAt(i)) {
      case '{' -> object();
      case '[' -> array();
      case '"' -> string();
      case 't' -> literal("true", true);
      case 'f' -> literal("false", false);
      case 'n' -> literal("null", null);
      default -> number();
    };
  }

  private Map<String, Object> object() {
    final Map<String, Object> out = new LinkedHashMap<>();
    i++;
    space();
    if (peek('}')) {
      i++;
      return out;
    }
    while (true) {
      space();
      final String key = string();
      space();
      expect(':');
      space();
      out.put(key, value());
      space();
      if (peek(',')) {
        i++;
        continue;
      }
      expect('}');
      return out;
    }
  }

  private List<Object> array() {
    final List<Object> out = new ArrayList<>();
    i++;
    space();
    if (peek(']')) {
      i++;
      return out;
    }
    while (true) {
      space();
      out.add(value());
      space();
      if (peek(',')) {
        i++;
        continue;
      }
      expect(']');
      return out;
    }
  }

  private String string() {
    expect('"');
    final StringBuilder b = new StringBuilder();
    while (true) {
      if (i >= s.length()) {
        throw error("unterminated string");
      }
      final char c = s.charAt(i++);
      if (c == '"') {
        return b.toString();
      }
      if (c != '\\') {
        b.append(c);
        continue;
      }
      if (i >= s.length()) {
        throw error("unterminated escape");
      }
      final char e = s.charAt(i++);
      switch (e) {
        case 'n' -> b.append('\n');
        case 't' -> b.append('\t');
        case 'r' -> b.append('\r');
        case 'b' -> b.append('\b');
        case 'f' -> b.append('\f');
        case 'u' -> {
          if (i + 4 > s.length()) {
            throw error("short unicode escape");
          }
          b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
          i += 4;
        }
        default -> b.append(e);
      }
    }
  }

  private Double number() {
    final int start = i;
    while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
      i++;
    }
    if (start == i) {
      throw error("unexpected character '" + s.charAt(i) + "'");
    }
    try {
      return Double.valueOf(s.substring(start, i));
    } catch (final NumberFormatException e) {
      throw error("bad number " + s.substring(start, i));
    }
  }

  private Object literal(final String word, final Object v) {
    if (!s.startsWith(word, i)) {
      throw error("expected " + word);
    }
    i += word.length();
    return v;
  }

  private void space() {
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
      i++;
    }
  }

  private boolean peek(final char c) {
    return i < s.length() && s.charAt(i) == c;
  }

  private void expect(final char c) {
    if (!peek(c)) {
      throw error("expected '" + c + "'");
    }
    i++;
  }

  private IllegalArgumentException error(final String what) {
    return new IllegalArgumentException("JSON at offset " + i + ": " + what);
  }
}
