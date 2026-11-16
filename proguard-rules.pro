# ProGuard rules for PregenBar
# Goal: make the shipped jar hostile to decompile-and-edit while keeping every
# contract the Bukkit/Spigot server resolves by name or by reflection intact.
#
# What the server touches by name (must be preserved):
#   - the main class named in plugin.yml (main: net.byebye.pregenBar.PregenBar)
#   - its onEnable/onDisable/onLoad lifecycle methods (called reflectively by Bukkit)
#   - @EventHandler methods (Bukkit discovers them by ANNOTATION, so annotations
#     must survive, and the methods must not be shrunk away as "unused")
#   - overrides of library methods (onCommand, PlaceholderExpansion.*, ...) — kept
#     automatically because the APIs are supplied as -libraryjars
# Everything else (all our own class/method/field names, package structure, debug
# info) is fair game and gets destroyed.

# --- Debugging trade-off ---------------------------------------------------
# Do NOT optimize. ProGuard's optimizer can inline/merge in ways that trip up
# frameworks that dispatch by reflection (Bukkit's event system). Renaming +
# shrinking + debug-stripping is where the protection comes from, and it is safe.
-dontoptimize
# Preverification MUST stay on: Java 7+ class files require StackMapTable frames,
# and without them every method with a branch fails JVM verification
# (VerifyError: Expecting a stackmap frame). Do NOT add -dontpreverify.

# Keep ONLY these attributes. Everything not listed here — SourceFile,
# LineNumberTable, LocalVariableTable, LocalVariableTypeTable — is stripped, so a
# decompiler gets no original file names, no line numbers and no local var names.
-keepattributes *Annotation*,Signature,Exceptions,InnerClasses,EnclosingMethod

# On case-insensitive filesystems (Windows), mixed-case obfuscated names can
# collide when the jar is extracted. Avoid that.
-dontusemixedcaseclassnames

# Aggressive layout destruction: collapse every kept-open class into the root package.
# NOTE: -overloadaggressively is deliberately NOT used. It lets two members of one class
# share a name with different descriptors (e.g. two fields both named `a`), which is legal
# bytecode but crashes Paper's plugin remapper (neoforged AutoRenamingTool keys members by
# name alone -> "Duplicate key a"). Every Paper 1.20.5+ server remaps plugins on load, so
# that flag would make the jar unloadable.
-repackageclasses ''
-flattenpackagehierarchy ''
-allowaccessmodification

# Silence warnings about the provided (compileOnly) APIs and their transitive refs.
-dontwarn org.bukkit.**
-dontwarn net.md_5.**
-dontwarn org.yaml.snakeyaml.**
-dontwarn com.google.**
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**
-dontwarn net.kyori.**

# bStats is shaded and relocated into net.byebye.pregenBar.libs.bstats.
# It loads its own classes by name via Class.forName / reflection, so the
# entire relocated subtree must be kept with original names intact.
-keep class net.byebye.pregenBar.libs.bstats.** { *; }

# --- Keep rules ------------------------------------------------------------

# The plugin entry point: keep its fully-qualified name and the lifecycle methods
# Bukkit invokes reflectively. Its no-arg constructor is how Bukkit instantiates it.
-keep public class net.byebye.pregenBar.PregenBar {
    public <init>();
    public void onEnable();
    public void onDisable();
    public void onLoad();
}

# Event handlers are found by annotation, not by name — they may be renamed, but
# they must NOT be removed by the shrinker (nothing in our code calls them) and
# the @EventHandler annotation must be retained (covered by -keepattributes above).
-keepclassmembers class * implements org.bukkit.event.Listener {
    @org.bukkit.event.EventHandler <methods>;
}

# Standard enum contract: keep the synthetic values()/valueOf so any reflective
# enum access (ours or a library's) still resolves after renaming.
-keepclassmembers,allowshrinking enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
