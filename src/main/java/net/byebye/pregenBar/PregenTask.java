package net.byebye.pregenBar;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * One world's pre-generation run. Chunks are visited in a square spiral from the centre. All
 * counters are touched on the main thread only: Paper completes {@code getChunkAtAsync} futures on
 * the main thread, and the callback hops there anyway if one ever does not.
 */
public final class PregenTask {

    public enum Shape { SQUARE, CIRCLE }

    final String worldName;
    final int centerX;   // chunk coords
    final int centerZ;
    final int radius;    // chunks
    final int radiusBlocks;
    final Shape shape;
    final long total;

    long nextIndex;
    long processed;
    long generated;
    long skipped;
    long failed;
    boolean paused;
    /** Server tick (from the plugin's tick counter) before which an auto-resumed task waits. */
    long notBefore;
    int lastDecile;

    final ConcurrentSkipListSet<Long> inFlight = new ConcurrentSkipListSet<>();
    BossBar bar;
    /** processed count sampled once per second, newest last. */
    private final Deque<Long> samples = new ArrayDeque<>();
    private long syncCooldown;

    PregenTask(String worldName, int centerX, int centerZ, int radiusBlocks, Shape shape) {
        this.worldName = worldName;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radiusBlocks = radiusBlocks;
        this.radius = Math.max(0, (radiusBlocks + 15) >> 4);
        this.shape = shape;
        this.total = Spiral.total(radius);
    }

    boolean finished() {
        return nextIndex >= total && inFlight.isEmpty();
    }

    double percent() {
        return total == 0 ? 100.0 : Math.min(100.0, processed * 100.0 / total);
    }

    /** Lowest index not known to be done: what a restart resumes from. */
    long resumeIndex() {
        Long low = inFlight.isEmpty() ? null : inFlight.first();
        return low == null ? nextIndex : Math.min(low, nextIndex);
    }

    // ------------------------------------------------------------------ work

    /**
     * Issues work for one tick.
     *
     * @param limit       max chunks in flight (async) for this tick
     * @param syncEvery   on Spigot: generate one chunk every this many ticks
     * @param budgetNanos time budget for skip checks this tick
     */
    void tick(PregenBar plugin, World world, int limit, int syncEvery, long budgetNanos, int maxChecks,
              boolean skipGenerated, boolean respectBorder) {
        long start = System.nanoTime();
        boolean async = PaperCompat.hasAsyncChunks();
        if (!async && syncCooldown > 0) {
            syncCooldown--;
            return;
        }
        WorldBorder border = respectBorder ? world.getWorldBorder() : null;
        long r2 = (long) radius * radius;
        int checks = 0;

        while (nextIndex < total && checks < maxChecks && System.nanoTime() - start < budgetNanos) {
            if (async && inFlight.size() >= limit) return;
            long index = nextIndex;
            int[] d = Spiral.at(index);
            int cx = centerX + d[0];
            int cz = centerZ + d[1];
            checks++;

            if (shape == Shape.CIRCLE && (long) d[0] * d[0] + (long) d[1] * d[1] > r2) {
                nextIndex++;
                processed++;
                continue; // outside the circle: not part of the job, counts as done
            }
            if (border != null && !border.isInside(new Location(world, (cx << 4) + 8, 64, (cz << 4) + 8))) {
                nextIndex++;
                processed++;
                continue;
            }
            if (skipGenerated && world.isChunkGenerated(cx, cz)) {
                nextIndex++;
                processed++;
                skipped++;
                continue;
            }

            if (async) {
                CompletableFuture<Chunk> future = PaperCompat.getChunkAtAsync(world, cx, cz);
                if (future != null) {
                    nextIndex++;
                    inFlight.add(index);
                    future.whenComplete((chunk, error) -> {
                        if (Bukkit.isPrimaryThread()) {
                            complete(index, error == null && chunk != null);
                        } else {
                            Bukkit.getScheduler().runTask(plugin, () -> complete(index, error == null && chunk != null));
                        }
                    });
                    continue;
                }
                // reflection call failed: fall through to a sync load for this one chunk
            }
            nextIndex++;
            try {
                world.getChunkAt(cx, cz);
                world.unloadChunkRequest(cx, cz);
                generated++;
            } catch (RuntimeException e) {
                failed++;
            }
            processed++;
            if (!async) {
                syncCooldown = Math.max(0, syncEvery - 1);
                return; // one sync chunk per tick at most
            }
        }
    }

    private void complete(long index, boolean ok) {
        if (!inFlight.remove(index)) return; // cancelled / reset meanwhile
        processed++;
        if (ok) generated++;
        else failed++;
    }

    // ------------------------------------------------------------------ rate / ETA

    /** Called once per second. */
    void sample() {
        samples.addLast(processed);
        while (samples.size() > 11) samples.removeFirst();
    }

    /** Chunks per second over the last ~10 s. */
    double rate() {
        if (samples.size() < 2) return 0;
        return (samples.peekLast() - samples.peekFirst()) / (double) (samples.size() - 1);
    }

    /** Seconds remaining, or -1 when unknown. */
    long etaSeconds() {
        double rate = rate();
        if (rate <= 0) return -1;
        return (long) Math.ceil((total - processed) / rate);
    }

    void resetSamples() {
        samples.clear();
    }

    // ------------------------------------------------------------------ persistence

    void save(ConfigurationSection sec) {
        sec.set("center-x", centerX);
        sec.set("center-z", centerZ);
        sec.set("radius-blocks", radiusBlocks);
        sec.set("shape", shape.name());
        sec.set("index", resumeIndex());
        sec.set("generated", generated);
        sec.set("skipped", skipped);
        sec.set("failed", failed);
        sec.set("paused", paused);
    }

    static PregenTask load(String world, ConfigurationSection sec) {
        Shape shape;
        try {
            shape = Shape.valueOf(sec.getString("shape", "SQUARE"));
        } catch (IllegalArgumentException e) {
            shape = Shape.SQUARE;
        }
        PregenTask t = new PregenTask(world, sec.getInt("center-x"), sec.getInt("center-z"), sec.getInt("radius-blocks"), shape);
        t.nextIndex = Math.max(0, Math.min(t.total, sec.getLong("index")));
        t.processed = t.nextIndex;
        t.generated = sec.getLong("generated");
        t.skipped = sec.getLong("skipped");
        t.failed = sec.getLong("failed");
        t.paused = sec.getBoolean("paused");
        t.lastDecile = (int) (t.processed * 10 / Math.max(1, t.total));
        return t;
    }
}
