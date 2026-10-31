# Time-to-safepoint probe, 2026-10-31

Machine: ASUS Vivobook 17 X1704VA, Intel Core 5 120U, 16 GB, Windows 11. JDK: Temurin 25.0.5+8, -Xms1g -Xmx1g plus the flags shown per line. Program: the scratch prototype of io.ratesalgo.bench.SafepointStallProbe (same three threads and methods; 'channel-touch-yield' is what JournalWriter.pretouch now does). Four files of 512 MiB per line; System.gc() every 20 ms; 'victim longest gap' is the longest stall of a thread that only spins.

```
== -XX:+UseParallelGC | agrona
agrona size=512 MiB count=4: create max 222.1 ms, total 923.6 ms; System.gc calls 28, longest 146.0 ms; victim longest gap 145.9 ms, gaps over 1 ms 28
== -XX:+UseParallelGC -XX:+UseCountedLoopSafepoints -XX:LoopStripMiningIter=1000 | agrona
agrona size=512 MiB count=4: create max 214.8 ms, total 950.6 ms; System.gc calls 42, longest 4.7 ms; victim longest gap 4.9 ms, gaps over 1 ms 42
== -XX:+UseG1GC | agrona
agrona size=512 MiB count=4: create max 227.7 ms, total 1037.5 ms; System.gc calls 43, longest 6.0 ms; victim longest gap 5.9 ms, gaps over 1 ms 43
== -XX:+UseParallelGC | channel
channel size=512 MiB count=4: create max 180.5 ms, total 793.0 ms; System.gc calls 35, longest 3.1 ms; victim longest gap 3.0 ms, gaps over 1 ms 35
== -XX:+UseParallelGC | channel-touch
channel-touch size=512 MiB count=4: create max 297.2 ms, total 1432.3 ms; System.gc calls 55, longest 106.8 ms; victim longest gap 106.7 ms, gaps over 1 ms 56
== -XX:+UseParallelGC | channel-touch-yield
channel-touch-yield size=512 MiB count=4: create max 306.8 ms, total 1491.4 ms; System.gc calls 66, longest 2.7 ms; victim longest gap 2.6 ms, gaps over 1 ms 65
```

Safepoint log extract from the baseline reproduction run (docs/benchmarks/latency-20261031-114010.json, -Xlog:safepoint,gc, JVM uptime in seconds), the three longest times to safepoint, and the journal directory watcher's file events nearest to each:

```
uptime 171.665 s  ParallelCollectForAllocation  reaching 224042200 ns  at 432800 ns  total 224491200 ns
uptime 227.673 s  ParallelCollectForAllocation  reaching 92351200 ns  at 532400 ns  total 92898700 ns
uptime 138.155 s  ParallelCollectForAllocation  reaching 76113500 ns  at 467500 ns  total 76595700 ns

harness clock 48.265 s  file bench-out.000002.raj.pending
harness clock 59.360 s  file bench-in.000002.raj.pending
harness clock 137.708 s  file bench-out.000003.raj.pending
harness clock 137.922 s  sleeper stalled     77.5 ms
harness clock 171.431 s  sleeper stalled    226.2 ms
harness clock 171.432 s  file bench-in.000003.raj.pending
harness clock 222.944 s  sleeper stalled     22.9 ms
harness clock 227.176 s  file bench-out.000004.raj.pending
harness clock 227.440 s  sleeper stalled     94.0 ms
harness clock 283.075 s  file bench-in.000004.raj.pending
harness clock 316.604 s  file bench-out.000005.raj.pending
harness clock 317.590 s  sleeper stalled     27.0 ms
```

The harness clock is the diagnostic wrapper's, which starts 0.248 s after JVM start. The wrapper ran the unchanged harness with two extra threads: one that sleeps 1 ms and logs any oversleep above 20 ms, and one that lists the journal directory every 100 ms (which allocates). 372 safepoints in the run, every one a young collection (ParallelCollectForAllocation). A run of the same code without the directory watcher (128 MiB segments, 20 s warm-up, 120 s measured, report `latency-20261031-114756.json`, not kept) still had 203 young collections, about one every 0.7 s, from the harness gateway thread's per-poll lambda; after the harness fix, the R2 runs in docs/BENCHMARKS.md logged no safepoint at all.
