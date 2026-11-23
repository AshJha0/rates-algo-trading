package io.ratesalgo.ops.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Each supported schema keyword at its exact edge, with the error text an operator reads. */
class JsonSchemaRulesTest {

  private static Map<String, Object> map(final Object... kv) {
    final Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  @Test
  void numericAndLengthLimitsAreInclusive() {
    final JsonSchema s = new JsonSchema(map("type", "object", "properties", map(
        "n", map("type", "number", "minimum", 1, "maximum", 5),
        "s", map("type", "string", "minLength", 3, "pattern", "^[a-z]+$"),
        "l", map("type", "array", "minItems", 1, "maxItems", 2, "items", map("type", "integer")))));
    assertEquals(List.of(), s.validate(map("n", 1, "s", "abc", "l", List.of(1))));
    assertEquals(List.of(), s.validate(map("n", 5.0, "s", "abcd", "l", List.of(1L, BigInteger.TWO))));
    assertEquals(List.of(
            "$.n: 0.5 is below the minimum 1",
            "$.s: shorter than 3",
            "$.l: fewer than 1 items"),
        s.validate(map("n", 0.5, "s", "ab", "l", List.of())));
    assertEquals(List.of(
            "$.n: 6 is above the maximum 5",
            "$.s: 'ab1c' does not match ^[a-z]+$",
            "$.l: more than 2 items",
            "$.l[1]: expected integer, found Double 2.5"),
        s.validate(map("n", 6, "s", "ab1c", "l", List.of(1, 2.5, 3))));
  }

  @Test
  void requiredConstEnumAndAdditionalPropertiesReportByPath() {
    final JsonSchema s = new JsonSchema(map("type", "object", "required", List.of("a", "b"),
        "additionalProperties", false, "properties", map(
            "a", map("const", 2),
            "b", map("enum", List.of("x", "y")),
            "c", map("type", "object", "properties", map("d", map("type", "boolean"))))));
    assertEquals(List.of(), s.validate(map("a", 2.0, "b", "y", "c", map("d", true))), "2 equals 2.0");
    assertEquals(List.of(
            "$: missing required property 'b'",
            "$.a: must equal 2",
            "$.c.d: expected boolean, found String yes",
            "$: property 'z' is not allowed"),
        s.validate(map("a", 3, "c", map("d", "yes"), "z", 1)));
    assertEquals(List.of("$.b: q is not one of [x, y]"), s.validate(map("a", 2, "b", "q")));
    assertEquals(List.of("$: expected object, found null"), s.validate(null));
  }

  @Test
  void openObjectsAcceptUnknownPropertiesAndNullIsAType() {
    final JsonSchema open = new JsonSchema(map("type", "object", "additionalProperties", true,
        "properties", map("n", map("type", "null"))));
    final Map<String, Object> doc = new HashMap<>();
    doc.put("n", null);
    doc.put("other", 1);
    assertEquals(List.of(), open.validate(doc));
    assertEquals(List.of("$.n: expected null, found Integer 1"), open.validate(map("n", 1)));
  }

  @Test
  void unsupportedKeywordsAndTypesAreRefused() {
    assertThrows(IllegalArgumentException.class, () -> new JsonSchema(map("oneOf", List.of())));
    assertThrows(IllegalArgumentException.class,
        () -> new JsonSchema(map("properties", map("a", map("anyOf", List.of())))));
    assertThrows(IllegalArgumentException.class, () -> new JsonSchema(map("items", map("allOf", List.of()))));
    assertThrows(IllegalArgumentException.class, () -> new JsonSchema(map("additionalProperties", map())));
    assertThrows(IllegalArgumentException.class, () -> new JsonSchema(map("type", "date")).validate(1));
    assertEquals(List.of("$: expected array, found Integer 1"),
        new JsonSchema(map("type", "array")).validate(1));
    assertEquals(List.of(), new JsonSchema(map("type", "array")).validate(Arrays.asList(1, null)));
  }
}
