package dev.wareworks.compat.computercraft;

import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Registers the Wareworks peripherals with CC: Tweaked (issue #9, ADR-074). Loaded only through
 * {@link ComputerCraftCompat#registerCapabilities}, i.e. only when CC: Tweaked is installed.
 * <p>
 * The route is the one Create 6 takes for its own peripherals: CC: Tweaked's block capability
 * ({@link PeripheralCapability#get()}) registered per block entity type. CC asks that capability first and falls back
 * to its generic peripherals (the {@code inventory} methods over {@code Capabilities.ItemHandler.BLOCK}) only when it
 * answers {@code null} (CC: Tweaked's {@code PlatformHelperImpl.PeripheralAccess#get}, checked on 1.116.1), so the
 * terminal is a {@code wareworks_terminal} and no longer a generic inventory. Every other Wareworks block keeps CC's
 * generic behaviour.
 * <p>
 * Create's own {@code ComputerBehaviour} is not used: it builds peripherals through a fixed list of Create's block
 * entities and throws for any other one, so a Wareworks block using it would crash as soon as CC is installed.
 */
public final class WareworksPeripherals {
    private WareworksPeripherals() {
    }

    static void register(RegisterCapabilitiesEvent event) {
        // A fresh peripheral per lookup, holding nothing but the terminal: TerminalPeripheral#equals compares the
        // terminal, so CC does not re-attach a computer when the lookup is repeated.
        event.registerBlockEntity(PeripheralCapability.get(), WareworksBlockEntityTypes.WAREHOUSE_TERMINAL.get(),
                (terminal, side) -> new TerminalPeripheral(terminal));
    }
}
