package io.ratesalgo.backtest.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * Safe YAML (and JSON) loading with typed accessors that throw with the key and file named,
 * rather than returning null.
 *
 * <p>Plain scalars that look like dates ({@code 2026-02-02}) stay strings: the implicit timestamp
 * type is not resolved, so dates are parsed explicitly with {@code LocalDate.parse} where a schema
 * expects one, and no {@code java.util.Date} appears.
 */
public final class Yaml {

  private Yaml() {}

  /** Resolves booleans, integers, floats, nulls and merge keys, but not timestamps. */
  private static final class NoTimestampResolver extends Resolver {
    @Override
    protected void addImplicitResolvers() {
      addImplicitResolver(org.yaml.snakeyaml.nodes.Tag.BOOL, BOOL, "yYnNtTfFoO");
      addImplicitResolver(org.yaml.snakeyaml.nodes.Tag.INT, INT, "-+0123456789");
      addImplicitResolver(org.yaml.snakeyaml.nodes.Tag.FLOAT, FLOAT, "-+0123456789.");
      addImplicitResolver(org.yaml.snakeyaml.nodes.Tag.MERGE, MERGE, "<");
      addImplicitResolver(org.yaml.snakeyaml.nodes.Tag.NULL, NULL, "~nN\0");
      addImplicitResolver(org.yaml.snakeyaml.nodes.Tag.NULL, EMPTY, null);
    }
  }

  /** Parses text with SnakeYAML's safe constructor (no arbitrary types, no timestamps). */
  public static Object parse(final String text) {
    final LoaderOptions options = new LoaderOptions();
    final DumperOptions dumper = new DumperOptions();
    final org.yaml.snakeyaml.Yaml yaml =
        new org.yaml.snakeyaml.Yaml(
            new SafeConstructor(options), new Representer(dumper), dumper, options, new NoTimestampResolver());
    return yaml.load(text);
  }

  public static Map<String, Object> parseMap(final String text, final String where) {
    return asMap(parse(text), where);
  }

  public static Map<String, Object> loadMap(final Path path) {
    try {
      return parseMap(Files.readString(path, StandardCharsets.UTF_8), path.toString());
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot read " + path, e);
    }
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> asMap(final Object value, final String where) {
    if (value instanceof Map<?, ?> m) {
      return (Map<String, Object>) m;
    }
    throw new IllegalArgumentException(where + ": expected a mapping, found " + describe(value));
  }

  @SuppressWarnings("unchecked")
  public static List<Object> asList(final Object value, final String where) {
    if (value instanceof List<?> l) {
      return (List<Object>) l;
    }
    throw new IllegalArgumentException(where + ": expected a list, found " + describe(value));
  }

  public static Object require(final Map<String, Object> map, final String key, final String where) {
    final Object v = map.get(key);
    if (v == null) {
      throw new IllegalArgumentException(where + ": missing key '" + key + "'");
    }
    return v;
  }

  public static Map<String, Object> map(final Map<String, Object> map, final String key, final String where) {
    return asMap(require(map, key, where), where + "." + key);
  }

  public static String string(final Map<String, Object> map, final String key, final String where) {
    final Object v = require(map, key, where);
    if (v instanceof String s) {
      if (s.isBlank()) {
        throw new IllegalArgumentException(where + ": key '" + key + "' is blank");
      }
      return s;
    }
    if (v instanceof Number || v instanceof Boolean) {
      return String.valueOf(v);
    }
    throw new IllegalArgumentException(where + ": key '" + key + "' is not text: " + describe(v));
  }

  public static double number(final Map<String, Object> map, final String key, final String where) {
    return toDouble(require(map, key, where), where + "." + key);
  }

  public static double number(final Map<String, Object> map, final String key, final double fallback) {
    final Object v = map.get(key);
    return v == null ? fallback : toDouble(v, key);
  }

  public static long integer(final Map<String, Object> map, final String key, final String where) {
    final Object v = require(map, key, where);
    if (v instanceof Integer || v instanceof Long) {
      return ((Number) v).longValue();
    }
    throw new IllegalArgumentException(where + ": key '" + key + "' is not an integer: " + describe(v));
  }

  public static double toDouble(final Object v, final String where) {
    if (v instanceof Number n) {
      final double d = n.doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        throw new IllegalArgumentException(where + ": number is not finite");
      }
      return d;
    }
    throw new IllegalArgumentException(where + ": expected a number, found " + describe(v));
  }

  private static String describe(final Object v) {
    return v == null ? "nothing" : v.getClass().getSimpleName() + " " + v;
  }
}
