# Running the low-latency runtime profile

The `runtime` section of the process configuration (API_SPEC.md Section 17.11, DECISIONS.md DR-R6-L02) sets how the process's threads wait and how it journals. It changes no desk decision: the same desk quotes the same way under either profile. Example: `deploy/config/trading-lowlatency.yaml`, the blue colour with only the runtime section changed.

| Key | `default` profile | `lowLatency` profile |
|---|---|---|
| `profile` | `default` | `lowLatency` |
| loop idle strategy | backoff | busy-spin |
| `gatewayIdleStrategy` (session and feed threads) | park 200 us | busy-spin |
| `journalMode` | `direct` | `ring` |
| `journalHash` | `fnv1a` (journal format version 1) | `word64` (journal format version 2) |
| `journalIdleStrategy` | - | busy-spin |
| `lockJournalPages` | false | true (Linux only) |
| `conflateCurveInputs` | false | true |
| `loopCpu`, `gatewayCpu` | -1 (not pinned) | the host's reserved cores |

Each key can be set on top of the profile.

## Before using it

1. **Reserve cores.** With every thread spinning, the profile keeps about seven cores busy on the recorded desk (the configuration file's own estimate) and more on the v2 desk, which has four sessions and three feeds. Give the process cores the scheduler leaves alone (`isolcpus`/`nohz_full` on the host, or a cpuset such as `docker run --cpuset-cpus=...`). Without that, spinning threads compete with everything else and do worse than the default profile.
2. **Set the CPUs.** `loopCpu` is the loop's core (one more per further loop). `gatewayCpu` is the D2C session's core; the other sessions and the feeds take the next cores in order. Pinning works on Linux only (`CoreAffinity`, `sched_setaffinity` through the JDK's foreign function API, DR-R6-L01); elsewhere the thread runs unpinned and the process starts anyway. The trading image passes `--enable-native-access=ALL-UNNAMED`; without it the JDK prints one warning. The simulated venues' thread is never pinned and never spins.
3. **Page locking.** `lockJournalPages` offers each journal segment to `mlock` within `RLIMIT_MEMLOCK`. Off Linux, or above the limit, every offer is refused and counted; the process runs.
4. **Validate**: `python tools/validate_config.py` checks every `deploy/config/*.yaml` against the schema; the process applies the same check at start.

## Journals written under it

`journalHash: word64` writes journal format version 2, with the algorithm named in the header. Readers: the Java `JournalReader`, `tools/journal_query.py` and `ratesresearch.fnv` all read both versions and say which hash they verified. The two hashes are not interchangeable: a replay compares hashes of the same algorithm, and a journal is not converted from one to the other.

## What it buys, measured

On the development laptop, loaded with other builds, the lowLatency medians from scheduled send to quote written were 0.05 to 4.7 ms against 2 to 25 ms for the default profile, and the spread between runs followed how busy the machine was (docs/BENCHMARKS.md, "The wired desk (1.2.0)"). No quiet-machine figure exists yet. Nothing there is evidence about a production host.

## Back to the default profile

Remove the `runtime` section, or set `profile: default`, and restart. A restart on existing journals keeps the hash their files name, whatever the configuration says; only new journals take `journalHash` (`TradingProcess`, DR-R6-L03).
