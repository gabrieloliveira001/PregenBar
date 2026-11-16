package net.byebye.pregenBar;

import org.bukkit.Chunk;
import org.bukkit.World;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * Paper's {@code World#getChunkAtAsync(int, int)} by reflection. The build compiles against Spigot,
 * which lacks it; there {@link #hasAsyncChunks()} is false and the task falls back to one
 * synchronous chunk per tick.
 */
public final class PaperCompat {

    private static final Method GET_CHUNK_AT_ASYNC = find();

    private PaperCompat() {}

    private static Method find() {
        try {
            return World.class.getMethod("getChunkAtAsync", int.class, int.class);
        } catch (NoSuchMethodException | SecurityException e) {
            return null;
        }
    }

    public static boolean hasAsyncChunks() {
        return GET_CHUNK_AT_ASYNC != null;
    }

    /** Async load-or-generate. Null when unavailable or the call itself failed. */
    @SuppressWarnings("unchecked")
    public static CompletableFuture<Chunk> getChunkAtAsync(World world, int x, int z) {
        if (GET_CHUNK_AT_ASYNC == null) return null;
        try {
            return (CompletableFuture<Chunk>) GET_CHUNK_AT_ASYNC.invoke(world, x, z);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
