package dev.wareworks.dev;

import dev.wareworks.config.WareworksConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server config overrides of the scenarios that need a value other than the default for their own world (dev tooling,
 * ADR-014): the {@code comb}, {@code chunks} and {@code robobee} scenarios.
 */
final class VisualConfig {
    private VisualConfig() {
    }

    /**
     * {@code ModConfigSpec.ConfigValue#set} plus {@code clearCache}: writes {@code newValue} into the loaded config in
     * memory only (never into the file), and the next {@code get} re-reads it, which the cached values need. Fails the
     * run when the server config is not loaded, because the override would then be a silent no-op.
     */
    static void setConfig(ModConfigSpec.IntValue value, int newValue) {
        if (!WareworksConfig.isServerConfigLoaded())
            throw new VisualTestException("the server config is not loaded, so overriding it would be a silent no-op");
        value.set(newValue);
        value.clearCache();
    }
}
