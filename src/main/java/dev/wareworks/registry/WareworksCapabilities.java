package dev.wareworks.registry;

import java.util.List;
import java.util.function.Consumer;

import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Capability providers of Wareworks block entities.
 * <p>
 * Pattern (as in Create's {@code CommonEvents}): every block entity that exposes a capability has a
 * {@code public static void registerCapabilities(RegisterCapabilitiesEvent event)} which calls
 * {@code event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, WareworksBlockEntityTypes.X.get(), (be, side) -> ...)}
 * and returns a stable handler instance. That method reference is added to {@link #REGISTRARS}. The event fires after
 * registration, so {@code .get()} on entries is safe there.
 * <p>
 * Registered ({@code docs/warehouse-system.md} §3.2, §3.4, §3.5): the warehouse input exposes an insert-only view of
 * its buffer, the warehouse output, the warehouse terminal and the warehouse production station an extract-only view,
 * all for every side. The views are final fields, so NeoForge's automatic
 * invalidation (placement, removal, chunk load/unload) is enough; a station only invalidates itself when a load changed
 * its slot count. The warehouse interface, controller and crane expose no item capability.
 */
public final class WareworksCapabilities {
    private static final List<Consumer<RegisterCapabilitiesEvent>> REGISTRARS = List.of(
            WarehouseInputBlockEntity::registerCapabilities,
            WarehouseOutputBlockEntity::registerCapabilities,
            WarehouseTerminalBlockEntity::registerCapabilities,
            WarehouseProductionBlockEntity::registerCapabilities);

    private WareworksCapabilities() {
    }

    /** Mod-bus listener, added in the {@code Wareworks} constructor. */
    public static void register(RegisterCapabilitiesEvent event) {
        for (Consumer<RegisterCapabilitiesEvent> registrar : REGISTRARS)
            registrar.accept(event);
    }
}
