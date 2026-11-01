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
- Journal mode (off, direct or ring), segment size, and the counts of rolls that waited for a prepared segment and of appends that waited for room in a ring.
- Garbage collections during the measured phase and their total time. Every collection is a safepoint and stops the loop thread, whichever thread made the garbage (DECISIONS.md DR-R2J-01).
- Date and time.

## Allocation check

On the event core thread, `ThreadMXBean.getThreadAllocatedBytes(threadId)` is read after warm-up and again after the measured phase. The delta must be zero for a run to be labelled allocation-free. A non-zero delta is reported with the number of bytes and the run is still reported, labelled accordingly. Where the delta is non-zero, an allocation profile (JFR) is attached.

The gating unit test, `LoopAllocationTest`, uses windows rather than a single delta (DR-INT-02). After one million warm-up messages it measures three consecutive windows of one million messages each, reading the loop thread's counter from the test thread by thread id (the bean is called once before warm-up, so a first-call cost cannot land in a window). The last window must be exactly zero and the second and third must each be at most 1,024 bytes; the first is reported but not bounded. A one-off cost after warm-up, such as a rarely taken path running for the first time, shows in one window only, while a leak of even one byte per message shows as at least a megabyte in every window. The failure message lists each window's bytes with its dropped-message and backpressure counts.

## Where it runs

Hosted CI runners (GitHub-hosted Linux and Windows) produce informational numbers only. Their hardware is shared and varies between runs, so no threshold gates on them. Gating applies only on a self-hosted, pinned runner with a recorded configuration, when one exists (DECISIONS.md DR-005). Until then, reports come from the development machine and say so.

## Results

Rows are added with a link to the report file and the run date. Every row so far is in-process on a Windows laptop: the generator, the loop and the outbound gateway are threads of one JVM sharing one monotonic clock, no core is isolated or pinned, and the laptop runs its usual background load. By the rule in "Fixed-rate load generator" every P2 run below would be discarded, because the generator fell behind its schedule (generator lateness p99 above the 20 us interval in each report); they are recorded because they are the first evidence, and the lateness is in each report. The R2 runs after the change and not marked (b) are the first whose generator kept to schedule (generator lateness p99 7.1 to 11.9 us); the R2 reproduction before the change was just behind (20.2 us).

The two rows dated 2026-11-01 are the exception: they come from CI's informational latency job on a GitHub-hosted ubuntu-latest runner (run https://github.com/AshJha0/rates-algo-trading/actions/runs/41290006100, which writes these percentiles to its job summary; DECISIONS.md DR-V1G-04). That runner is a shared 2 vCPU virtual machine, not a tuned host: the generator, loop and gateway threads compete for two vCPUs with the runner's own processes, the run is short (10 s warm-up, 20 s measured, 1,000,000 recorded samples) and the idle strategy is yield, so the scheduled-to-encode p50 of 766 us is mostly generator and scheduling delay. Read these rows as a Linux reference point that each CI run refreshes, not as a measure of the platform; DR-005 still applies.

Machine for all other rows: ASUS Vivobook 17 X1704VA, Intel Core 5 120U (12 logical CPUs), 16 GB, Windows 11 (10.0). JVM: Eclipse Temurin 25.0.5+8, flags `-Xms1g -Xmx1g -XX:+AlwaysPreTouch -XX:+UseParallelGC` plus the three Agrona `--add-opens`. Loop idle strategy busy spin, not pinned. Load: 50,000 QuoteRequests per second, 60 s warm-up (3,000,000 messages, not recorded), 300 s measured. Harness: `io.ratesalgo.bench.LatencyHarness` (`rates-bench`).

Intervals (DECISIONS.md DR-P2C-08): core decode-to-encode is T3 minus T2, handler entry before the SBE decode to the Quote published on the outbound ring and journaled, recorded with `recordValueWithExpectedInterval` at 20 us; scheduled-to-encode is T3 minus T0 from the generator's scheduled send time, so it includes generator lateness, ring queueing and inbound journaling. T1 is not stamped in-process. Tick-to-wire is not measured yet; the FIX codec session implements `TickToWireProbe`.

