package io.ratesalgo.app;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;

/**
 * A copy of rates-core's test helper of the same name (test classes do not cross modules).
 * Runs one test method in a fresh JVM with extra flags, for the allocation tests that must also
 * hold without escape analysis (review finding L13, DECISIONS.md DR-R6-L07): with
 * {@code -XX:-DoEscapeAnalysis} the JIT no longer replaces short-lived objects by registers, so a
 * "zero allocation" that only held because C2 scalar-replaced something shows up as bytes.
 *
 * <p>The child gets the parent's class path and the platform's {@code --add-opens}; its main
 * creates the test class, fills each {@link TempDir} field with a fresh directory, calls the method
 * and exits 0, or prints the failure and exits 1.
 */
public final class ChildJvm {

  private ChildJvm() {}

  /** What a child run printed and how it ended. */
  public record Result(int exitCode, String output) {}

  /**
   * Runs {@code testClass.method()} in a child JVM with {@code flags}.
   *
   * @param timeoutSeconds the child is killed after this long
   */
  public static Result run(final Class<?> testClass, final String method, final long timeoutSeconds,
      final String... flags) throws IOException, InterruptedException {
    return run(testClass, method, null, timeoutSeconds, flags);
  }

  /** {@link #run(Class, String, long, String...)} for a method taking one enum argument, by its name. */
  public static Result run(final Class<?> testClass, final String method, final String enumArgument,
      final long timeoutSeconds, final String... flags) throws IOException, InterruptedException {
    final List<String> cmd = new ArrayList<>();
    cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    cmd.addAll(List.of(
        "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED"));
    cmd.addAll(List.of(flags));
    cmd.add("-cp");
    cmd.add(System.getProperty("java.class.path"));
    cmd.add(ChildJvm.class.getName());
    cmd.add(testClass.getName());
    cmd.add(method);
    if (enumArgument != null) {
      cmd.add(enumArgument);
    }
    final Path log = Files.createTempFile("child-jvm", ".log");
    try {
      final Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
      if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        return new Result(-1, Files.readString(log, StandardCharsets.UTF_8) + "\n(killed after " + timeoutSeconds + " s)");
      }
      return new Result(p.exitValue(), Files.readString(log, StandardCharsets.UTF_8));
    } finally {
      Files.deleteIfExists(log);
    }
  }

  /** Child entry point: {@code <test class> <method> [<enum constant>]}. */
  public static void main(final String[] args) throws Exception {
    final Class<?> type = Class.forName(args[0]);
    final java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
    constructor.setAccessible(true);
    final Object test = constructor.newInstance();
    final List<Path> dirs = new ArrayList<>();
    for (final Field f : type.getDeclaredFields()) {
      if (f.isAnnotationPresent(TempDir.class)) {
        final Path dir = Files.createTempDirectory("child-jvm-test");
        dirs.add(dir);
        f.setAccessible(true);
        f.set(test, dir);
      }
    }
    Method m = null;
    for (final Method candidate : type.getDeclaredMethods()) {
      if (candidate.getName().equals(args[1]) && candidate.getParameterCount() == (args.length > 2 ? 1 : 0)) {
        m = candidate;
      }
    }
    if (m == null) {
      throw new NoSuchMethodException(args[1]);
    }
    m.setAccessible(true);
    int code = 0;
    try {
      if (args.length > 2) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        final Object value = Enum.valueOf((Class) m.getParameterTypes()[0], args[2]);
        m.invoke(test, value);
      } else {
        m.invoke(test);
      }
      System.out.println("CHILD PASSED " + args[0] + "." + args[1]);
    } catch (final java.lang.reflect.InvocationTargetException e) {
      System.out.println("CHILD FAILED " + args[0] + "." + args[1] + ": " + e.getCause());
      e.getCause().printStackTrace(System.out);
      code = 1;
    } finally {
      for (final Path d : dirs) {
        try (Stream<Path> files = Files.walk(d)) {
          for (final Path f : files.sorted(Comparator.reverseOrder()).toList()) {
            Files.deleteIfExists(f);
          }
        } catch (final IOException ignored) {
          // temporary files; the operating system clears them in time
        }
      }
    }
    System.exit(code);
  }
}
