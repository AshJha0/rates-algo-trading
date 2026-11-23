package io.ratesalgo.ops.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.ratesalgo.ops.http.OpsHttpServer;
import io.ratesalgo.ops.metrics.MetricsRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * deploy/bluegreen.sh against stub processes (review finding E8): the switch is recorded only when
 * the old colour confirmed its kill. An old colour that answers 503, or does not answer, used to be
 * recorded as replaced. Needs bash and curl; skipped where they are missing.
 */
class BlueGreenScriptTest {

  private static final Path SCRIPT = Path.of("../deploy/bluegreen.sh").toAbsolutePath().normalize();
  private static String bash;

  @TempDir Path state;

  @BeforeAll
  static void findBash() throws Exception {
    final boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    // on Windows a bare "bash" may be the WSL launcher, which cannot reach these loopback ports
    final String candidate = windows ? "C:/Program Files/Git/bin/bash.exe" : "bash";
    assumeTrue(!windows || Files.isRegularFile(Path.of(candidate)), "no Git bash");
    try {
      final Process p = new ProcessBuilder(candidate, "-c", "command -v curl").redirectErrorStream(true).start();
      assumeTrue(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0, "no curl");
    } catch (final IOException e) {
      assumeTrue(false, "no bash: " + e);
    }
    bash = candidate;
  }

  /** A stub colour: always ready, its kill answers what the test sets. */
  private static OpsHttpServer colour(final String unconfirmed, final String status) throws IOException {
    return new OpsHttpServer("127.0.0.1", 0, new MetricsRegistry(), () -> null, new OpsHttpServer.Control() {
      @Override
      public String kill(final int requestedBy) {
        return unconfirmed;
      }

      @Override
      public String statusJson() {
        return status;
      }
    }, "tok");
  }

  private static int closedPort() throws IOException {
    try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return s.getLocalPort();
    }
  }

  private String output;

  private int switchToGreen(final int bluePort, final int greenPort, final Map<String, String> extra) throws Exception {
    final ProcessBuilder b =
        new ProcessBuilder(bash, SCRIPT.toString().replace('\\', '/'), "green", state.toString().replace('\\', '/'))
            .redirectErrorStream(true);
    b.environment().put("RATES_CONTROL_TOKEN", "tok");
    b.environment().put("RATES_OPERATOR_ID", "9001");
    b.environment().put("BLUE_URL", "http://127.0.0.1:" + bluePort);
    b.environment().put("GREEN_URL", "http://127.0.0.1:" + greenPort);
    b.environment().remove("RATES_OLD_COLOUR_CONFIRMED_DOWN");
    b.environment().putAll(extra);
    final Process p = b.start();
    output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(p.waitFor(2, TimeUnit.MINUTES), "the script did not finish: " + output);
    return p.exitValue();
  }

  private Path record() {
    return state.resolve("active-colour");
  }

  @Test
  void anUnconfirmedKillLeavesTheOldColourRecordedAsActive() throws Exception {
    try (OpsHttpServer blue = colour("D2C 2 unconfirmed", "{\"liveQuotes\":2}");
        OpsHttpServer green = colour(null, "{\"liveQuotes\":0}")) {
      assertEquals(1, switchToGreen(blue.port(), green.port(), Map.of()), output);
      assertTrue(output.contains("did not confirm the kill (HTTP 503)") && output.contains("switch abandoned"), output);
      assertFalse(Files.exists(record()), "nothing may be recorded");
    }
  }

  @Test
  void anOldColourThatDoesNotAnswerIsNotRecordedAsReplacedWithoutTheOperatorsWord() throws Exception {
    try (OpsHttpServer green = colour(null, "{\"liveQuotes\":0}")) {
      final int dead = closedPort();
      assertEquals(1, switchToGreen(dead, green.port(), Map.of()), output);
      assertTrue(output.contains("did not answer the kill request"), output);
      assertFalse(Files.exists(record()), "no answer proves nothing about quotes at the venues");

      // the operator checked the venues and says so: recorded, with the override on the record
      assertEquals(0, switchToGreen(dead, green.port(), Map.of("RATES_OLD_COLOUR_CONFIRMED_DOWN", "1")), output);
      final String recorded = Files.readString(record());
      assertTrue(recorded.startsWith("green ") && recorded.contains("operator=9001")
          && recorded.contains("old-colour-kill-unconfirmed=operator-override"), recorded);
    }
  }

  @Test
  void aConfirmedKillIsRecordedAndStillLiveQuotesAreNot() throws Exception {
    try (OpsHttpServer blue = colour(null, "{\"liveQuotes\":3}");
        OpsHttpServer green = colour(null, "{\"liveQuotes\":0}")) {
      assertEquals(1, switchToGreen(blue.port(), green.port(), Map.of()), output);
      assertFalse(Files.exists(record()));
    }
    try (OpsHttpServer blue = colour(null, "{\"liveQuotes\":0}");
        OpsHttpServer green = colour(null, "{\"liveQuotes\":0}")) {
      assertEquals(0, switchToGreen(blue.port(), green.port(), Map.of()), output);
      final String recorded = Files.readString(record());
      assertTrue(recorded.startsWith("green ") && !recorded.contains("override"), recorded);
      assertEquals(0, switchToGreen(blue.port(), green.port(), Map.of()), "already active: " + output);
    }
  }

  @Test
  void aTargetThatIsNotReadyStopsTheSwitchBeforeTheKill() throws Exception {
    try (OpsHttpServer blue = colour(null, "{\"liveQuotes\":0}")) {
      assertEquals(1, switchToGreen(blue.port(), closedPort(), Map.of()), output);
      assertTrue(output.contains("is not ready"), output);
      assertFalse(Files.exists(record()));
    }
  }
}