| Date | Commit | Machine | Interval | Rate | Samples | p50 (us) | p99 (us) | p99.9 (us) | p99.99 (us) | max (us) | Allocation delta (bytes) | Report |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2026-10-31 | defe3303dac4 | laptop above, journaling off | core decode-to-encode | 50000/s | 15009298 | 0.200 | 0.900 | 6.903 | 1190.911 | 6901.759 | 0 | `docs/benchmarks/latency-20261031-073526.json` |
| 2026-10-31 | defe3303dac4 | laptop above, journaling off | scheduled-to-encode | 50000/s | 15000000 | 0.500 | 762.367 | 5570.559 | 19759.103 | 32767.999 | 0 | `docs/benchmarks/latency-20261031-073526.json` |
| 2026-10-31 | defe3303dac4 | laptop above, journaling on, 512 MiB segments preallocated | core decode-to-encode | 50000/s | 15356344 | 0.500 | 12730.367 | 271056.895 | 448790.527 | 479461.375 | 199968 | `docs/benchmarks/latency-20261031-072915.json` |
| 2026-10-31 | defe3303dac4 | laptop above, journaling on, 512 MiB segments preallocated | scheduled-to-encode | 50000/s | 14984173 | 1.200 | 83361.791 | 389021.695 | 548405.247 | 576192.511 | 199968 | `docs/benchmarks/latency-20261031-072915.json` |
| 2026-10-31 | 4facfef57c3e | laptop above, journaling on, 512 MiB, R2 reproduction before any change | core decode-to-encode | 50000/s | 15004683 | 0.200 | 2.201 | 8.407 | 174.207 | 9428.991 | 192944 | `docs/benchmarks/latency-20261031-114010.json` |
| 2026-10-31 | 4facfef57c3e | laptop above, journaling on, 512 MiB, R2 reproduction before any change | scheduled-to-encode | 50000/s | 15000000 | 0.700 | 63.007 | 36765.695 | 196345.855 | 224657.407 | 192944 | `docs/benchmarks/latency-20261031-114010.json` |
| 2026-10-31 | 04d12a503a6b | laptop above, journaling off, R2 harness | core decode-to-encode | 50000/s | 15000615 | 0.100 | 0.200 | 1.100 | 13.503 | 468.735 | 0 | `docs/benchmarks/latency-20261031-121134.json` |
| 2026-10-31 | 04d12a503a6b | laptop above, journaling off, R2 harness | scheduled-to-encode | 50000/s | 15000000 | 0.300 | 38.527 | 232.319 | 720.895 | 4489.215 | 0 | `docs/benchmarks/latency-20261031-121134.json` |
| 2026-10-31 | 04d12a503a6b (a) | laptop above, journaling on, direct, 512 MiB | core decode-to-encode | 50000/s | 15001070 | 0.200 | 0.500 | 1.600 | 28.303 | 336.127 | 0 | `docs/benchmarks/latency-20261031-115541.json` |
| 2026-10-31 | 04d12a503a6b (a) | laptop above, journaling on, direct, 512 MiB | scheduled-to-encode | 50000/s | 15000000 | 0.500 | 26.303 | 183.551 | 2945.023 | 9347.071 | 0 | `docs/benchmarks/latency-20261031-115541.json` |
| 2026-10-31 | 04d12a503a6b (a) | laptop above, journaling on, ring, 512 MiB, 64 MiB rings | core decode-to-encode | 50000/s | 15000861 | 0.100 | 0.400 | 1.600 | 23.503 | 440.319 | 0 | `docs/benchmarks/latency-20261031-120234.json` |
| 2026-10-31 | 04d12a503a6b (a) | laptop above, journaling on, ring, 512 MiB, 64 MiB rings | scheduled-to-encode | 50000/s | 15000000 | 0.500 | 28.703 | 457.215 | 8552.447 | 15687.679 | 0 | `docs/benchmarks/latency-20261031-120234.json` |
| 2026-10-31 | 04d12a503a6b | journaling on, direct, 512 MiB; discarded by rule (b) | core decode-to-encode | 50000/s | 15007805 | 0.200 | 0.800 | 3.601 | 4188.159 | 19546.111 | 976 | `docs/benchmarks/latency-20261031-121739.json` |
| 2026-10-31 | 04d12a503a6b | journaling on, direct, 512 MiB; discarded by rule (b) | scheduled-to-encode | 50000/s | 15000000 | 0.500 | 296.703 | 20463.615 | 49709.055 | 64258.047 | 976 | `docs/benchmarks/latency-20261031-121739.json` |
| 2026-10-31 | 04d12a503a6b | journaling on, ring, 512 MiB; discarded by rule (b) | core decode-to-encode | 50000/s | 15003630 | 0.100 | 0.600 | 2.000 | 286.463 | 6320.127 | 0 | `docs/benchmarks/latency-20261031-122405.json` |
| 2026-10-31 | 04d12a503a6b | journaling on, ring, 512 MiB; discarded by rule (b) | scheduled-to-encode | 50000/s | 15000000 | 0.400 | 233.599 | 16031.743 | 122093.567 | 151650.303 | 0 | `docs/benchmarks/latency-20261031-122405.json` |
| 2026-10-31 | 04d12a503a6b | journaling on, direct, 512 MiB; discarded by rule (b) | core decode-to-encode | 50000/s | 15020581 | 0.400 | 0.900 | 275.455 | 12500.991 | 30408.703 | 0 | `docs/benchmarks/latency-20261031-123100.json` |
| 2026-10-31 | 04d12a503a6b | journaling on, direct, 512 MiB; discarded by rule (b) | scheduled-to-encode | 50000/s | 15000000 | 0.800 | 1462.271 | 19513.343 | 35880.959 | 46792.703 | 0 | `docs/benchmarks/latency-20261031-123100.json` |
| 2026-10-31 | 04d12a503a6b | journaling on, ring, 512 MiB; discarded by rule (b) | core decode-to-encode | 50000/s | 15009258 | 0.200 | 0.800 | 4.103 | 2797.567 | 17350.655 | 0 | `docs/benchmarks/latency-20261031-123733.json` |
| 2026-10-31 | 04d12a503a6b | journaling on, ring, 512 MiB; discarded by rule (b) | scheduled-to-encode | 50000/s | 15000000 | 0.500 | 3463.167 | 55083.007 | 97583.103 | 122486.783 | 0 | `docs/benchmarks/latency-20261031-123733.json` |
| 2026-11-01 | 8bfe1b8af42f | GitHub-hosted ubuntu-latest, a shared hosted runner and not a tuned host (2 vCPU AMD EPYC 9V45 VM, Linux 7.0.0-1017-azure), journaling on, idle yield, 10 s warm-up, 20 s measured | core decode-to-encode | 50000/s | 1000376 | 0.160 | 5.939 | 9.511 | 2805.759 | 4804.607 | 0 | CI run https://github.com/AshJha0/rates-algo-trading/actions/runs/41290006100, job summary and `latency-report` artifact |
| 2026-11-01 | 8bfe1b8af42f | GitHub-hosted ubuntu-latest, a shared hosted runner and not a tuned host (2 vCPU AMD EPYC 9V45 VM, Linux 7.0.0-1017-azure), journaling on, idle yield, 10 s warm-up, 20 s measured | scheduled-to-encode | 50000/s | 1000000 | 765.951 | 5591.039 | 82051.071 | 99680.255 | 101646.335 | 0 | CI run https://github.com/AshJha0/rates-algo-trading/actions/runs/41290006100, job summary and `latency-report` artifact |
| | | | tick-to-wire | | | | | | | | | not measured (FIX codec hook) |

