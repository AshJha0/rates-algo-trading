package io.ratesalgo.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Hot-path rules (docs/ARCHITECTURE.md, threading and memory rules) checked with ArchUnit over the
 * compiled classes of every package marked {@link HotPath}. The module compiles with {@code
 * -XDstringConcat=inline} so that string concatenation is visible as StringBuilder calls.
 *
 * <p>Each rule is also run against {@code io.ratesalgo.core.archfixture}, a test package written
 * to break every rule, so a rule that silently stopped matching anything would fail here.
 */
class HotPathRulesTest {

  /** Code units on the message path: no string building here, even on error branches. */
  static final Pattern MESSAGE_PATH =
      Pattern.compile(
          "onMessage|onRingMessage|doWork|publish|dispatch|onReplayFrame|flushBackpressure"
              + "|raiseBackpressure|journalClockTick|offer|tryClaim|commit|latch|isClockTick");

  private static final Set<String> BOXED =
      Set.of(
          Boolean.class.getName(),
          Byte.class.getName(),
          Short.class.getName(),
          Character.class.getName(),
          Integer.class.getName(),
          Long.class.getName(),
          Float.class.getName(),
          Double.class.getName());

  static final DescribedPredicate<JavaClass> HOT =
      DescribedPredicate.describe(
          "are on the hot path",
          c -> c.isAnnotatedWith(HotPath.class) || c.getPackage().isAnnotatedWith(HotPath.class));

  static final ArchRule NO_SYNCHRONIZED_METHODS =
      methods()
          .that()
          .areDeclaredInClassesThat(HOT)
          .should()
          .notHaveModifier(JavaModifier.SYNCHRONIZED)
          .allowEmptyShould(true);

  static final ArchRule NO_LOCKS =
      noClasses().that(HOT).should().dependOnClassesThat().resideInAPackage("java.util.concurrent.locks..");

  static final ArchRule NO_JAVA_UTIL_COLLECTIONS =
      noClasses()
          .that(HOT)
          .should()
          .dependOnClassesThat(
              JavaClass.Predicates.assignableTo(Collection.class)
                  .or(JavaClass.Predicates.assignableTo(Map.class)));

  static final ArchRule NO_BOXING = noClasses().that(HOT).should(box());

  static final ArchRule NO_STRING_BUILDING_ON_MESSAGE_PATHS =
      noClasses().that(HOT).should(buildStringsOnMessagePaths());

  static final List<ArchRule> RULES =
      List.of(
          NO_SYNCHRONIZED_METHODS,
          NO_LOCKS,
          NO_JAVA_UTIL_COLLECTIONS,
          NO_BOXING,
          NO_STRING_BUILDING_ON_MESSAGE_PATHS);

  private static ArchCondition<JavaClass> box() {
    return new ArchCondition<>("box primitives") {
      @Override
      public void check(final JavaClass c, final ConditionEvents events) {
        c.getMethodCallsFromSelf().stream()
            .filter(call -> BOXED.contains(call.getTargetOwner().getName()))
            .filter(call -> "valueOf".equals(call.getName()))
            .forEach(call -> events.add(SimpleConditionEvent.satisfied(call, call.getDescription())));
      }
    };
  }

  private static ArchCondition<JavaClass> buildStringsOnMessagePaths() {
    return new ArchCondition<>("build strings in message-path methods") {
      @Override
      public void check(final JavaClass c, final ConditionEvents events) {
        for (final JavaCodeUnit unit : c.getCodeUnits()) {
          if (!MESSAGE_PATH.matcher(unit.getName()).matches()) {
            continue;
          }
          final Stream<JavaAccess<?>> calls =
              Stream.concat(
                  unit.getMethodCallsFromSelf().stream(), unit.getConstructorCallsFromSelf().stream());
          calls
              .filter(
                  a -> {
                    final String owner = a.getTargetOwner().getName();
                    return owner.equals(StringBuilder.class.getName())
                        || owner.equals("java.lang.invoke.StringConcatFactory")
                        || (owner.equals(String.class.getName())
                            && ("format".equals(a.getName()) || "concat".equals(a.getName())));
                  })
              .forEach(a -> events.add(SimpleConditionEvent.satisfied(a, a.getDescription())));
        }
      }
    };
  }

  private static JavaClasses core() {
    return new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("io.ratesalgo.core");
  }

  private static JavaClasses fixture() {
    return new ClassFileImporter().importPackages("io.ratesalgo.core.archfixture");
  }

  @Test
  void hotPathPackageIsMarked() {
    final JavaClasses classes = core();
    assertTrue(classes.get(EventLoop.class).getPackage().isAnnotatedWith(HotPath.class));
    assertFalse(
        classes.get(io.ratesalgo.core.chaos.FaultInjector.class).getPackage().isAnnotatedWith(HotPath.class));
  }

  @Test
  void coreHotPathFollowsEveryRule() {
    final JavaClasses classes = core();
    for (final ArchRule rule : RULES) {
      rule.check(classes);
    }
  }

  @Test
  void everyRuleCatchesTheFixtureThatBreaksIt() {
    final JavaClasses classes = fixture();
    for (final ArchRule rule : RULES) {
      assertThrows(AssertionError.class, () -> rule.check(classes), rule.getDescription());
    }
  }

  /** ArchUnit sees synchronized methods, not synchronized blocks; the sources are checked for those. */
  @Test
  void noSynchronizedBlocksInHotPathSources() throws IOException {
    final Path src = Path.of("src", "main", "java", "io", "ratesalgo", "core");
    final Pattern sync = Pattern.compile("\\bsynchronized\\b");
    try (Stream<Path> files = Files.list(src)) {
      for (final Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        final String text = Files.readString(f, StandardCharsets.UTF_8);
        final String code = text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        assertFalse(sync.matcher(code).find(), f + " uses synchronized");
      }
    }
  }
}
