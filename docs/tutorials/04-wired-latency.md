# Tutorial 04: measure wired latency and read it honestly

Goal: read a committed latency report line by line, then produce one, and be able to say what it is worth. The methodology and the caveats are in [docs/BENCHMARKS.md](../BENCHMARKS.md); the ideas are in [LEARN.md Section 11](../../LEARN.md#11-low-latency-java-single-writer-off-heap-replay).

## Step 1. Read before you measure

**Run on 2026-11-24.** The quiet-laptop reports behind docs/BENCHMARKS.md are committed. Print one:

```bash
python tools/latency_summary.py docs/benchmarks/wired-20261124-quiet/wired-lowLatency-direct-plain-20261124T082338Z.json
```

Rows from the stage table it prints (microseconds):

| Interval | p50 | p99 | p99.9 | max |
|---|---:|---:|---:|---:|
| scheduled send to quote written (T0 to T4), headline | 64.0 | 163.7 | 346.6 | 6492.2 |
| generator lateness | 20.6 | 54.1 | 128.3 | 6398.0 |
| inbound ring wait | 0.7 | 3.6 | 125.8 | 666.6 |
| inbound journal append | 0.3 | 1.2 | 2.9 | 100.2 |
| handler (pricing, risk, quoting, publish) | 7.7 | 29.2 | 66.9 | 608.8 |
| FIX encode | 2.1 | 6.4 | 14.5 | 102.0 |
| FIX finish and socket write | 33.6 | 76.2 | 145.4 | 1202.2 |

Seven readings, in order.

1. **What starts the clock.** The scheduled send, not the actual one, so the generator's own lateness is inside the headline.
2. **What stops it.** The Quote's bytes written to a loopback socket. No network card, switch or real venue is in the number.
3. **The maximum.** The headline's maximum is 6,492.2 us and the generator's lateness reached 6,398.0. They need not be the same request, but the largest of any desk stage in the table is 1,202.2, so no single stage explains a 6 ms delay.
4. **Medians do not add.** The stage medians do not sum to the median of the whole, and tails add even less.
5. **Samples behind a percentile.** 60,000 requests leave 60 samples above p99.9 and six above p99.99, which the tool marks "few samples". Do not quote that column.
6. **What was not answered.** 59,999 of 60,000. The missing one is listed, not hidden.
7. **What else was running.** The footer shows 3,223 ms of JIT compilation in the measured minute, no collection, no pinned loop (`loopPinned 0`) and four refused page locks (`journalPageLocksRefused 4`; locking works on Linux only).

Now the comparison everybody makes. The default profile's p50 in the same run was 15,401.0 us with about the same handler (7.6 us): its threads park, and on Windows a 200 us park lasts a timer period. That difference is mostly the operating system's timer and says nothing about a Linux host.

## Step 2. Why the schedule matters

If the process stops for 100 ms at 1,000 requests a second, 100 requests are late by 1 to 100 ms, and p99.9 of a 60 s run is about 40 ms. A generator that waited for each answer would record one slow sample in 60,000 and report the normal figure. [LEARN.md Section 13](../../LEARN.md#13-pitfalls-and-numerical-issues) shows the corrected histogram for a closed loop, and recipe [13](../../COOKBOOK.md#13-how-do-i-run-the-latency-job-and-read-the-histogram) prints it.

## Step 3. Measure your own

**Not run here.** Close other work first. The first line installs the project's own artifacts in the local Maven repository, as CI's latency job does; the third is the one that prints.

```bash
mvn -B -ntp -DskipTests -Djacoco.skip=true -Dspotbugs.skip=true install
mvn -B -ntp -pl rates-bench exec:exec@wired -Dwired.label=my-machine
python tools/latency_summary.py rates-bench/target/wired/wired-*.json
```

The properties and their defaults are in `rates-bench/pom.xml`: `wired.rate` 1000, `wired.warmup` 20, `wired.measure` 60, `wired.label` local, `wired.gcCompare` false, `wired.out` the module's `target/wired`, and `wired.configs`, all eight configurations of profile, journal mode and transport. For a first try measure two:

```bash
mvn -B -ntp -pl rates-bench exec:exec@wired -Dwired.configs=default-direct-plain,lowLatency-direct-plain
```

Each configuration runs in a fresh JVM with the trading image's flags and `-Drates.probes=true`. One that exits with an error is reported and the suite goes on. Only the recorded desk profile has a wired figure: the outside D2C endpoint is refused on simulator v2. The older job, `exec:exec@latency`, is the event-core harness and answers a different question; docs/BENCHMARKS.md keeps its results as history and says not to quote them as the desk's latency.

## Step 4. Write the result down

A latency figure without these is not worth keeping:

- commit, JVM version and flags (the report has them);
- machine, processor, operating system, and whether cores were reserved or pinned;
- what else ran, and the generator's lateness beside the headline;
- every run and every configuration, including the bad ones: no run is discarded, for lateness or anything else;
- the samples behind each percentile quoted;
- what was not measured.

Do not call two builds different because their medians differ. The committed runs gave `lowLatency` plain-TCP, direct-journal medians of 49.0, 64.0 and 4,735.0 us on one laptop as its load changed (the commits differ as well). To know whether a change helped, alternate the two builds several times on one idle machine and compare the difference with the spread inside each build.

## Knowledge check

1. Two runs have p50 64 us and 78 us, and p99 generator lateness 54 us and 774 us. What follows about the code? Nothing, until the generator is on time in both.
2. In the quiet run the ring journal's p99 is 898 us against 164 us for the direct journal. Is the ring slower? The table cannot say: one run each, and the ring run's generator was later too (215.8 us against 54.1 at p99).
3. A colleague quotes 0.2 us for the desk. Where might it come from? The older event-core harness, which timed a constant-arithmetic handler that does no desk work.
