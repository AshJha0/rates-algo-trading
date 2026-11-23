package io.ratesalgo.bench.wired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs {@link WiredDeskBenchmark} once per configuration, each in a fresh JVM started with the
 * trading image's JVM flags ({@link #shippedFlags}), one after another, and keeps every report.
 * Usage: {@code java -cp ... io.ratesalgo.bench.wired.WiredDeskSuite --rate=1000
 * --warmup-seconds=20 --measure-seconds=60 --label=laptop --out=target/wired [--configs=a,b]
 * [--gc-compare=true]}. With {@code --gc-compare=true} the default-direct-plain configuration runs
 * once more under the Parallel collector, for the GC choice (DECISIONS.md DR-R6-L06).
 */
public final class WiredDeskSuite {

  /** Every configuration: profile, journal mode, transport. */
  static final List<String> ALL =
      List.of(
          "default-direct-plain", "default-ring-plain", "lowLatency-direct-plain", "lowLatency-ring-plain",
          "default-direct-tls", "default-ring-tls", "lowLatency-direct-tls", "lowLatency-ring-tls");

  private WiredDeskSuite() {}

  /**
   * The JVM flags of deploy/trading/Dockerfile, which every run uses (a test checks the two
   * agree). Transparent huge pages exist on Linux only and are left out elsewhere.
   */
  static List<String> shippedFlags(final boolean linux) {
    final List<String> f = new ArrayList<>(
        List.of(
            "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
            "--add-opens", "java.base/java.nio=ALL-UNNAMED",
            "--enable-native-access=ALL-UNNAMED",
            "-XX:+UseZGC", "-Xms512m", "-Xmx512m", "-XX:+AlwaysPreTouch"));
    if (linux) {
      f.add("-XX:+UseTransparentHugePages");
    }
    return f;
  }

  public static void main(final String[] args) throws IOException, InterruptedException {
    final Map<String, String> a = new LinkedHashMap<>();
    for (final String arg : args) {
      a.put(arg.substring(2, arg.indexOf('=')), arg.substring(arg.indexOf('=') + 1));
    }
    final Path out = Path.of(a.getOrDefault("out", "target/wired"));
    Files.createDirectories(out);
    final boolean linux = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux");
    final List<List<String>> runs = plan(a, linux, System.getProperty("java.class.path"));
    int failed = 0;
    for (final List<String> cmd : runs) {
      System.out.println("== " + cmd.get(cmd.size() - 8) + " " + cmd.get(cmd.size() - 7) + " " + cmd.get(cmd.size() - 6));
      final Process p = new ProcessBuilder(cmd).inheritIO().start();
      final int code = p.waitFor();
      if (code != 0) {
        failed++;
        System.out.println("   exited with " + code + "; kept going, every run is reported");
      }
    }
    System.out.println("runs: " + runs.size() + ", failed: " + failed + ", reports in " + out.toAbsolutePath());
    if (failed == runs.size()) {
      System.exit(1);
    }
  }

  /** The command line of every run the arguments ask for, in order. */
  static List<List<String>> plan(final Map<String, String> a, final boolean linux, final String classPath) {
    final Path out = Path.of(a.getOrDefault("out", "target/wired"));
    final List<String> configs = a.containsKey("configs") ? List.of(a.get("configs").split(",", -1)) : ALL;
    final List<String[]> runs = new ArrayList<>();
    for (final String name : configs) {
      runs.add(new String[] {name, "-XX:+UseZGC"});
    }
    if (Boolean.parseBoolean(a.getOrDefault("gc-compare", "false"))) {
      runs.add(new String[] {"default-direct-plain", "-XX:+UseParallelGC"});
    }
    final List<List<String>> commands = new ArrayList<>();
    for (final String[] run : runs) {
      final String[] parts = run[0].split("-", -1);
      if (parts.length != 3) {
        throw new IllegalArgumentException("a configuration is profile-journal-transport: " + run[0]);
      }
      final List<String> cmd = new ArrayList<>();
      cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
      for (final String flag : shippedFlags(linux)) {
        cmd.add("-XX:+UseZGC".equals(flag) ? run[1] : flag);
      }
      final String gcName = run[1].substring(run[1].indexOf('+') + 1);
      cmd.add("-Xlog:gc*,safepoint:file=" + out.resolve(run[0] + "-" + gcName + "-jvm.log") + ":uptime,level,tags");
      cmd.add("-Drates.probes=true");
      cmd.add("-cp");
      cmd.add(classPath);
      cmd.add(WiredDeskBenchmark.class.getName());
      cmd.add("--profile=" + parts[0]);
      cmd.add("--journal=" + parts[1]);
      cmd.add("--transport=" + parts[2]);
      cmd.add("--rate=" + a.getOrDefault("rate", "1000"));
      cmd.add("--warmup-seconds=" + a.getOrDefault("warmup-seconds", "20"));
      cmd.add("--measure-seconds=" + a.getOrDefault("measure-seconds", "60"));
      cmd.add("--label=" + a.getOrDefault("label", "local") + ("-XX:+UseZGC".equals(run[1]) ? "" : "-" + gcName));
      cmd.add("--out=" + out);
      commands.add(cmd);
    }
    return commands;
  }
}
