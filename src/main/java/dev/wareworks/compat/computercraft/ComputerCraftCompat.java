package dev.wareworks.compat.computercraft;

import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * The entry into the optional CC: Tweaked integration (issue #9, ADR-074): the one class the rest of the mod calls, and
 * one that names no CC: Tweaked type.
 * <p>
 * CC: Tweaked's API is {@code compileOnly}, so a class that names one of its types fails to load in a game without it.
 * This class therefore reaches {@link WareworksPeripherals} — which does name them — only after
 * {@link #isLoaded()} answered yes; without CC: Tweaked the JVM never resolves a CC class, and the mod runs exactly as
 * before. Nothing here is client code, so a dedicated server loads it like any other common class.
 * {@code ComputerCraftBoundaryTest} keeps every CC type inside this package and out of this class.
 */
public final class ComputerCraftCompat {
    /** CC: Tweaked's mod id. */
    public static final String MOD_ID = "computercraft";

    private ComputerCraftCompat() {
    }

    /** Whether CC: Tweaked is installed. */
    public static boolean isLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    /** Mod-bus capability registration (listed in {@code WareworksCapabilities}); does nothing without CC: Tweaked. */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        if (isLoaded())
            WareworksPeripherals.register(event);
    }
}
