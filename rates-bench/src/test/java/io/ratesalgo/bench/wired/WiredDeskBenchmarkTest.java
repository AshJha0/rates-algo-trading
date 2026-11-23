package io.ratesalgo.bench.wired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.app.TradingProcess;
import io.ratesalgo.bench.LatencyReport;
import io.ratesalgo.core.JournalMode;
import io.ratesalgo.journal.ChainHash;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The wired-desk benchmark end to end, short: the real trading process answers the load
 * generator's QuoteRequests over loopback FIX, every request of the measured window is accounted
 * for, and each stage interval is filled. The module's surefire run sets {@code rates.probes}.
 */
class WiredDeskBenchmarkTest {

  @TempDir Path dir;

  private static String json(final Path report) throws Exception {
    return Files.readString(report, StandardCharsets.UTF_8);
  }

  private static long count(final String json, final String key) {
    final java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\": (-?\\d+)").matcher(json);
    assertTrue(m.find(), key + " in " + json);
    return Long.parseLong(m.group(1));
  }

  @Test
  void aShortPlainRunAnswersTheRequestsAndFillsEveryStage() throws Exception {
    final WiredDeskBenchmark.Config c =
        WiredDeskBenchmark.Config.parse(
            new String[] {"--profile=default", "--journal=direct", "--transport=plain", "--rate=200",
                "--warmup-seconds=1", "--measure-seconds=2", "--label=test", "--out=" + dir});
    final String report = json(WiredDeskBenchmark.run(c));
    assertTrue(report.contains("\"schema\": \"rates-bench-wired/1\""));
    assertEquals(400, count(report, "requestsMeasured"));
    final long answered = count(report, "answered");
    assertEquals(400, answered + count(report, "unanswered"), "every measured request is accounted for");
    assertTrue(answered > 300, "the desk quoted most requests: " + answered);
    assertTrue(count(report, "clockResolutionNs") > 0);
    for (final String[] interval : WiredDeskBenchmark.INTERVALS) {
      final Histogram h = LatencyReport.readHistogram(report, interval[0]);
      assertTrue(h.getTotalCount() > 0, interval[0] + " has samples");
    }
    final Histogram total = LatencyReport.readHistogram(report, "scheduledToQuoteWritten");
    final Histogram handler = LatencyReport.readHistogram(report, "handler");
    assertTrue(total.getValueAtPercentile(50) >= handler.getValueAtPercentile(50), "a stage is part of the whole");
    assertEquals(0, count(report, "haltReasonPresent"));
  }

  @Test
  void aShortLowLatencyRingRunOverTlsWorksToo() throws Exception {
    final WiredDeskBenchmark.Config c =
        WiredDeskBenchmark.Config.parse(
            new String[] {"--profile=lowLatency", "--journal=ring", "--transport=tls", "--rate=200",
                "--warmup-seconds=1", "--measure-seconds=1", "--out=" + dir});
    final String report = json(WiredDeskBenchmark.run(c));
    assertTrue(count(report, "answered") > 150, report);
    assertEquals(0, count(report, "journalProducerWaits"), "the journal threads kept up");
  }

  @Test
  void configurationsAndTuningsFollowTheProfiles() {
    assertThrows(IllegalArgumentException.class, () -> WiredDeskBenchmark.Config.parse(new String[] {"--journal=mmap"}));
    assertThrows(IllegalArgumentException.class, () -> WiredDeskBenchmark.Config.parse(new String[] {"rate=5"}));
    final TradingProcess.Tuning low =
        WiredDeskBenchmark.tuning(WiredDeskBenchmark.Config.parse(new String[] {"--profile=lowLatency", "--journal=direct"}));
    assertEquals(JournalMode.DIRECT, low.journals().mode());
    assertEquals(ChainHash.WORD64, low.journals().hashAlgorithm());
    assertEquals("busy-spin", low.gatewayIdleStrategy());
    final TradingProcess.Tuning def = WiredDeskBenchmark.tuning(WiredDeskBenchmark.Config.parse(new String[] {"--journal=ring"}));
    assertEquals(JournalMode.RING, def.journals().mode());
    assertEquals("park", def.gatewayIdleStrategy());
    assertEquals("default-ring-plain", WiredDeskBenchmark.Config.parse(new String[] {"--journal=ring"}).name());
    assertTrue(WiredDeskBenchmark.clockResolutionNanos() > 0);
  }

  /** The suite benchmarks with the flags the trading image ships; a change to one must change the other. */
  @Test
  void theSuiteUsesTheTradingImagesJvmFlags() throws Exception {
    final String docker = Files.readString(Path.of("../deploy/trading/Dockerfile"), StandardCharsets.UTF_8);
    for (final String flag : WiredDeskSuite.shippedFlags(true)) {
      assertTrue(docker.contains("\"" + flag + "\""), flag + " is in the Dockerfile's ENTRYPOINT");
    }
    assertEquals(8, WiredDeskSuite.ALL.size());
    assertTrue(WiredDeskSuite.shippedFlags(false).size() < WiredDeskSuite.shippedFlags(true).size());
    assertTrue(List.copyOf(WiredDeskSuite.shippedFlags(true)).contains("--enable-native-access=ALL-UNNAMED"));
  }

  @Test
  void theSuitePlansOneJvmPerConfigurationAndTheGcComparison() {
    final java.util.Map<String, String> all = new java.util.LinkedHashMap<>();
    all.put("gc-compare", "true");
    all.put("out", dir.toString());
    final List<List<String>> runs = WiredDeskSuite.plan(all, false, "cp");
    assertEquals(9, runs.size());
    assertTrue(runs.get(0).contains("-XX:+UseZGC") && runs.get(0).contains("--profile=default"));
    assertTrue(runs.get(8).contains("-XX:+UseParallelGC") && !runs.get(8).contains("-XX:+UseZGC"));
    assertTrue(runs.get(8).stream().anyMatch(a -> a.startsWith("--label=local-UseParallelGC")));
    assertTrue(runs.stream().allMatch(r -> r.contains("-Drates.probes=true")));
    final java.util.Map<String, String> one = java.util.Map.of("configs", "lowLatency-ring-tls", "rate", "50");
    final List<List<String>> linux = WiredDeskSuite.plan(one, true, "cp");
    assertEquals(1, linux.size());
    assertTrue(linux.get(0).contains("-XX:+UseTransparentHugePages"));
    assertTrue(linux.get(0).contains("--journal=ring") && linux.get(0).contains("--rate=50"));
    assertThrows(IllegalArgumentException.class, () -> WiredDeskSuite.plan(java.util.Map.of("configs", "fast"), false, "cp"));
  }

  @Test
  void everyBadArgumentIsRefused() {
    for (final String bad : new String[] {"--profile=fast", "--transport=udp", "--rate=0", "--rate=200000",
        "--warmup-seconds=-1", "--measure-seconds=0"}) {
      assertThrows(IllegalArgumentException.class, () -> WiredDeskBenchmark.Config.parse(new String[] {bad}), bad);
    }
  }
}
