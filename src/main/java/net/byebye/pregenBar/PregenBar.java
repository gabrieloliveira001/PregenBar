package net.byebye.pregenBar;

import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * PregenBar: chunk pre-generator with a boss bar for admins. Free and open, by byebye_gg.
 *
 * <p>One 1-tick timer drives every task. It measures TPS itself (works on Spigot too) and adapts a
 * throttle over a 2 s window: below {@code min-tps} the allowed concurrency halves, above it it climbs
 * back by 10% of the maximum.
 */
public final class PregenBar extends JavaPlugin implements TabCompleter, Listener {

    // TODO: register the plugin at https://bstats.org/ and put its id here. 0 = bStats off.
    private static final int BSTATS_ID = 35389;
    private static final String PERM = "pregenbar.admin";

    private final Map<String, PregenTask> tasks = new LinkedHashMap<>();
    private final Set<UUID> hiddenBars = new HashSet<>();
    private final long[] tickTimes = new long[41];
    private int tickPos;
    private int tickCount;
    private long serverTick;
    private double tps = 20.0;
    private double throttle = 1.0;
    private BukkitTask ticker;
    private ExecutorService io;
    private File progressFile;

    // config snapshot
    private int maxConcurrent;
    private double minTps;
    private long budgetNanos;
    private int maxChecks;
    private boolean skipGenerated;
    private boolean respectBorder;
    private int saveIntervalSeconds;
    private int resumeDelaySeconds;
    private boolean logProgress;
    private PregenTask.Shape defaultShape;
    private String barTitle;
    private String barPausedTitle;
    private BarColor barColor;
    private BarStyle barStyle;