(a) Run from the working tree before the commit was made, with the main sources of `rates-journal`, `rates-core` and `rates-bench` as committed in 04d12a503a6b; the report's `commit` field says 4facfef57c3e because the harness reads `HEAD`. These two ran inside the diagnostic wrapper of the cause section (sleeping-thread stall log, `-Xlog:safepoint,gc`, Windows counters; the directory watcher off).

(b) The generator fell behind its schedule (generator lateness p99 83.6, 69.4, 564.7 and 1,032.7 us against the 20 us interval), so by the rule in "Fixed-rate load generator" these runs are discarded; they are kept because they show what load from outside the run does. During the first of them another session's `javac` was running on the laptop.

Against the P2 target of p50 under 20 us and p99.9 under 100 us for core decode-to-encode: with journaling off both are met (0.2 us and 6.9 us), with zero bytes allocated on the loop thread over the measured phase. With journaling on the p50 is met (0.5 us) and the p99.9 is not (271 ms).

What the journaling-on tail was, as P2 read it (superseded by the next section). Two earlier runs the same morning (`docs/benchmarks/latency-20261031-070816.json` with segments created on the loop thread, and `docs/benchmarks/latency-20261031-071808.json` with preallocation but the full segment still forced to disk on the loop thread) had p99.9 of 1.1 s and 0.79 s; creating a 512 MiB segment took about 340 ms on this laptop (measured the same day with a one-off program timing `JournalWriter.create`). Moving creation and the final force to a background thread (DR-P2C-03) brought p99.9 to 271 ms. What remains is not the loop's own work: the generator thread, which does no I/O, was itself late by up to 554 ms in the same run, so whole threads were descheduled while the memory-mapped journals were written back to disk. Generator drops (30,922 refused by a full inbound ring) and outbound drops (766, each raising a BackpressureEvent) happened during those stalls. The allocation delta of 199,968 bytes with journaling on comes from segment rolls, which map a file on the loop thread; with journaling off it is 0. Next steps, not done here: pre-fault the next segment's pages on the background thread, and repeat on a pinned Linux host with isolated cores, where DR-005 says gating numbers belong.

