package io.ratesalgo.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LatencyHarnessTest {

  @TempDir Path dir;

  @ParameterizedTest
  @ValueSource(strings = {"direct", "ring"})
  void shortRunWritesAReportWhoseHistogramsReadBack(final String mode) throws Exception {
    final HarnessConfig cfg =
        HarnessConfig.parse(
            new String[] {
              "--rate=20000",
              "--warmup-seconds=1",
              "--measure-seconds=2",
              "--idle=backoff",
              "--journal=true",
              "--journal-mode=" + mode,
              "--ring-bytes=1048576",
              "--segment-bytes=1048576",
              "--cpu=-1",
              "--label=unit-test",
              "--out=" + dir
            });
    final AtomicLong seen = new AtomicLong();
    final LatencyHarness.Result r = LatencyHarness.run(cfg, (b, o, l, t) -> seen.incrementAndGet());
    assertEquals(60_000L, r.generated());
    final long measured = 40_000L - r.generatorDropped();
    assertTrue(r.scheduledToEncode().getTotalCount() <= 40_000L);
    assertTrue(r.scheduledToEncode().getTotalCount() >= measured - r.generatorDropped());
    assertTrue(r.decodeToEncode().getTotalCount() >= r.scheduledToEncode().getTotalCount());
    final String json = Files.readString(r.jsonFile(), StandardCharsets.UTF_8);
    // Exact accounting on every path a message can leave by (DR-INT-06). Under machine load the
    // gateway thread can fall behind, and the loop then drops Quotes on a full outbound ring
    // (backpressure, counted), so the gateway sees fewer than the loop processed.
    final long inbound = count(json, "inbound");
    final long outbound = count(json, "outbound");
    final long outboundDropped = count(json, "outboundDropped");
    assertEquals(r.generated() - r.generatorDropped(), inbound, json);
    assertEquals(inbound, outbound + outboundDropped, json);
    assertEquals(outbound, seen.get(), json);
    assertTrue(json.contains("\"schema\": \"rates-bench-latency/1\""));
    assertTrue(json.contains("\"tickToWire\": null"));
    assertTrue(json.contains("\"journalMode\": \"" + mode + "\""), json);
    assertTrue(json.contains("\"gcCollections\": "), json);
    final Histogram back = LatencyReport.readHistogram(json, "decodeToEncode");
    assertEquals(r.decodeToEncode().getTotalCount(), back.getTotalCount());
    assertEquals(r.decodeToEncode().getValueAtPercentile(99.0), back.getValueAtPercentile(99.0));
    LatencyReport.readHistogram(json, "generatorLateness");
    final List<String> rows = Files.readAllLines(r.markdownFile(), StandardCharsets.UTF_8);
    assertEquals(2, rows.size());
    assertTrue(rows.get(0).contains("core decode-to-encode"));
    assertTrue(rows.get(1).contains("scheduled-to-encode"));
  }

  private static long count(final String json, final String name) {
    final java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("\"" + name + "\": (\\d+)").matcher(json);
    assertTrue(m.find(), "no count " + name + " in " + json);
    return Long.parseLong(m.group(1));
  }

  @Test
  void mainRunsWithoutJournalAndPrintsTheRows() throws Exception {
    final PrintStream original = System.out;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try (PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
      System.setOut(sink);
      LatencyHarness.main(
          new String[] {
            "--rate=5000", "--warmup-seconds=0", "--measure-seconds=1", "--idle=yield", "--journal=false", "--out=" + dir
          });
    } finally {
      System.setOut(original);
    }
    final String text = captured.toString(StandardCharsets.UTF_8);
    assertTrue(text.contains("core decode-to-encode"));
    assertTrue(text.contains("report: "));
  }

  @Test
  void configParsingAndValidation() {
    final HarnessConfig d = HarnessConfig.parse(new String[0]);
    assertEquals(50_000, d.rate());
    assertEquals(20_000L, d.intervalNanos());
    assertEquals(3_000_000L, d.warmupMessages());
    assertEquals(15_000_000L, d.measuredMessages());
    assertThrows(IllegalArgumentException.class, () -> HarnessConfig.parse(new String[] {"rate=1"}));
    assertThrows(IllegalArgumentException.class, () -> HarnessConfig.parse(new String[] {"--nope=1"}));
    assertThrows(IllegalArgumentException.class, () -> HarnessConfig.parse(new String[] {"--journal=yes"}));
    assertThrows(IllegalArgumentException.class, () -> HarnessConfig.parse(new String[] {"--rate=0"}));
    assertThrows(IllegalArgumentException.class, () -> HarnessConfig.parse(new String[] {"--measure-seconds=0"}));
    assertEquals("direct", d.journalMode());
    assertEquals(64 << 20, d.ringBytes());
    final HarnessConfig ring = HarnessConfig.parse(new String[] {"--journal-mode=ring", "--ring-bytes=65536"});
    assertEquals("ring", ring.journalMode());
    assertEquals(65_536, ring.ringBytes());
    assertThrows(IllegalArgumentException.class, () -> HarnessConfig.parse(new String[] {"--journal-mode=async"}));
  }

  @Test
  void reportHelpers() {
    assertEquals("\"a\\\"b\\\\c\\n\\r\\t\\u0001\"", LatencyReport.quote("a\"b\\c\n\r\t\u0001"));
    assertEquals("1.500", LatencyReport.micros(1_500L));
    final Histogram empty = new Histogram(BenchQuoter.HIGHEST_NANOS, 3);
    assertTrue(LatencyReport.intervalJson(empty).startsWith("{\"samples\": 0, \"histogram\": "));
    assertThrows(IllegalArgumentException.class, () -> LatencyReport.readHistogram("{}", "decodeToEncode"));
    assertThrows(
        IllegalArgumentException.class,
        () -> LatencyReport.readHistogram("{\"x\": {\"histogram\": \"AAAA\"}}", "x"));
  }

  @Test
  void machineInfoFallsBackToUnknown() {
    assertEquals("unknown", MachineInfo.run("no-such-command-rates-bench"));
    assertEquals("unknown", MachineInfo.firstLine(Path.of("no", "such", "file")));
    final MachineInfo m = MachineInfo.detect();
    assertTrue(m.logicalCpus() > 0);
    assertTrue(m.jvm().contains(String.valueOf(Runtime.version().feature())));
  }

  @Test
  void quoterRejectsAScheduleThatIsNotAPowerOfTwo() {
    assertThrows(
        IllegalArgumentException.class, () -> new BenchQuoter(null, null, new long[3], 0L, 1L));
  }
}
