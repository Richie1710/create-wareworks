package dev.wareworks.gametest;

import java.util.ArrayDeque;
import java.util.Deque;

import dev.wareworks.config.WareworksConfig;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Temporarily changes server config values inside a GameTest, so the config extremes of
 * {@code docs/warehouse-system.md} §9 can be tested without editing {@code wareworks-server.toml}.
 * <p>
 * {@link ModConfigSpec.ConfigValue#set} writes into the loaded config in memory only (no file write, no config events);
 * {@link ModConfigSpec.ConfigValue#clearCache()} makes the next {@code get()} re-read it, which is also needed for
 * {@code worldRestart} values, whose cache {@code set} does not update.
 * <p>
 * <b>Tests using this must run in their own batch</b> ({@link RobustnessGameTests#CONFIG_BATCH}): the tests of one batch
 * run at the same time and would see each other's overrides. Batches run one after another
 * ({@code GameTestRunner#runBatch}), and an {@code @AfterBatch} method restores every override, also when a test fails.
 */
final class ConfigOverrides {
    /** Restore actions in reverse order of application; touched only from the server thread. */
    private static final Deque<Runnable> RESTORES = new ArrayDeque<>();

    private ConfigOverrides() {
    }

    /**
     * Sets {@code value} to {@code override} until {@link #restoreAll()}. Fails the test when the server config is not
     * loaded, because a silent no-op would make the test assert the defaults.
     */
    static <T> void set(GameTestHelper helper, ModConfigSpec.ConfigValue<T> value, T override) {
        if (!WareworksConfig.isServerConfigLoaded()) {
            helper.fail("the server config must be loaded to override it");
            return;
        }
        T original = value.get();
        RESTORES.push(() -> apply(value, original));
        apply(value, override);
    }

    /** Restores every override, latest first. Safe to call when nothing was overridden. */
    static void restoreAll() {
        while (!RESTORES.isEmpty())
            RESTORES.pop().run();
    }

    private static <T> void apply(ModConfigSpec.ConfigValue<T> value, T newValue) {
        value.set(newValue);
        value.clearCache();
    }
}
