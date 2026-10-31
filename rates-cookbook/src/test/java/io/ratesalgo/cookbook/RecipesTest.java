package io.ratesalgo.cookbook;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Each recipe's printed output must equal the text under "Expected output" in COOKBOOK.md. The
 * expected files are the single source: tools/check_cookbook.py checks that COOKBOOK.md quotes
 * them and the recipe sources verbatim.
 */
class RecipesTest {

  @FunctionalInterface
  interface Main {
    void run(String[] args) throws Exception;
  }

  static Stream<Arguments> recipes() {
    return Stream.of(
        Arguments.of("recipe01.txt", (Main) Recipe01TimerWheel::main),
        Arguments.of("recipe02.txt", (Main) Recipe02Journal::main),
        Arguments.of("recipe03.txt", (Main) Recipe03QuoteRequest::main),
        Arguments.of("recipe17.txt", (Main) Recipe17Backtest::main),
        Arguments.of("recipe18.txt", (Main) Recipe18Ledger::main));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("recipes")
  void outputMatchesCookbook(final String expectedResource, final Main main) throws Exception {
    final String expected = resource("/expected/" + expectedResource);
    final PrintStream original = System.out;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try (PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
      System.setOut(sink);
      main.run(new String[0]);
    } finally {
      System.setOut(original);
    }
    final String actual = captured.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    assertEquals(expected, actual);
  }

  private static String resource(final String name) throws IOException {
    try (InputStream in = RecipesTest.class.getResourceAsStream(name)) {
      if (in == null) {
        throw new IOException("missing test resource " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
