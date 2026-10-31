package io.ratesalgo.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
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
import io.ratesalgo.app.gateway.FeedBinding;
import io.ratesalgo.app.gateway.InboundRouter;
import io.ratesalgo.app.gateway.VenueSession;
import io.ratesalgo.app.loop.DeskGroup;
import io.ratesalgo.app.loop.RouterHedgeSink;
import io.ratesalgo.app.market.LoopBook;
import io.ratesalgo.core.HotPath;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * rates-core's hot-path rules (docs/ARCHITECTURE.md, threading and memory rules; HotPathRulesTest
 * there) applied to the classes the process puts on a loop or gateway thread: the desk group and
 * its router binding, the composite book, the inbound router, the venue sessions and the feed
 * binding, each marked {@link HotPath}. The rules are restated here because rates-core keeps them
 * in its test sources. One difference (DECISIONS.md DR-R2W-12): Agrona's primitive-keyed maps are
 * allowed, since the routing tables need a lookup by id and their primitive methods neither box
 * nor allocate once sized; {@code java.util} collections stay forbidden.
 */
class AppHotPathRulesTest {

  private static final Pattern MESSAGE_PATH =
      Pattern.compile(
          "onMessage|onRingMessage|doWork|publish|dispatch|onReplayFrame|flushBackpressure"
              + "|raiseBackpressure|journalClockTick|offer|tryClaim|commit|latch|isClockTick"
              + "|on[A-Z]\\w*");

  private static final Set<String> BOXED =
      Set.of(Boolean.class.getName(), Byte.class.getName(), Short.class.getName(), Character.class.getName(),
          Integer.class.getName(), Long.class.getName(), Float.class.getName(), Double.class.getName());

  private static final DescribedPredicate<JavaClass> HOT =
      DescribedPredicate.describe("are on the hot path", c -> c.isAnnotatedWith(HotPath.class)
          || c.getEnclosingClass().map(e -> e.isAnnotatedWith(HotPath.class)).orElse(false));

  private static final List<ArchRule> RULES =
      List.of(
          methods().that().areDeclaredInClassesThat(HOT).should().notHaveModifier(JavaModifier.SYNCHRONIZED)
              .allowEmptyShould(true),
          noClasses().that(HOT).should().dependOnClassesThat().resideInAPackage("java.util.concurrent.locks.."),
          noClasses().that(HOT).should().dependOnClassesThat(
              JavaClass.Predicates.assignableTo(Collection.class)
                  .or(JavaClass.Predicates.assignableTo(Map.class))
                  .and(DescribedPredicate.not(JavaClass.Predicates.resideInAPackage("org.agrona.collections")))),
          noClasses().that(HOT).should(box()),
          noClasses().that(HOT).should(buildStringsOnMessagePaths()));

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
              Stream.concat(unit.getMethodCallsFromSelf().stream(), unit.getConstructorCallsFromSelf().stream());
          calls
              .filter(a -> {
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

  @Test
  void theProcessHotPathFollowsEveryCoreRule() {
    final JavaClasses classes =
        new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("io.ratesalgo.app");
    for (final Class<?> hot :
        List.of(DeskGroup.class, RouterHedgeSink.class, LoopBook.class, InboundRouter.class, VenueSession.class,
            FeedBinding.class)) {
      assertTrue(classes.get(hot).isAnnotatedWith(HotPath.class), hot + " is marked");
    }
    for (final ArchRule rule : RULES) {
      rule.check(classes);
    }
  }
}