### Cause of the journaling-on tail (R2, 2026-10-31)

Measured before any code was changed, on the laptop above, JDK and flags as above, commit 4facfef57c3e (main after PR #2), by the R2 journal session. Evidence files: `docs/benchmarks/latency-20261031-114010.json` (the reproduction) and `docs/benchmarks/safepoint-evidence-20261031.md` (the safepoint log extract and the probe output). DECISIONS.md DR-R2J-01 has the decision.

Reproduction. The harness, unchanged, ran with journaling on, 512 MiB segments, 60 s warm-up and 300 s measured inside a diagnostic wrapper that added `-Xlog:safepoint,gc`, a thread that sleeps 1 ms and logs any oversleep above 20 ms, a watcher of the journal directory, and Windows performance counters every second (`typeperf`: available memory, modified page list, page faults, transition faults, page reads, cache dirty pages, disk write bytes and latency, queue length, processor time). In that run the core decode-to-encode p99.9 was 8.4 us but scheduled-to-encode reached p99.9 36.8 ms and a maximum of 224.7 ms, and the loop thread allocated 192,944 bytes (rows below). The tail moves between the two intervals from run to run because a stalled loop thread stops wherever it next polls for a safepoint: inside a handler, the stall lands in decode-to-encode (the P2 run); between messages, only in scheduled-to-encode (this run).

What the stalls were. Every stall above 20 ms of the sleeping thread (226, 94 and 77 ms) was a safepoint whose time to safepoint was the stall (224.0, 92.4 and 76.1 ms; the collection itself took under 0.6 ms), and each began within 0.3 s of a `.pending` segment file appearing, that is, as the background thread started to create the next 512 MiB segment. Available memory stayed between 4.6 and 7.1 GB and page reads from disk averaged 21 per second, so memory pressure and hard faults on the loop thread are ruled out; Windows write-back of the mapped journals, the P2 explanation, does not stop a thread that is not touching the file.

Mechanism. `JournalWriter.create` called Agrona's `IoUtil.mapNewFile(file, size, true)`, which zero-fills by storing one byte per 4 KiB page through the new mapping, 131,072 stores for 512 MiB, in a counted `int` loop (checked with `javap`). Each store is a page fault taken in Java code. Under `-XX:+UseParallelGC`, C2 compiles counted loops without a safepoint poll (`UseCountedLoopSafepoints` is false and `LoopStripMiningIter` 0 under ParallelGC; both are on under G1, checked with `-XX:+PrintFlagsFinal`). So once the fill loop is compiled, a safepoint requested during it waits for the rest of the fill, and every other thread, the loop thread and the generator included, waits at its own next poll. The safepoints themselves came from the harness: its gateway thread passed a capturing lambda to `outbound.read` inside its polling loop, one new object per poll, and the garbage caused a young collection about every 0.6 s (203 collections in a 145 s run without the directory watcher). With a fill lasting 0.1 to 0.3 s, three of the seven segment creations in the reproduction overlapped a collection.

Isolated by `SafepointStallProbe` (`rates-bench`): one thread creates four 512 MiB files, one calls `System.gc()` every 20 ms, one only spins and records its longest gap.

| Creation method | Collector and flags | Longest gap of the spinning thread (ms) |
|---|---|---|
| Agrona `mapNewFile` zero-fill | ParallelGC | 145.9 |
| Agrona `mapNewFile` zero-fill | ParallelGC, `-XX:+UseCountedLoopSafepoints -XX:LoopStripMiningIter=1000` | 4.9 |
| Agrona `mapNewFile` zero-fill | G1 | 5.9 |
| zeros written with `FileChannel.write` | ParallelGC | 3.0 |
| channel writes, then one store per page in a plain loop | ParallelGC | 106.7 |
| channel writes, then one store per page with `Thread.yield()` every 16 pages (R2) | ParallelGC | 2.6 |

The remaining 2.6 to 3.0 ms is the full collection that `System.gc()` performs, which the spinning thread waits for at any time.

What changed (DR-R2J-01, DR-R2J-02): segment files are zero-filled with channel writes; every page of the current segment and of the next one is touched on a background thread with a safepoint check every 16 pages; the next segment is prepared as soon as the current one starts; a roll hands writers over through volatile fields and maps, forces and allocates nothing on the loop thread; the harness creates its gateway handler once. A second mode (`RING`, DR-R2J-03) moves the mapped write and the chain hash to a journal thread.

### Results after the R2 change (2026-10-31)

Rows above marked 04d12a503a6b, same laptop, JDK and flags, 50,000 QuoteRequests per second, 60 s warm-up, 300 s measured, 512 MiB segments; runs at the commit add `-Xlog:safepoint`. Against the target for core decode-to-encode (p50 under 20 us, p99.9 under 100 us), journaling on:

| Run | Mode | p50 (us) | p99.9 (us) | max (us) | Generator lateness p99 (us) | Safepoints | Collections | Roll waits | Ring waits | Loop allocation (bytes) |
|---|---|---|---|---|---|---|---|---|---|---|
| `latency-20261031-115541.json` | direct | 0.200 | 1.600 | 336.127 | 7.1 | 0 | 0 | 0 | 0 | 0 |
| `latency-20261031-120234.json` | ring | 0.100 | 1.600 | 440.319 | 8.7 | 0 | 0 | 0 | 0 | 0 |
| `latency-20261031-121739.json` (b) | direct | 0.200 | 3.601 | 19546.111 | 83.6 | 0 | 0 | 0 | 0 | 976 |
| `latency-20261031-122405.json` (b) | ring | 0.100 | 2.000 | 6320.127 | 69.4 | 0 | 0 | 0 | 0 | 0 |
| `latency-20261031-123100.json` (b) | direct | 0.400 | 275.455 | 30408.703 | 564.7 | 0 | 0 | 0 | 0 | 0 |
| `latency-20261031-123733.json` (b) | ring | 0.200 | 4.103 | 17350.655 | 1032.7 | 0 | 0 | 0 | 0 | 0 |

With journaling on, the target is met in both modes in every run whose generator kept to schedule: p50 0.1 to 0.2 us and p99.9 1.6 us, against 0.5 us and 271 ms in the P2 run, with no safepoint, no collection, no roll that waited and no byte allocated on the loop thread. The journaling-off run at the same commit (`latency-20261031-121134.json`) gives p99.9 1.1 us, so with the change journaling costs the loop about half a microsecond at p99.9. Three of the four discarded runs also meet the target; the fourth (`123100`, direct) reaches 275 us at p99.9 while its generator, which does no journaling, was late by 564.7 us at p99 and 21.3 ms at p99.9.

Load from outside the run, not the journal, explains the discarded runs. Two shorter runs on 2026-10-31 at 04d12a503a6b (20 s warm-up, 120 s measured) with ten threads of a separate JVM spinning for the whole run: journaling off (`latency-20261031-124426.json`) reached decode-to-encode p99.9 11,444 us with generator lateness p99 29.1 ms; direct (`latency-20261031-124658.json`) reached p99.9 5,575 us with generator lateness p99 9.1 ms. Neither logged a safepoint or a roll wait. On this laptop (2 performance and 8 efficiency cores, 12 logical CPUs, threads not pinned), a busy-spinning loop thread that shares the CPUs with other busy threads is descheduled for milliseconds at a time whatever it does. The 976 bytes in `121739` were allocated in its measured phase; the run had no roll wait and no backpressure, and the source was not identified (`LoopAllocationTest` measures the same code at zero in both modes). The pinned, isolated Linux host of DR-005 is where the tail beyond p99.9 should be judged.

Commit 9f30a11, after 04d12a503a6b, changes the measured code in two places, found by SpotBugs and not re-measured: `RingJournal`'s closed flag is volatile (one more volatile read per ring-mode append, a plain load on x86-64), and `LoopJournals` releases a half-opened journal pair in a `finally` block instead of a `catch`.

### Microbenchmarks (JMH)

Labelled as microbenchmarks; they compare implementations of one operation and do not answer the latency question. `rates-bench`, `mvn -pl rates-bench exec:exec@jmh` settings reduced to 1 fork, 3 warm-up and 5 measured iterations of 2 s; same laptop and JVM, 2026-10-31, commit defe3303dac4; raw results in `docs/benchmarks/jmh-20261031.json`.

| Benchmark | Mean (ns/op) | Iterations (ns/op) | Note |
|---|---|---|---|
| `RingBufferPublishBenchmark.publishThenRead` | 81.7 | 81.8, 83.2, 80.2, 81.9, 81.1 | one 57-byte write and one read of a 1 MiB `ManyToOneRingBuffer` on one thread |
| `JournalAppendBenchmark.append` | 3,239.9 | 14,322.5, 362.6, 611.5, 483.5, 419.3 | one 57-byte frame into a 256 MiB mapped journal; the first iteration includes replacing a full file (zero-filling 256 MiB), the others are 360 to 610 ns per append on this laptop's page-fault path |

R2 (DR-R2J-03): `JournalModeAppendBenchmark`, the loop thread's cost of journaling one 57-byte QuoteRequest in each mode, into a 128 MiB segment created and touched in the iteration's set-up (so no file creation and no page fault is measured; the background thread meanwhile prepares the next segment). 1 fork, 3 warm-up and 5 measured iterations of 20 ms, `-Xms1g -Xmx1g -XX:+UseParallelGC`; same laptop and JDK, 2026-10-31, commit 04d12a503a6b; raw results in `docs/benchmarks/jmh-journal-mode-20261031.json`.

| Benchmark | Mean (ns/op) | Iterations (ns/op) | Note |
|---|---|---|---|
| `JournalModeAppendBenchmark.append`, `direct` | 99.6 | 62.2, 62.3, 163.6, 64.9, 145.3 | copy, chain hash over 57 bytes, header, ordered length store into a touched page; the two slow iterations overlap the background preparation of the next segment |
| `JournalModeAppendBenchmark.append`, `ring` | 29.6 | 19.2, 25.3, 38.5, 36.6, 28.5 | claim, stamp, copy and commit into a 128 MiB ring; the journal thread drains concurrently and the ring never filled |

Compared with the P2 figure of 360 to 610 ns per append, the direct append is about 62 ns once its page is touched. The iterations are short and the spread is wide; read these as orders of magnitude.
