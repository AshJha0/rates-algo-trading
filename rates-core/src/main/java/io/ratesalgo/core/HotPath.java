package io.ratesalgo.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a package (or a single class) whose code runs on the event-loop thread after warm-up.
 *
 * <p>Code under this mark uses no {@code synchronized}, nothing from {@code
 * java.util.concurrent.locks}, no {@code java.util} collections or maps, no boxing, and no string
 * concatenation in its message paths. {@code HotPathRulesTest} checks these rules with ArchUnit
 * and {@code LoopAllocationTest} checks that the loop thread allocates nothing (docs/ARCHITECTURE.md,
 * threading and memory rules).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PACKAGE, ElementType.TYPE})
public @interface HotPath {}