    @Override
    public void onEnable() {
        loadSettings();
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "PregenBar-IO");
            t.setDaemon(true);
            return t;
        });
        progressFile = new File(getDataFolder(), "progress.yml");
        loadProgress();

        PluginCommand command = getCommand("pregen");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(this, this);
        ticker = Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        if (!PaperCompat.hasAsyncChunks()) {
            getLogger().info("Paper async chunk API not found: generating one chunk per tick (synchronous).");
        }
        if (BSTATS_ID > 0) new Metrics(this, BSTATS_ID);
    }

    @Override
    public void onDisable() {
        if (ticker != null) ticker.cancel();
        if (io != null) {
            io.shutdown();
            try {
                io.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        writeNow(serialize());
        for (PregenTask t : tasks.values()) removeBar(t);
    }

    private void loadSettings() {
        saveDefaultConfig();
        reloadConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        FileConfiguration c = getConfig();
        maxConcurrent = Math.max(1, c.getInt("generation.max-concurrent", 16));
        minTps = c.getDouble("generation.min-tps", 18.0);
        budgetNanos = Math.max(1, c.getInt("generation.tick-budget-ms", 5)) * 1_000_000L;
        maxChecks = Math.max(1, c.getInt("generation.max-checks-per-tick", 400));
        skipGenerated = c.getBoolean("generation.skip-generated", true);
        respectBorder = c.getBoolean("generation.respect-world-border", true);
        saveIntervalSeconds = Math.max(5, c.getInt("generation.save-interval-seconds", 30));
        resumeDelaySeconds = Math.max(0, c.getInt("generation.resume-delay-seconds", 10));
        logProgress = c.getBoolean("generation.log-every-10-percent", true);
        try {
            defaultShape = PregenTask.Shape.valueOf(c.getString("generation.shape", "square").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            defaultShape = PregenTask.Shape.SQUARE;
        }
        barTitle = c.getString("boss-bar.title",
                "&bPregen &f%world% &8| &f%percent%% &8| &f%rate% &7chunks/s &8| &7ETA &f%eta%");
        barPausedTitle = c.getString("boss-bar.paused-title", "&ePregen &f%world% &8| &f%percent%% &8| &e&lPAUSED");
        barColor = parse(BarColor.class, c.getString("boss-bar.color", "BLUE"), BarColor.BLUE);
        barStyle = parse(BarStyle.class, c.getString("boss-bar.style", "SEGMENTED_10"), BarStyle.SEGMENTED_10);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private String msg(String key, String fallback) {
        String raw = getConfig().getString("messages." + key, fallback);
        if (raw == null || raw.isEmpty()) return "";
        return Text.colorize(raw.replace("%prefix%", getConfig().getString("messages.prefix", "&b&lPREGEN &8» &7")));
    }

    private static void send(CommandSender to, String message) {
        if (message != null && !message.isEmpty()) to.sendMessage(message);
    }

    // ------------------------------------------------------------------ main loop

    private void tick() {
        serverTick++;
        measureTps();

        if (serverTick % 40 == 0) {
            // Every 2 s (= the TPS window). Throttle: multiplicative decrease below min-tps, additive increase above.
            if (tps < minTps) throttle = Math.max(0.05, throttle * 0.5);
            else if (tps >= Math.min(19.5, minTps + 1.0)) throttle = Math.min(1.0, throttle + 0.1);
        }
        int limit = Math.max(1, (int) Math.floor(maxConcurrent * throttle));
        int syncEvery = Math.max(1, (int) Math.round(1.0 / throttle));

        List<PregenTask> done = new ArrayList<>();
        for (PregenTask t : tasks.values()) {
            if (t.paused || serverTick < t.notBefore) continue;
            World world = Bukkit.getWorld(t.worldName);
            if (world == null) continue; // waiting for a world plugin to load it
            t.tick(this, world, limit, syncEvery, budgetNanos, maxChecks, skipGenerated, respectBorder);
            if (logProgress) logDecile(t);
            if (t.finished()) done.add(t);
        }
        for (PregenTask t : done) finish(t);

        if (serverTick % 20 == 0) {
            for (PregenTask t : tasks.values()) {
                if (!t.paused) t.sample();
                updateBar(t);
            }
        }
        if (serverTick % (saveIntervalSeconds * 20L) == 0 && !tasks.isEmpty()) saveAsync();
    }

    private void measureTps() {
        long now = System.nanoTime();
        tickTimes[tickPos] = now;
        int oldest = (tickPos + 1) % tickTimes.length;
        tickPos = oldest;
        if (tickCount < tickTimes.length) {
            tickCount++;
            return;
        }
        double seconds = (now - tickTimes[oldest]) / 1e9;
        if (seconds > 0) tps = Math.min(20.0, (tickTimes.length - 1) / seconds);
    }

    private void logDecile(PregenTask t) {
        int decile = (int) (t.processed * 10 / Math.max(1, t.total));
        if (decile <= t.lastDecile || decile >= 10) return;
        t.lastDecile = decile;
        getLogger().info(String.format(Locale.ROOT, "%s: %d%% (%,d / %,d chunks, %.1f chunks/s, ETA %s)",
                t.worldName, decile * 10, t.processed, t.total, t.rate(), eta(t.etaSeconds())));
    }

    private void finish(PregenTask t) {
        tasks.remove(t.worldName);
        removeBar(t);
        getLogger().info(String.format(Locale.ROOT, "%s: done. %,d chunks generated, %,d already existed, %,d failed.",
                t.worldName, t.generated, t.skipped, t.failed));
        String m = msg("finished", "%prefix%&aPre-generation of &f%world% &afinished: &f%generated% &agenerated, &f%skipped% &askipped.")
                .replace("%world%", t.worldName)
                .replace("%generated%", String.format(Locale.ROOT, "%,d", t.generated))
                .replace("%skipped%", String.format(Locale.ROOT, "%,d", t.skipped));
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission(PERM)) send(p, m);
        saveAsync();
    }

    // ------------------------------------------------------------------ boss bar

    private void updateBar(PregenTask t) {
        if (t.bar == null) t.bar = Bukkit.createBossBar("", barColor, barStyle);
        String template = t.paused ? barPausedTitle : barTitle;
        if (!t.paused && Bukkit.getWorld(t.worldName) == null) {
            template = getConfig().getString("boss-bar.waiting-title", "&7Pregen &f%world% &8| &7waiting for the world to load");
        }
        t.bar.setTitle(placeholders(Text.colorize(template), t));
        t.bar.setProgress(Math.max(0.0, Math.min(1.0, t.percent() / 100.0)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean want = p.hasPermission(PERM) && !hiddenBars.contains(p.getUniqueId());
            boolean has = t.bar.getPlayers().contains(p);
            if (want && !has) t.bar.addPlayer(p);
            else if (!want && has) t.bar.removePlayer(p);
        }
    }

    private void removeBar(PregenTask t) {
        if (t.bar != null) t.bar.removeAll();
        t.bar = null;
    }

    private String placeholders(String s, PregenTask t) {
        return s.replace("%world%", t.worldName)
                .replace("%percent%", String.format(Locale.ROOT, "%.1f", t.percent()))
                .replace("%rate%", String.format(Locale.ROOT, "%.0f", t.rate()))
                .replace("%eta%", eta(t.etaSeconds()))
                .replace("%processed%", String.format(Locale.ROOT, "%,d", t.processed))
                .replace("%total%", String.format(Locale.ROOT, "%,d", t.total))
                .replace("%generated%", String.format(Locale.ROOT, "%,d", t.generated))
                .replace("%skipped%", String.format(Locale.ROOT, "%,d", t.skipped));
    }

    static String eta(long seconds) {
        if (seconds < 0) return "--";
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        for (PregenTask t : tasks.values()) updateBar(t);
    }

    // ------------------------------------------------------------------ persistence

    private String serialize() {
        YamlConfiguration yml = new YamlConfiguration();
        ConfigurationSection root = yml.createSection("tasks");
        for (PregenTask t : tasks.values()) t.save(root.createSection(t.worldName));
        return yml.saveToString();
    }

    private void saveAsync() {
        String data = serialize();
        if (io == null || io.isShutdown()) {
            writeNow(data);
            return;
        }
        io.execute(() -> writeNow(data));
    }

    private synchronized void writeNow(String data) {
        try {
            getDataFolder().mkdirs();
            Path target = progressFile.toPath();
            Path tmp = target.resolveSibling("progress.yml.tmp");
            Files.writeString(tmp, data, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            getLogger().warning("Could not save progress.yml: " + e.getMessage());
        }
    }

    private void loadProgress() {
        if (!progressFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(progressFile);
        ConfigurationSection root = yml.getConfigurationSection("tasks");
        if (root == null) return;
        boolean autoResume = getConfig().getBoolean("generation.auto-resume", true);
        for (String world : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(world);
            if (sec == null) continue;
            PregenTask t = PregenTask.load(world, sec);
            if (!autoResume) t.paused = true;
            t.notBefore = resumeDelaySeconds * 20L;
            tasks.put(world, t);
            getLogger().info(String.format(Locale.ROOT, "Restored task for %s at %.1f%%%s.", world, t.percent(),
                    t.paused ? " (paused)" : ", resuming in " + resumeDelaySeconds + "s"));
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission(PERM)) {
            send(sender, msg("no-permission", "%prefix%&cYou do not have permission."));
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start" -> start(sender, label, args);
            case "pause", "resume", "cancel" -> {
                PregenTask t = pick(sender, args);
                if (t == null) return true;
                if (sub.equals("pause")) {
                    t.paused = true;
                    send(sender, msg("paused", "%prefix%Paused &f%world%&7.").replace("%world%", t.worldName));
                } else if (sub.equals("resume")) {
                    t.paused = false;
                    t.notBefore = 0;
                    t.resetSamples();
                    send(sender, msg("resumed", "%prefix%Resumed &f%world%&7.").replace("%world%", t.worldName));
                } else {
                    tasks.remove(t.worldName);
                    t.inFlight.clear();
                    removeBar(t);
                    send(sender, msg("cancelled", "%prefix%Cancelled &f%world%&7. Chunks generated so far stay.")
                            .replace("%world%", t.worldName));
                }
                updateBarSafe(t);
                saveAsync();
            }
            case "status" -> status(sender, args);
            case "bar" -> {
                if (!(sender instanceof Player p)) {
                    send(sender, msg("players-only", "%prefix%&cOnly players can toggle the boss bar."));
                    return true;
                }
                boolean hide = hiddenBars.add(p.getUniqueId());
                if (!hide) hiddenBars.remove(p.getUniqueId());
                for (PregenTask t : tasks.values()) updateBar(t);
                send(sender, hide ? msg("bar-hidden", "%prefix%Boss bar hidden.") : msg("bar-shown", "%prefix%Boss bar shown."));
            }
            case "reload" -> {
                loadSettings();
                for (PregenTask t : tasks.values()) {
                    removeBar(t);
                    updateBar(t);
                }
                send(sender, msg("reloaded", "%prefix%&aConfig reloaded."));
            }
            default -> usage(sender, label);
        }
        return true;
    }

    private void updateBarSafe(PregenTask t) {
        if (tasks.containsKey(t.worldName)) updateBar(t);
    }

    private void usage(CommandSender sender, String label) {
        send(sender, msg("usage", "%prefix%Usage: /%label% start <world> <radius> [centerX centerZ] [square|circle] &8| &7pause|resume|cancel|status [world] &8| &7bar &8| &7reload")
                .replace("%label%", label));
    }

    private void start(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            usage(sender, label);
            return;
        }
        World world = Bukkit.getWorld(args[1]);
        if (world == null) {
            send(sender, msg("unknown-world", "%prefix%&cUnknown world &f%world%&c.").replace("%world%", args[1]));
            return;
        }
        if (tasks.containsKey(world.getName())) {
            send(sender, msg("already-running", "%prefix%&cA task for &f%world% &calready exists. Cancel it first.")
                    .replace("%world%", world.getName()));
            return;
        }
        long radius = Numbers.parse(args[2]);
        int maxRadius = getConfig().getInt("generation.max-radius", 100_000);
        if (radius <= 0 || radius > maxRadius) {
            send(sender, msg("bad-radius", "%prefix%&cRadius must be between 1 and %max% blocks.")
                    .replace("%max%", String.format(Locale.ROOT, "%,d", maxRadius)));
            return;
        }
        int cx;
        int cz;
        int next = 3;
        if (args.length >= 5) {
            long x = Numbers.parseSigned(args[3]);
            long z = Numbers.parseSigned(args[4]);
            if (x == Long.MIN_VALUE || z == Long.MIN_VALUE || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) {
                send(sender, msg("bad-center", "%prefix%&cInvalid center coordinates."));
                return;
            }
            cx = (int) x;
            cz = (int) z;
            next = 5;
        } else {
            WorldBorder border = world.getWorldBorder();
            cx = border.getCenter().getBlockX();
            cz = border.getCenter().getBlockZ();
        }
        PregenTask.Shape shape = defaultShape;
        if (args.length > next) {
            try {
                shape = PregenTask.Shape.valueOf(args[next].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // keep default
            }
        }
        PregenTask t = new PregenTask(world.getName(), cx >> 4, cz >> 4, (int) radius, shape);
        tasks.put(t.worldName, t);
        updateBar(t);
        saveAsync();
        send(sender, msg("started", "%prefix%Started &f%world% &7radius &f%radius% &7around &f%x%, %z% &7(&f%total% &7chunks, %mode%).")
                .replace("%world%", t.worldName)
                .replace("%radius%", String.format(Locale.ROOT, "%,d", radius))
                .replace("%x%", String.valueOf(cx)).replace("%z%", String.valueOf(cz))
                .replace("%total%", String.format(Locale.ROOT, "%,d", t.total))
                .replace("%mode%", PaperCompat.hasAsyncChunks() ? "async" : "sync, 1 chunk/tick"));
        getLogger().info(String.format(Locale.ROOT, "Started %s: radius %d around %d, %d (%,d chunks, %s).",
                t.worldName, radius, cx, cz, t.total, shape.name().toLowerCase(Locale.ROOT)));
    }

    /** The task named in args[1], or the only one when there is exactly one. */
    private PregenTask pick(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            PregenTask t = tasks.get(args[1]);
            if (t == null) {
                for (PregenTask o : tasks.values()) if (o.worldName.equalsIgnoreCase(args[1])) t = o;
            }
            if (t == null) send(sender, msg("no-task", "%prefix%&cNo task for &f%world%&c.").replace("%world%", args[1]));
            return t;
        }
        if (tasks.size() == 1) return tasks.values().iterator().next();
        send(sender, tasks.isEmpty() ? msg("no-tasks", "%prefix%No pre-generation is running.")
                : msg("which-world", "%prefix%&cSeveral tasks are running; name the world."));
        return null;
    }

    private void status(CommandSender sender, String[] args) {
        if (tasks.isEmpty()) {
            send(sender, msg("no-tasks", "%prefix%No pre-generation is running."));
            return;
        }
        List<PregenTask> list = new ArrayList<>();
        if (args.length >= 2) {
            PregenTask t = pick(sender, args);
            if (t == null) return;
            list.add(t);
        } else {
            list.addAll(tasks.values());
        }
        for (PregenTask t : list) {
            String state = t.paused ? "paused" : Bukkit.getWorld(t.worldName) == null ? "waiting for world" : "running";
            send(sender, placeholders(msg("status",
                    "%prefix%&f%world% &8- &f%percent%% &7(%processed%/%total%) &8| &f%rate% &7chunks/s &8| &7ETA &f%eta% &8| &7%state%"), t)
                    .replace("%state%", state));
            send(sender, placeholders(msg("status-detail",
                    "&7  generated &f%generated%&7, skipped &f%skipped%&7, in flight &f%inflight%&7, limit &f%limit%&7, TPS &f%tps% &7(%mode%)"), t)
                    .replace("%inflight%", String.valueOf(t.inFlight.size()))
                    .replace("%limit%", String.valueOf(Math.max(1, (int) Math.floor(maxConcurrent * throttle))))
                    .replace("%tps%", String.format(Locale.ROOT, "%.1f", tps))
                    .replace("%mode%", PaperCompat.hasAsyncChunks() ? "async" : "sync"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission(PERM)) return out;
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(List.of("start", "pause", "resume", "cancel", "status", "bar", "reload"));
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("start")) {
                for (World w : Bukkit.getWorlds()) options.add(w.getName());
            } else if (List.of("pause", "resume", "cancel", "status").contains(sub)) {
                options.addAll(tasks.keySet());
            }
        } else if (args[0].equalsIgnoreCase("start")) {
            if (args.length == 3) options.addAll(List.of("1000", "5000", "10k"));
            else if ((args.length == 4 || args.length == 5) && sender instanceof Player p) {
                options.add(String.valueOf(args.length == 4 ? p.getLocation().getBlockX() : p.getLocation().getBlockZ()));
            } else if (args.length == 6 || args.length == 4) {
                options.addAll(List.of("square", "circle"));
            }
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(last)) out.add(o);
        return out;
    }
}
