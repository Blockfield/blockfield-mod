# Controlled anim-sync CPU bench

Re-measures PR #164 (generation-index cache, `1247f26b`) against `origin/main`
(`7b211708`) with a controllable, low-noise bench. The PR #164 matrix (3 runs
per side) had noise larger than the effect: 15x14 END CPU/player raw before
1.646/1.589/1.616 ms, after 1.763/1.567/1.442 ms.

## How to run

One queue job per comparison; the coordinator opens the isolated window
(`systemctl start bf-bench-window.service`) which runs every
`WS/.dev/bench-window/queue/*.sh` in sort order as your user inside CPUs 6-11:

- `01-aa-15x14.sh`: A/A noise floor, 7b211708 vs itself, 15:14 W2, 8 pairs.
- `02-ab-15x14.sh`: A=7b211708 vs B=1247f26b, 15:14 W2, 8 pairs.
- `03-ab-30x9.sh`: same revs, 30:9 W2, 8 pairs.

Each job exports both revs with `git archive` (no rebuild skew), builds each
side once (`:server:remapJar :server:remapOracleJar`, offline), freezes the
JAR SHAs (any mid-matrix rebuild fails the job), then runs 8 ABBA pairs with
a fresh JVM per run:

```sh
python3 dev/anim_sync_bench_ab.py --a-rev 7b211708 --b-rev 1247f26b \
    --cohort 15:14 --pairs 8 --ticks 300 --warmup 120 --workers 2 \
    --out <dir> --git-dir <mod-checkout> --expect-cpus 6-11
```

Results and `summary.json` land under
`WS/.dev/coord-20261011/plans/anim-sync/bench-ctl-logs/`. A/A first: jobs 02
and 03 read the A/A `summary.json` to size the noise floor.

## What the window guarantees

Boost off, CPUs 6-11 frequency pinned (`scaling_min_freq` =
`scaling_max_freq` = 2901000 kHz; the driver rounds 3.0 GHz), everything else
on CPUs 0-5, runner inside cpuset 6-11. ActiveProcessorCount=6.

## How to read the verdict

Primary metric: main-thread CPU ns per END tick per player
(`operations.endTickPerPlayer.cpuNanos.mean / players`). Each ABBA pair gives
one B/A ratio from the mean of its two B runs over the mean of its two A runs,
so linear drift inside the block cancels; all `4 × pairs` runs are used. The
summary reports the median ratio with a bootstrap 95% CI (seed 164). The A/A
job defines the noise band `1 ± h`, where `h` is the larger distance of the A/A
CI from 1. The verdict is `EFFECT BEYOND NOISE` only when the whole B/A CI lies
outside that band; otherwise `NO EFFECT BEYOND NOISE` (or `UNDECIDED` without
an A/A baseline). A and B run the identical command; only the working
directory (the exported revision) differs, and `commands.log` shows both. Both
exports get this checkout's bench harness (`dev/oracle-runner.gradle`,
`AnimSyncBench.java`; hashes in `summary.json` `harness`), so JVM flags and
measurement are the same on both sides and only production code differs. Every run records affinity,
per-second `scaling_cur_freq`, k10temp, loadavg and the bench JSON path. A run
fails when affinity != 6-11 or any bench CPU drifts >3% from the pinned value
(read from `scaling_max_freq` at start). With `--no-window` all results are
marked `controlled: false`.

Estimated wall time: ~3 min/run (measured 2m40s per bench invocation),
16 runs + 2 side builds per job ≈ 55-65 min; three jobs ≈ 3 h, inside the
4 h window limit. Dropping to 6 pairs saves ~15 min/job but widens the CI
roughly by sqrt(8/6).
