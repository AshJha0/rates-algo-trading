# Benchmarks: methodology

This document fixes how latency is measured so that numbers, when they exist, mean the same thing from one report to the next. As of 2026-10-31 nothing has been measured; the results table at the end is empty on purpose. The first report will be linked here by file name and date.

## Stage boundaries and clocks

Every measured interval is between two named points, each stamped with `Clock.nanoTime()` on the thread that passes it. The points are:

| Point | Where | Stamped by |
|---|---|---|
| T0 | Load generator's scheduled send time for the event (not the actual send time) | load generator |
| T1 | First byte of the input readable in the event core's ring buffer | gateway thread, after publish |
| T2 | Event core has read the input (start of handler) | event core thread |
| T3 | Event core has published the output to the outbound ring buffer | event core thread |
| T4 | Gateway has written the last byte of the output to the socket (or to the simulator's inbound buffer) | gateway thread |

Two headline intervals:

- Core decode-to-encode: T3 minus T1. This is the part the single-writer design controls.
- Tick-to-wire: T4 minus T0. This includes the gateways and, through T0, any queueing caused by the system falling behind.

All stamps come from the same monotonic clock on the same machine. Cross-machine measurement is not attempted.

## Fixed-rate load generator

The load generator sends events on a fixed schedule (for example one book update every 100 microseconds, plus RFQs at a lower rate) regardless of how quickly the system responds. T0 is the scheduled time, not the actual send time. If the generator itself falls behind its schedule, the run is discarded and the generator's own lateness histogram is reported.

Rates are chosen to represent a busy rates desk day, with the actual rate recorded per run. A run also includes a rate sweep so the point at which the system starts to queue is visible.

## Coordinated-omission correction

Measuring from the scheduled time T0 is the coordinated-omission correction. Without it a stall hides itself: while the system is paused, no new samples are taken, so the pause is recorded once instead of once per event that should have been handled. Samples are recorded in an HdrHistogram with 3 significant digits over a range of 1 microsecond to 10 seconds. Both the corrected (from T0) and uncorrected (from actual send time) histograms are kept, and reports show the corrected one.

JMH is not used for these measurements. It is the right tool for comparing two implementations of one method and is used for that in isolated cases, with results labelled as microbenchmarks; it does not answer the tick-to-wire question.

## Warm-up policy

Each run begins with a warm-up of at least 60 seconds or 1 million events, whichever is longer, during which samples are discarded. The warm-up exercises every code path the measured phase will use, so JIT compilation and lazy initialisation complete before measurement. The measured phase runs for at least 5 minutes. A run reports the JIT compilation log summary so a reviewer can see whether compilation happened during measurement.

## Percentiles reported

p50, p99, p99.9, p99.99 and max, in microseconds, for each interval. The sample count is reported with them. A percentile is only reported if the sample count supports it (p99.99 needs at least 100,000 samples to mean anything, and the report says when it does not).

## What is recorded with every run

- Machine: model, CPU model and count, memory, whether cores were isolated or pinned.
- Operating system and kernel or build version.
- JVM vendor, version, and the full flag list (heap size, GC, `-XX:+AlwaysPreTouch`, large pages, compiler flags).
- Git commit of the code under test.
- Load generator rate(s) and event mix.
- Date and time.

## Allocation check

On the event core thread, `ThreadMXBean.getThreadAllocatedBytes(threadId)` is read after warm-up and again after the measured phase. The delta must be zero for a run to be labelled allocation-free. A non-zero delta is reported with the number of bytes and the run is still reported, labelled accordingly. Where the delta is non-zero, an allocation profile (JFR) is attached.

## Where it runs

Hosted CI runners (GitHub-hosted Linux and Windows) produce informational numbers only. Their hardware is shared and varies between runs, so no threshold gates on them. Gating applies only on a self-hosted, pinned runner with a recorded configuration, when one exists (DECISIONS.md DR-005). Until then, reports come from the development machine and say so.

## Results

No results yet. Rows are added with a link to the report file and the run date.

| Date | Commit | Machine | Interval | Rate | Samples | p50 (us) | p99 (us) | p99.9 (us) | p99.99 (us) | max (us) | Allocation delta (bytes) | Report |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| | | | core decode-to-encode | | | | | | | | | |
| | | | tick-to-wire | | | | | | | | | |
