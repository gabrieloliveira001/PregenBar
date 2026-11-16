# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build

Gradle 8.8 wrapper, Java 21, Spigot API 1.21.4 (compile only). Gradle 8.8 cannot run on a JDK newer than 21, so point `JAVA_HOME` at `C:\Program Files\Eclipse Adoptium\jdk-21.0.4.7-hotspot`.

```bash
./gradlew.bat build proguard --no-daemon "-Dorg.gradle.jvmargs=-Xmx768m"   # build/libs/PregenBar-<version>.jar (obfuscated)
```

- `-dev.jar` is the shadow jar (bStats relocated to `net.byebye.pregenBar.libs.bstats`); ProGuard reads it. `build/proguard/mapping.txt` de-obfuscates stack traces.
- Free plugin: no license gate, no /link. No tests.

## Architecture

| Class | Role |
| --- | --- |
| `PregenBar` | Main class, the 1-tick driver, TPS guard, boss bars, `progress.yml`, `/pregen` + tab completion. bStats off while `BSTATS_ID` is 0 (TODO). |
| `PregenTask` | One world's run: centre/radius in chunks, shape, `nextIndex`, counters, in-flight index set, 10 s rate window. `tick()` issues work, `save()/load()` (de)serialise. |
| `Spiral` | Closed-form square spiral `index -> (dx, dz)`; ring k holds indices (2k-1)^2..(2k+1)^2-1. Progress is a single index. |
| `PaperCompat` | `World#getChunkAtAsync(int,int)` by reflection. |
| `Numbers` | `500`, `2.5k`, `1m` parsing for the radius. |
| `Text` | `&` and hex colours. |

### Generation loop

- Paper/Purpur: up to `limit` async loads in flight (`max-concurrent * throttle`). Futures are completed on the main thread by Paper; the callback hops there if not.
- Spigot: one `getChunkAt` + `unloadChunkRequest` every `round(1/throttle)` ticks.
- Each tick the skip checks (circle shape, world border, `isChunkGenerated`) stop at `tick-budget-ms` or `max-checks-per-tick`.
- TPS is self-measured over the last 40 ticks. Every 40 ticks: below `min-tps` -> throttle halves (min 0.05), at or above `min(19.5, min-tps + 1)` -> +0.1 (max 1).

### Persistence

- `progress.yml` -> `tasks.<world>`: centre (chunk coords), `radius-blocks`, shape, `index`, counters, `paused`.
- `index` is the resume point: the lowest in-flight index, else `nextIndex`. On load `processed = index`; the few chunks between that and the old `nextIndex` are re-checked and skipped.
- Saves are serialised on the main thread and written on a single IO thread (temp file + atomic move). `onDisable` waits for the IO thread, then writes synchronously.
- On enable, saved tasks wait `resume-delay-seconds` and, if their world is not loaded yet (Multiverse etc.), keep waiting.

## Conventions

- Compile against Spigot 1.21.4; Paper-only calls by reflection with a fallback. No NMS.
- Every message lives in `config.yml` with a call-site fallback.
- Git: branch `feat/mundo`, no Claude author/co-author lines.
