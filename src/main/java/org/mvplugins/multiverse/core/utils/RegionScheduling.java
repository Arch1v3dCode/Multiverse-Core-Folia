package org.mvplugins.multiverse.core.utils;

import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Schedules work on Paper's regionized schedulers, which Canvas and Folia require.
 * Falls back to the Bukkit scheduler when those schedulers are not implemented.
 */
public final class RegionScheduling {

    private static volatile Boolean canvas;

    private RegionScheduling() {
    }

    /**
     * @return True when the running server implements Canvas world unloading.
     */
    public static boolean isCanvas() {
        Boolean cached = canvas;
        if (cached != null) {
            return cached;
        }
        Server server = Bukkit.getServer();
        if (server == null) {
            return false;
        }
        boolean detected = declares(server.getClass(), "unloadWorldAsync", World.class, boolean.class, Consumer.class);
        canvas = detected;
        return detected;
    }

    /**
     * @return True when the current thread is the global region thread, or the primary server thread.
     */
    public static boolean isGlobalTickThread() {
        if (ReflectHelper.hasMethod(Bukkit.class, "isGlobalTickThread")) {
            try {
                return Bukkit.isGlobalTickThread();
            } catch (Throwable ignored) {
                return Bukkit.isPrimaryThread();
            }
        }
        return Bukkit.isPrimaryThread();
    }

    /**
     * Runs an action on the global region thread. Runs inline when already on that thread.
     *
     * @param action The action to run.
     */
    public static void runOnGlobal(@NotNull Runnable action) {
        if (isGlobalTickThread()) {
            action.run();
            return;
        }
        Plugin plugin = plugin();
        if (plugin == null) {
            action.run();
            return;
        }
        try {
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> action.run());
        } catch (Throwable ignored) {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    /**
     * Runs an action on the global region thread after a delay.
     *
     * @param action     The action to run.
     * @param delayTicks Delay in server ticks.
     * @return A callback that cancels the task.
     */
    public static @NotNull Runnable runDelayedOnGlobal(@NotNull Runnable action, long delayTicks) {
        Plugin plugin = plugin();
        if (plugin == null) {
            action.run();
            return () -> { };
        }
        try {
            var task = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduled -> action.run(), delayTicks);
            if (task == null) {
                return () -> { };
            }
            return task::cancel;
        } catch (Throwable ignored) {
            BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, action, delayTicks);
            return task::cancel;
        }
    }

    /**
     * Runs an action off the server thread.
     *
     * @param action The action to run.
     */
    public static void runAsync(@NotNull Runnable action) {
        Plugin plugin = plugin();
        if (plugin == null) {
            action.run();
            return;
        }
        try {
            Bukkit.getAsyncScheduler().runNow(plugin, task -> action.run());
        } catch (Throwable ignored) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, action);
        }
    }

    /**
     * Runs an action on the region that owns an entity.
     *
     * @param entity     The entity whose region should run the action.
     * @param action     The action to run.
     * @param delayTicks Delay in server ticks. Zero runs as soon as the region is available.
     */
    public static void runOnEntity(@NotNull Entity entity, @NotNull Runnable action, long delayTicks) {
        Plugin plugin = plugin();
        if (plugin == null) {
            action.run();
            return;
        }
        try {
            if (delayTicks <= 0) {
                entity.getScheduler().run(plugin, task -> action.run(), null);
            } else {
                entity.getScheduler().runDelayed(plugin, task -> action.run(), null, delayTicks);
            }
        } catch (Throwable ignored) {
            if (delayTicks <= 0) {
                Bukkit.getScheduler().runTask(plugin, action);
            } else {
                Bukkit.getScheduler().runTaskLater(plugin, action, delayTicks);
            }
        }
    }

    private static boolean declares(@NotNull Class<?> type, @NotNull String method, Class<?>... params) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                current.getDeclaredMethod(method, params);
                return true;
            } catch (NoSuchMethodException ignored) {
                // Inherited interface methods do not count; only a real server implementation does.
            }
        }
        return false;
    }

    private static @Nullable Plugin plugin() {
        return Bukkit.getPluginManager().getPlugin("Multiverse-Core");
    }
}
