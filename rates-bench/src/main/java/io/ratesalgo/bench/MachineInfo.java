package io.ratesalgo.bench;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * What a latency report records about where it ran (docs/BENCHMARKS.md, "What is recorded with
 * every run"). Model and CPU names are read best-effort from the operating system; anything that
 * cannot be read is reported as "unknown" rather than guessed.
 *
 * @param model machine model
 * @param cpu CPU model name
 * @param logicalCpus logical processors visible to the JVM
 * @param memoryBytes physical memory, or -1 if unknown
 * @param os operating system name and version
 * @param arch CPU architecture
 * @param jvm JVM vendor, name and version
 * @param jvmFlags the JVM's input arguments
 * @param commit git commit of the code under test
 */
public record MachineInfo(
    String model,
    String cpu,
    int logicalCpus,
    long memoryBytes,
    String os,
    String arch,
    String jvm,
    List<String> jvmFlags,
    String commit) {

  public MachineInfo {
    jvmFlags = List.copyOf(jvmFlags);
  }

  /** Reads the current machine. Spawns short-lived processes; call before the measured phase. */
  public static MachineInfo detect() {
    final String osName = System.getProperty("os.name", "unknown");
    final String lower = osName.toLowerCase(Locale.ROOT);
    String model = "unknown";
    String cpu = "unknown";
    if (lower.startsWith("windows")) {
      model = run("powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_ComputerSystem).Model");
      cpu = run("powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_Processor).Name");
    } else if (lower.startsWith("linux")) {
      model = firstLine(Path.of("/sys/devices/virtual/dmi/id/product_name"));
      cpu = cpuInfoModel();
    } else if (lower.startsWith("mac")) {
      model = run("sysctl", "-n", "hw.model");
      cpu = run("sysctl", "-n", "machdep.cpu.brand_string");
    }
    long memory = -1;
    if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
      memory = os.getTotalMemorySize();
    }
    String commit = System.getenv("GITHUB_SHA");
    if (commit == null || commit.isBlank()) {
      commit = run("git", "rev-parse", "HEAD");
    }
    return new MachineInfo(
        model,
        cpu,
        Runtime.getRuntime().availableProcessors(),
        memory,
        osName + " " + System.getProperty("os.version", ""),
        System.getProperty("os.arch", "unknown"),
        System.getProperty("java.vm.vendor", "")
            + " "
            + System.getProperty("java.vm.name", "")
            + " "
            + Runtime.version(),
        ManagementFactory.getRuntimeMXBean().getInputArguments(),
        commit.length() > 12 ? commit.substring(0, 12) : commit);
  }

  static String run(final String... command) {
    try {
      final Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
      final String out;
      try (InputStream in = p.getInputStream()) {
        out = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
      }
      if (!p.waitFor(10, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        return "unknown";
      }
      return p.exitValue() == 0 && !out.isEmpty() ? out.lines().findFirst().orElse("unknown").strip() : "unknown";
    } catch (final IOException e) {
      return "unknown";
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return "unknown";
    }
  }

  static String firstLine(final Path file) {
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8).stream().findFirst().orElse("unknown").strip();
    } catch (final IOException e) {
      return "unknown";
    }
  }

  private static String cpuInfoModel() {
    try {
      return Files.readAllLines(Path.of("/proc/cpuinfo"), StandardCharsets.UTF_8).stream()
          .filter(l -> l.startsWith("model name"))
          .map(l -> l.substring(l.indexOf(':') + 1).strip())
          .findFirst()
          .orElse("unknown");
    } catch (final IOException e) {
      return "unknown";
    }
  }
}
