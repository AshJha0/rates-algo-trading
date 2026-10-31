package io.ratesalgo.ops.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A validator for the subset of JSON Schema (draft 2020-12) that the configuration schema uses
 * (API_SPEC.md Section 17.11): {@code type}, {@code properties}, {@code required},
 * {@code additionalProperties: false}, {@code enum}, {@code const}, {@code minimum},
 * {@code maximum}, {@code minLength}, {@code pattern}, {@code items}, {@code minItems},
 * {@code maxItems}. Annotation keywords ({@code $schema}, {@code $id}, {@code title},
 * {@code description}) are accepted and ignored. Any other keyword is refused when the schema is
 * loaded, so a schema can never rely on a rule this validator would silently skip. CI validates
 * the same files with the Python {@code jsonschema} package as a cross-check.
 */
public final class JsonSchema {

  private static final Set<String> KNOWN = Set.of("type", "properties", "required",
      "additionalProperties", "enum", "const", "minimum", "maximum", "minLength", "pattern", "items",
      "minItems", "maxItems", "$schema", "$id", "title", "description");

  private final Map<String, Object> root;

  /** Wraps a parsed schema, refusing unsupported keywords. */
  public JsonSchema(final Map<String, Object> schema) {
    checkKeywords(schema, "#");
    this.root = schema;
  }

  /**
   * Validates a document.
   *
   * @return every violation as {@code path: message}, empty when valid
   */
  public List<String> validate(final Object document) {
    final List<String> errors = new ArrayList<>();
    validate(root, document, "$", errors);
    return errors;
  }

  @SuppressWarnings("unchecked")
  private static void checkKeywords(final Map<String, Object> schema, final String where) {
    for (final Map.Entry<String, Object> e : schema.entrySet()) {
      if (!KNOWN.contains(e.getKey())) {
        throw new IllegalArgumentException("schema keyword '" + e.getKey() + "' at " + where + " is not supported");
      }
    }
    if (schema.get("properties") instanceof Map<?, ?> props) {
      for (final Map.Entry<?, ?> p : props.entrySet()) {
        checkKeywords((Map<String, Object>) p.getValue(), where + "/properties/" + p.getKey());
      }
    }
    if (schema.get("items") instanceof Map<?, ?> items) {
      checkKeywords((Map<String, Object>) items, where + "/items");
    }
    final Object additional = schema.get("additionalProperties");
    if (additional != null && !Boolean.FALSE.equals(additional) && !Boolean.TRUE.equals(additional)) {
      throw new IllegalArgumentException("additionalProperties at " + where + " must be true or false");
    }
  }

  @SuppressWarnings("unchecked")
  private static void validate(final Map<String, Object> s, final Object v, final String path, final List<String> errors) {
    final Object type = s.get("type");
    if (type instanceof String t && !hasType(t, v)) {
      errors.add(path + ": expected " + t + ", found " + describe(v));
      return;
    }
    if (s.containsKey("const") && !equal(s.get("const"), v)) {
      errors.add(path + ": must equal " + s.get("const"));
    }
    if (s.get("enum") instanceof List<?> allowed && allowed.stream().noneMatch(a -> equal(a, v))) {
      errors.add(path + ": " + v + " is not one of " + allowed);
    }
    if (v instanceof Number n) {
      if (s.get("minimum") instanceof Number min && n.doubleValue() < min.doubleValue()) {
        errors.add(path + ": " + n + " is below the minimum " + min);
      }
      if (s.get("maximum") instanceof Number max && n.doubleValue() > max.doubleValue()) {
        errors.add(path + ": " + n + " is above the maximum " + max);
      }
    }
    if (v instanceof String str) {
      if (s.get("minLength") instanceof Number min && str.length() < min.intValue()) {
        errors.add(path + ": shorter than " + min);
      }
      if (s.get("pattern") instanceof String p && !Pattern.compile(p).matcher(str).find()) {
        errors.add(path + ": '" + str + "' does not match " + p);
      }
    }
    if (v instanceof Map<?, ?> obj) {
      final Map<String, Object> props =
          s.get("properties") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
      if (s.get("required") instanceof List<?> req) {
        for (final Object r : req) {
          if (!obj.containsKey(r)) {
            errors.add(path + ": missing required property '" + r + "'");
          }
        }
      }
      for (final Map.Entry<?, ?> e : obj.entrySet()) {
        final String key = String.valueOf(e.getKey());
        final Object sub = props.get(key);
        if (sub instanceof Map<?, ?> subSchema) {
          validate((Map<String, Object>) subSchema, e.getValue(), path + "." + key, errors);
        } else if (Boolean.FALSE.equals(s.get("additionalProperties"))) {
          errors.add(path + ": property '" + key + "' is not allowed");
        }
      }
    }
    if (v instanceof List<?> list) {
      if (s.get("minItems") instanceof Number min && list.size() < min.intValue()) {
        errors.add(path + ": fewer than " + min + " items");
      }
      if (s.get("maxItems") instanceof Number max && list.size() > max.intValue()) {
        errors.add(path + ": more than " + max + " items");
      }
      if (s.get("items") instanceof Map<?, ?> items) {
        for (int i = 0; i < list.size(); i++) {
          validate((Map<String, Object>) items, list.get(i), path + "[" + i + "]", errors);
        }
      }
    }
  }

  private static boolean hasType(final String type, final Object v) {
    return switch (type) {
      case "object" -> v instanceof Map;
      case "array" -> v instanceof List;
      case "string" -> v instanceof String;
      case "boolean" -> v instanceof Boolean;
      case "integer" -> v instanceof Integer || v instanceof Long || v instanceof java.math.BigInteger;
      case "number" -> v instanceof Number;
      case "null" -> v == null;
      default -> throw new IllegalArgumentException("unsupported type " + type);
    };
  }

  private static boolean equal(final Object a, final Object b) {
    if (a instanceof Number x && b instanceof Number y) {
      return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
    }
    return a == null ? b == null : a.equals(b);
  }

  private static String describe(final Object v) {
    return v == null ? "null" : v.getClass().getSimpleName() + " " + v;
  }
}
