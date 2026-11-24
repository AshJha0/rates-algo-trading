# Tutorials

Four step-by-step tutorials. Each one takes a reader who has cloned the repository to one working result, and says what the result does not mean. [LEARN.md](../../LEARN.md) explains the ideas, [COOKBOOK.md](../../COOKBOOK.md) holds small programs with recorded output, and the [runbooks](../runbooks/README.md) are for operating the process. A tutorial joins the three in the order a new person would meet them.

| Tutorial | You will | Needs |
|---|---|---|
| [01 A first back-test day](01-first-backtest-day.md) | make one synthetic day, run the daily report, read every section, reconcile it in Python | JDK 25, Maven, Python |
| [02 The v2 desk in process](02-v2-desk-profile.md) | run the opt-in v2 desk on the simulated market and read its status | JDK 25, Maven, Python |
| [03 A registered experiment](03-registered-experiment.md) | write an experiment file, validate it, meet each refusal, and stop before running it | Python |
| [04 Wired latency](04-wired-latency.md) | read a committed latency report, then measure your own and say what it is worth | Python; JDK 25, Maven and an idle machine to measure |

## How to read the commands

Run every command from the repository root, in a POSIX shell (Git Bash on Windows). `python` is the interpreter that has the packages of `research/requirements-lock.txt`. Commands that import `ratesresearch` need `PYTHONPATH=research`. Java commands need JDK 25 and Maven.

Each command block is labelled. **Run on 2026-11-24** means the command was run when the tutorial was written and the output shown is what it printed. **Not run here** means it was checked against the source (the class, its arguments, the Maven profile) and not executed for this page; output quoted beside such a block comes from a named, already recorded place.

Nothing here is a claim about speed or profit. Simulated results are statements about the simulator under declared assumptions (DR-008).

## What is deliberately missing

No tutorial runs or signs `research/prereg/R5-FAM-001.yaml`. It is unsigned and has not been run. Tutorial 03 uses a throwaway file of its own and stops at validation.
