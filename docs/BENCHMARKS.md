# Benchmarks: methodology

This document fixes how latency is measured so that numbers, when they exist, mean the same thing from one report to the next. The first measurements were taken on 2026-10-31 on the development laptop, in-process, and are in the results section at the end with their report files.

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

The gating unit test, `LoopAllocationTest`, uses windows rather than a single delta (DR-INT-02). After one million warm-up messages it measures three consecutive windows of one million messages each, reading the loop thread's counter from the test thread by thread id (the bean is called once before warm-up, so a first-call cost cannot land in a window). The last window must be exactly zero and the second and third must each be at most 1,024 bytes; the first is reported but not bounded. A one-off cost after warm-up, such as a rarely taken path running for the first time, shows in one window only, while a leak of even one byte per message shows as at least a megabyte in every window. The failure message lists each window's bytes with its dropped-message and backpressure counts.

## Where it runs

Hosted CI runners (GitHub-hosted Linux and Windows) produce informational numbers only. Their hardware is shared and varies between runs, so no threshold gates on them. Gating applies only on a self-hosted, pinned runner with a recorded configuration, when one exists (DECISIONS.md DR-005). Until then, reports come from the development machine and say so.

## Results

Rows are added with a link to the report file and the run date. Every row so far is in-process on a Windows laptop: the generator, the loop and the outbound gateway are threads of one JVM sharing one monotonic clock, no core is isolated or pinned, and the laptop runs its usual background load. By the rule in "Fixed-rate load generator" every run below would be discarded, because the generator fell behind its schedule (generator lateness p99 above the 20 us interval in each report); they are recorded because they are the first evidence, and the lateness is in each report.

Machine for all rows: ASUS Vivobook 17 X1704VA, Intel Core 5 120U (12 logical CPUs), 16 GB, Windows 11 (10.0). JVM: Eclipse Temurin 25.0.5+8, flags `-Xms1g -Xmx1g -XX:+AlwaysPreTouch -XX:+UseParallelGC` plus the three Agrona `--add-opens`. Loop idle strategy busy spin, not pinned. Load: 50,000 QuoteRequests per second, 60 s warm-up (3,000,000 messages, not recorded), 300 s measured. Harness: `io.ratesalgo.bench.LatencyHarness` (`rates-bench`).

Intervals (DECISIONS.md DR-P2C-08): core decode-to-encode is T3 minus T2, handler entry before the SBE decode to the Quote published on the outbound ring and journaled, recorded with `recordValueWithExpectedInterval` at 20 us; scheduled-to-encode is T3 minus T0 from the generator's scheduled send time, so it includes generator lateness, ring queueing and inbound journaling. T1 is not stamped in-process. Tick-to-wire is not measured yet; the FIX codec session implements `TickToWireProbe`.

| Date | Commit | Machine | Interval | Rate | Samples | p50 (us) | p99 (us) | p99.9 (us) | p99.99 (us) | max (us) | Allocation delta (bytes) | Report |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2026-10-31 | defe3303dac4 | laptop above, journaling off | core decode-to-encode | 50000/s | 15009298 | 0.200 | 0.900 | 6.903 | 1190.911 | 6901.759 | 0 | `docs/benchmarks/latency-20261031-073526.json` |
| 2026-10-31 | defe3303dac4 | laptop above, journaling off | scheduled-to-encode | 50000/s | 15000000 | 0.500 | 762.367 | 5570.559 | 19759.103 | 32767.999 | 0 | `docs/benchmarks/latency-20261031-073526.json` |
| 2026-10-31 | defe3303dac4 | laptop above, journaling on, 512 MiB segments preallocated | core decode-to-encode | 50000/s | 15356344 | 0.500 | 12730.367 | 271056.895 | 448790.527 | 479461.375 | 199968 | `docs/benchmarks/latency-20261031-072915.json` |
| 2026-10-31 | defe3303dac4 | laptop above, journaling on, 512 MiB segments preallocated | scheduled-to-encode | 50000/s | 14984173 | 1.200 | 83361.791 | 389021.695 | 548405.247 | 576192.511 | 199968 | `docs/benchmarks/latency-20261031-072915.json` |
| | | | tick-to-wire | | | | | | | | | not measured (FIX codec hook) |

Against the P2 target of p50 under 20 us and p99.9 under 100 us for core decode-to-encode: with journaling off both are met (0.2 us and 6.9 us), with zero bytes allocated on the loop thread over the measured phase. With journaling on the p50 is met (0.5 us) and the p99.9 is not (271 ms).

What the journaling-on tail is. Two earlier runs the same morning (`docs/benchmarks/latency-20261031-070816.json` with segments created on the loop thread, and `docs/benchmarks/latency-20261031-071808.json` with preallocation but the full segment still forced to disk on the loop thread) had p99.9 of 1.1 s and 0.79 s; creating a 512 MiB segment took about 340 ms on this laptop (measured the same day with a one-off program timing `JournalWriter.create`). Moving creation and the final force to a background thread (DR-P2C-03) brought p99.9 to 271 ms. What remains is not the loop's own work: the generator thread, which does no I/O, was itself late by up to 554 ms in the same run, so whole threads were descheduled while the memory-mapped journals were written back to disk. Generator drops (30,922 refused by a full inbound ring) and outbound drops (766, each raising a BackpressureEvent) happened during those stalls. The allocation delta of 199,968 bytes with journaling on comes from segment rolls, which map a file on the loop thread; with journaling off it is 0. Next steps, not done here: pre-fault the next segment's pages on the background thread, and repeat on a pinned Linux host with isolated cores, where DR-005 says gating numbers belong.

### Microbenchmarks (JMH)

Labelled as microbenchmarks; they compare implementations of one operation and do not answer the latency question. `rates-bench`, `mvn -pl rates-bench exec:exec@jmh` settings reduced to 1 fork, 3 warm-up and 5 measured iterations of 2 s; same laptop and JVM, 2026-10-31, commit defe3303dac4; raw results in `docs/benchmarks/jmh-20261031.json`.

| Benchmark | Mean (ns/op) | Iterations (ns/op) | Note |
|---|---|---|---|
| `RingBufferPublishBenchmark.publishThenRead` | 81.7 | 81.8, 83.2, 80.2, 81.9, 81.1 | one 57-byte write and one read of a 1 MiB `ManyToOneRingBuffer` on one thread |
| `JournalAppendBenchmark.append` | 3,239.9 | 14,322.5, 362.6, 611.5, 483.5, 419.3 | one 57-byte frame into a 256 MiB mapped journal; the first iteration includes replacing a full file (zero-filling 256 MiB), the others are 360 to 610 ns per append on this laptop's page-fault path |
