# Benchmarks, round 6: the wired desk over loopback FIX (draft)

Draft for the integrator to fold into docs/BENCHMARKS.md. It replaces the headline the review criticised (finding L4: a constant-arithmetic handler timed alone, runs discarded by a lateness rule) with a measurement of the real process. Nothing here is a target met; read the caveats before any number.

## What is measured

`io.ratesalgo.bench.wired.WiredDeskBenchmark` (rates-bench) starts the real trading process, `TradingProcess` with DeskGroup pricing, risk, quoting, hedging and routing, its inbound, outbound and router journals, its FIX sessions and its supervisor, exactly as `TradingProcessMain` wires it, except that the D2C session connects to a load generator instead of the in-process D2C simulator (`TradingProcess.Options.withD2cEndpoint`). The CLOB simulator, its book feed, the curve feed and the venue threads run in the same JVM, as in the simulator deployment.

The load generator is a FIX acceptor on loopback (plain TCP, or TLS 1.3 with the same JDK engine the desk uses). It logs the desk on, then writes QuoteRequests on a fixed schedule, never waiting for an answer. For each Quote it receives it sends a QuoteResponse "pass" so the quote ends and live quotes do not pile up. It runs in the same JVM as the desk, so all stamps share one `System.nanoTime` clock.

Points, per request:

| Point | Where | Stamped by |
|---|---|---|
| T0 | scheduled send time | generator schedule (not the actual send) |
| sent | the generator's socket write returned | generator thread |
| published | QuoteRequest translated to SBE and in the loop's inbound ring | D2C session thread (`GatewayProbe`) |
| read | read by the loop from its ring | loop thread (`LoopProbe`) |
| journaled | appended to the inbound journal (or its ring) | loop thread |
| handled | handler returned; the Quote is in the outbound ring | loop thread |
| dequeued | Quote taken from the outbound ring | D2C session thread |
| encoded | Quote fields encoded | D2C session thread |
| T4 | Quote bytes written to the socket (TLS: wrapped and written) | D2C session thread |
| T5 | Quote read by the generator | generator's reader thread |

Headline: T0 to T4, scheduled send to quote written. Because every interval starts at the scheduled time, a stall anywhere (desk, generator, operating system) is charged to every request it delays: there is no coordinated omission. No run is discarded for any reason; the generator's lateness (write returned minus T0) is reported beside each run instead. Histograms are HdrHistogram, 3 significant digits, 1 ns to 60 s; percentiles p50, p99, p99.9, p99.99 and max. At 60,000 measured requests a p99.99 rests on the six largest samples and is marked "few samples".

The probes are compiled out unless the JVM starts with `-Drates.probes=true` (a constant the JIT folds). The suite sets it; each stamp costs one `System.nanoTime` call, about 20 to 40 ns on this laptop, seven per request.

Configurations: profile `default` (loop backoff, session and feed threads parking 200 us, nothing pinned, FNV-1a journals) or `lowLatency` (loop, session, feed and journal threads busy-spinning, WORD64 chain hash, segments offered for mlock, curve inputs conflated), journal `direct` or `ring`, transport `plain` or `tls`: eight configurations, each in a fresh JVM (`WiredDeskSuite`), with the trading image's JVM flags (deploy/trading/Dockerfile; a test keeps the two lists equal) plus `-Xlog:gc*,safepoint` to a file. With `--gc-compare=true` the suite runs `default-direct-plain` once more under the Parallel collector.

How to run: `mvn -pl rates-bench exec:exec@wired` (properties `wired.rate`, `wired.warmup`, `wired.measure`, `wired.configs`, `wired.gcCompare`, `wired.label`); reports go to `rates-bench/target/wired/`, and `python tools/latency_summary.py rates-bench/target/wired/wired-*.json` prints the tables below. CI's latency job runs all eight on its Linux runner (10 s warm-up, 30 s measured), informational and non-gating, with the tables in the job summary and the reports and JVM logs in the `latency-report` artifact.

## What is not measured

