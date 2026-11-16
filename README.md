# PregenBar

Free chunk pre-generator for Paper / Purpur 1.21.0 - 26.x (Java 21, Spigot works in a slower sync mode). By byebye_gg.

## Features

- Generates chunks in a **spiral** from the centre outwards (square or circle).
- **Async** on Paper/Purpur (`World#getChunkAtAsync`) with a configurable concurrency limit; on Spigot it falls back to one synchronous chunk per tick.
- **TPS guard**: below 18 TPS (configurable) it slows down automatically, and speeds back up when TPS recovers.
- **Skips already generated chunks** and, optionally, chunks outside the world border.
- **Boss bar** for admins: percent, chunks/s, ETA. Console log every 10%.
- **Resumes after a restart**: progress is saved to `progress.yml`.
- Several worlds can run at the same time.

## Commands (permission `pregenbar.admin`, default op)

| Command | |
| --- | --- |
| `/pregen start <world> <radius> [centerX centerZ] [square\|circle]` | Radius in blocks (`5000`, `10k`). The centre defaults to the world border centre. |
| `/pregen pause [world]` / `resume [world]` / `cancel [world]` | The world can be left out when only one task exists. |
| `/pregen status [world]` | Percent, chunks/s, ETA, generated/skipped, TPS, current limit. |
| `/pregen bar` | Hide or show the boss bar for yourself. |
| `/pregen reload` | Reload config.yml. |

## Files

- `config.yml`: concurrency, TPS guard, skip rules, boss bar and messages, documented inline.
- `progress.yml`: running/paused tasks. Delete it (with the server stopped) to forget them.
