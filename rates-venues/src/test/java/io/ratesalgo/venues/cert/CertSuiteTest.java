package io.ratesalgo.venues.cert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.venues.sim.Json;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Every certification script shipped with the simulators passes with zero sequence errors. */
class CertSuiteTest {

  static final List<String> SCRIPTS =
      List.of(
          "d2c-rfq-hit-cover.json",
          "d2c-rfq-traded-away.json",
          "d2c-rfq-done-away.json",
          "d2c-rfq-passed.json",
          "d2c-rfq-no-quote.json",
          "d2c-quote-status-cancel.json",
          "d2c-cancel-on-disconnect.json",
          "clob-order-lifecycle.json",
          "clob-market-data.json",
          "clob-workup.json",
          "clob-iceberg-size-selfmatch.json",
          "clob-cancel-on-disconnect.json",
          "sef-rfq-required.json",
          "sef-rfq-rules.json",
          "sef-credit-check.json",
          "sef-order-book.json");

  static Stream<String> scripts() {
    return SCRIPTS.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scripts")
  void scriptPasses(final String name) {
    final CertResult r = CertRunner.run(name);
    assertTrue(r.passed(), name + ": " + String.join("\n", r.failures()) + " seqErrors=" + r.sequenceErrors());
    assertTrue(r.expectations() > 0);
  }

  @Test
  void aFailingExpectationIsReported() {
    @SuppressWarnings("unchecked")
    final Map<String, Object> script =
        (Map<String, Object>)
            Json.parse(
                "{\"venue\":\"clob\",\"flow\":\"orders\",\"steps\":["
                    + "{\"send\":{\"35\":\"AF\",\"584\":\"1\",\"585\":\"7\"}},"
                    + "{\"expect\":{\"35\":\"8\",\"911\":\"5\",\"58\":null,\"37\":\"*\"}},"
                    + "{\"expect\":{\"35\":\"8\"}}]}");
    final CertResult r = CertRunner.run("inline", script);
    assertFalse(r.passed());
    assertEquals(2, r.failures().size(), String.join("\n", r.failures()));
    assertEquals("orders", r.flow());
    assertEquals("clob", r.venue());
    assertEquals(3, r.steps());
  }

  @Test
  void badScriptsAreRefused() {
    assertThrows(IllegalArgumentException.class, () -> CertRunner.load("missing.json"));
    @SuppressWarnings("unchecked")
    final Map<String, Object> script =
        (Map<String, Object>)
            Json.parse("{\"venue\":\"clob\",\"flow\":\"x\",\"steps\":[{\"dance\":1},{\"do\":\"jump\"}]}");
    final CertResult r = CertRunner.run("bad", script);
    assertEquals(1, r.failures().size());
    assertTrue(r.failures().get(0).contains("unknown step kind"));
  }
}