A network interface card, a switch, kernel bypass, a real venue's FIX engine and its latency, another machine, a pinned or isolated core. Loopback TCP on one machine skips the NIC and the interrupt path entirely; a production tick-to-quote adds those, and on a tuned Linux host with kernel TCP the review estimated 15 to 40 us wire to wire at p50 (its estimate, not measured here). The generator shares the machine and the JVM with the desk, so its own work and its own garbage are in every number.

## Results, 2026-11-23, laptop

Read this first. These are in-process-plus-loopback numbers on an unpinned laptop that was also running several other Maven builds and test runs of other sessions throughout (other agents' builds held 1.5 to 3 GB of memory and several cores; JMH's global lock was held by another session's benchmark during the microbenchmark run). Generator lateness reaches milliseconds at p99 in every run, which shows the machine itself was the bottleneck for whole threads at a time. The numbers are what was measured; they say little about the code's floor and nothing about a production host.

Machine: ASUS Vivobook 17 X1704VA, Intel Core 5 120U (2 performance and 8 efficiency cores, 12 logical CPUs), 16 GB, Windows 11 10.0. JVM: Eclipse Temurin 25.0.5+8. Clock resolution (smallest `System.nanoTime` step seen): 100 ns. Flags: `--add-opens` x3, `--enable-native-access=ALL-UNNAMED`, `-XX:+UseZGC -Xms512m -Xmx512m -XX:+AlwaysPreTouch`, `-Xlog:gc*,safepoint:file=...`, `-Drates.probes=true` (`-XX:+UseTransparentHugePages` is Linux-only and left out here). Rate 1,000 QuoteRequests per second, 20 s warm-up (20,000 requests, not recorded), 60 s measured (60,000 requests); requests spread over the D2C swaps and Treasuries, 10 million face, alternating sides, eight clients. Commit 9c1daa9a58dc. Total suite time 17 min 28 s. Reports: `docs/benchmarks/wired-20261123/`.

Scheduled send to quote written (T0 to T4), all nine runs:

| Configuration | Requests | Answered | p50 (us) | p99 (us) | p99.9 (us) | p99.99 (us) | max (us) | Lateness p99 (us) | Lateness max (us) | GCs in measured phase |
|---|---|---|---|---|---|---|---|---|---|---|
| default-direct-plain | 60000 | 59988 | 24772.6 | 63438.8 | 88866.8 | 106758.1 (few samples) | 111018.0 | 17350.7 | 53641.2 | 0 |
| default-ring-plain | 60000 | 59977 | 23838.7 | 56754.2 | 100139.0 | 119341.1 (few samples) | 124321.8 | 14639.1 | 102760.4 | 0 |
| lowLatency-direct-plain | 60000 | 59996 | 4735.0 | 55181.3 | 122093.6 | 152174.6 (few samples) | 156893.2 | 20119.6 | 78708.7 | 0 |
| lowLatency-ring-plain | 60000 | 59997 | 2299.9 | 28934.1 | 56688.6 | 70254.6 (few samples) | 72417.3 | 9674.8 | 48857.1 | 6 |
| default-direct-tls | 60000 | 59985 | 22036.5 | 54591.5 | 277348.4 | 310116.4 (few samples) | 314572.8 | 12181.5 | 243269.6 | 18 |
| default-ring-tls | 60000 | 59996 | 21168.1 | 282591.2 | 575143.9 | 606601.2 (few samples) | 610271.2 | 16719.9 | 103940.1 | 12 |
| lowLatency-direct-tls | 60000 | 59998 | 203.5 | 9674.8 | 22364.2 | 30064.6 (few samples) | 31719.4 | 3389.4 | 30933.0 | 12 |
| lowLatency-ring-tls | 60000 | 59999 | 666.6 | 8962.0 | 21037.1 | 30294.0 (few samples) | 32243.7 | 3090.4 | 28442.6 | 18 |
| default-direct-plain, Parallel collector | 60000 | 59995 | 13770.8 | 40927.2 | 47317.0 | 60751.9 (few samples) | 63668.2 | 745.0 | 36438.0 | 0 |

"Answered" counts requests whose Quote was both written by the desk and read by the generator; the rest (1 to 23 per run) were not quoted (the desk's quoting decision) and are listed, not hidden. ZGC "collections" are concurrent cycles; their pauses are in the GC-choice section.

Stages, p50 / p99 in microseconds (full tables with p99.9, p99.99 and max: `python tools/latency_summary.py docs/benchmarks/wired-20261123/wired-*.json`):

| Configuration | write to published | inbound ring wait | journal append | handler | outbound ring wait | encode | finish and socket write |
|---|---|---|---|---|---|---|---|
| default-direct-plain | 7159.8 / 23855 | 2406.4 / 21201 | 0.4 / 5 | 10.1 / 105 | 12779.5 / 31130 | 2.3 / 15 | 24.5 / 521 |
| default-ring-plain | 7012.4 / 20087 | 1910.8 / 18121 | 0.2 / 4 | 9.9 / 115 | 13172.7 / 26673 | 2.2 / 15 | 25.8 / 442 |
| lowLatency-direct-plain | 897.5 / 15573 | 873.5 / 13492 | 0.3 / 6 | 11.6 / 150 | 225.9 / 15507 | 3.5 / 18 | 36.8 / 1334 |
| lowLatency-ring-plain | 230.4 / 8921 | 329.2 / 8081 | 0.4 / 5 | 12.6 / 92 | 91.6 / 9601 | 4.0 / 18 | 39.7 / 1522 |
| default-direct-tls | 6864.9 / 19333 | 1347.6 / 20169 | 0.4 / 4 | 9.2 / 100 | 7106.6 / 25100 | 2.3 / 15 | 34.0 / 571 |
| default-ring-tls | 6307.8 / 48562 | 1341.4 / 209060 | 0.2 / 4 | 8.9 / 101 | 5464.1 / 68944 | 2.5 / 16 | 34.7 / 931 |
| lowLatency-direct-tls | 5.8 / 3029 | 1.2 / 2906 | 0.6 / 5 | 15.4 / 91 | 1.8 / 3201 | 4.4 / 14 | 62.1 / 820 |
| lowLatency-ring-tls | 12.0 / 2939 | 2.0 / 2867 | 0.6 / 5 | 14.3 / 83 | 3.1 / 3252 | 4.3 / 14 | 57.3 / 1225 |
| default-direct-plain, Parallel | 7229.4 / 15335 | 162.8 / 16458 | 0.4 / 4 | 7.5 / 69 | 438.3 / 16556 | 1.8 / 11 | 20.7 / 161 |

What these say, and what they do not:

- The desk's own work per request is small and stable across runs: inbound journal append 0.2 to 0.6 us at p50, handler (pricing, risk, quoting, publish) 7.5 to 15.4 us at p50 and 69 to 150 us at p99, encode 1.8 to 4.4 us, finish plus socket write 21 to 62 us (TLS higher). Everything else is waiting.
- In the default profile the waits are the parking idle strategies. The session threads park 200 us when idle and the loop backs off to 1 ms parks; on Windows a park of that size lasts a timer period, about 1 to 15.6 ms, so a request waits for the D2C thread to wake, then for the loop, then for the D2C thread again. That gives the 20 to 25 ms medians. On Linux the same parks last close to their nominal length; this run says nothing about that.
- The two lowLatency TLS runs reached p50 0.2 and 0.7 ms with every thread spinning; the two lowLatency plain runs, earlier in the suite, reached 4.7 and 2.3 ms with the same code. The difference is not plain against TLS: their generator lateness was three to six times higher, that is, the machine was busier while they ran. Spinning threads on twelve shared logical CPUs, with other builds running, are descheduled for milliseconds; the p99 of every run is in milliseconds for that reason.
- Direct against ring journaling cannot be separated in this data: the journal append itself is 0.2 to 0.6 us at p50 in both, under the noise of every other stage. The ring journal threads kept up: no producer waited in any ring run, and the largest backlog seen was 72 KB of a 64 MB ring (`journalBacklogHighWaterBytes`).
- The Parallel-collector run was faster than its ZGC twin at every percentile, with zero collections in both measured phases; it ran last, when the machine happened to be quieter (generator lateness p99 0.7 ms against 17 ms). It is not evidence for either collector; see the GC section.
- JIT compilation was still running in the measured phase of every run (2 to 7 s of compiler time per run, `jitMillisMeasured`); 20 s of warm-up at 1,000 per second was not enough on a loaded machine. Longer warm-ups belong on a quiet host.
- Page locking (`lowLatency`) is Linux-only; here every segment offered for mlock was counted as refused, which is the expected outcome off Linux.
- Curve-input conflation (`lowLatency`) replaced 10 held inputs in one run and none in the others (`conflated`): at this load the curve feed's burst every 500 ms rarely leaves two inputs for one instrument waiting at once. The handler's p99 is no lower with it; this suite does not isolate the reprice a request waits behind.

Earlier BENCHMARKS.md figures for the event core alone (p50 0.1 to 0.5 us, p99.9 1.6 us) are not comparable to these: they time the loop on a constant handler, with no FIX, no desk and no gateways.

## GC choice (DECISIONS.md DR-R6-L06), evidence from the GC logs of this suite

- Plain runs: zero collections in the measured phase under both collectors. The loop, the FIX codec and the plain transport allocate nothing per message; the garbage that remains comes from the in-process venue simulators (QuickFIX/J) and the HTTP endpoints.
- TLS runs: 12 to 18 ZGC cycles per measured minute (the JDK's TLS engine allocates about 1.3 KB per record on each side, measured by `TlsAllocationTest`). ZGC pauses in every run were at most 0.47 ms (one, at startup) and at most 0.07 ms in the measured phases.
- Time to safepoint was the larger cost: up to 15 to 23 ms in most runs ("Reaching safepoint" in the logs), against under 1 ms in the two quietest runs. A thread that is descheduled cannot reach a safepoint; on this laptop that is the operating system, not the collector.
- The Parallel collector made one 11.3 ms full collection at startup (metadata threshold) and none in the measured phase of the one plain run it was given. It was not run with TLS; a Parallel young collection stops every thread, the loop included, for the whole collection, and the TLS runs show one would come every few seconds.

Decision: keep ZGC in the trading image. Not measured here, and needed before any change: ZGC against Parallel on the TLS configurations on a quiet Linux host.

## Microbenchmarks (JMH), 2026-11-23

Same laptop, JVM and background load as above (another session's JMH run held the global JMH lock; this run ignored it, so the two ran at the same time). 1 fork, 3 warm-up and 5 measured iterations of 1 s. Raw results: `docs/benchmarks/jmh-round6-20261123.json`. The error bars are wide; read the ratios, not the absolute values.

Chain hash, one step chained to the previous result (`ChainHashBenchmark`):

| Payload (bytes) | FNV-1a, format 1 (ns) | WORD64, format 2 (ns) | CRC32C intrinsic, not used (ns) |
|---|---|---|---|
| 57 | 363.6 +/- 212.9 | 75.6 +/- 71.6 | 47.0 +/- 21.2 |
| 120 | 705.3 +/- 451.1 | 145.1 +/- 48.9 | 57.4 +/- 26.3 |
| 512 | 3424.8 +/- 1161.2 | 437.6 +/- 123.8 | 141.1 +/- 176.3 |

WORD64 is 4.8 to 7.8 times faster than FNV-1a here. CRC32C is faster still but keeps 32 bits; the chain keeps 64 (DR-R6-L03).

FIX Quote encode (`FixEncodeBenchmark`, header and body fields, BeginString, BodyLength, CheckSum; no socket write):

| Variant | Mean (ns) |
|---|---|
| before round 6 (`LegacyFixWriter`, a verbatim copy: body copied behind the header, byte-wise checksum, date recomputed for every stamp) | 3005.2 +/- 1377.6 |
| today's encoder finishing into a second buffer (one copy) | 2340.1 +/- 950.7 |
| today's encoder as the session uses it (in place, no copy) | 2816.2 +/- 2614.5 |

Under this noise the three cannot be told apart; the in-place variant's error bar spans the other two. The change removes one body copy (about 250 bytes) and the second copy in the plain transport, and caches the date part of three timestamps per message; what that buys must be measured on a quiet machine.
