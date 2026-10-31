/**
 * The event core: one thread owns one instrument group's state and runs {@link
 * io.ratesalgo.core.EventLoop}'s duty cycle. The contract other modules build to is API_SPEC.md
 * Section 9. Everything in this package is on the hot path; the subpackage {@code
 * io.ratesalgo.core.chaos} is test tooling and is not.
 */
@HotPath
package io.ratesalgo.core;
