package dev.wareworks.registry;

import com.simibubi.create.api.packager.unpacking.UnpackingHandler;

import dev.wareworks.Wareworks;
import dev.wareworks.content.station.WarehouseInputUnpackingHandler;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Create unpacking handlers ({@code docs/warehouse-system.md} §3.2.5, M26, issue #18): what happens when a Create
 * Packager takes a package apart into a Wareworks block.
 * <p>
 * Exactly one entry, and it adds no behaviour — {@link WarehouseInputUnpackingHandler} delegates verbatim to
 * {@link UnpackingHandler#DEFAULT} so that the refused half of the handover can be read on the input's goggle tooltip.
 * <p>
 * <b>Why common setup and not the mod constructor.</b> {@code UnpackingHandler.REGISTRY} is keyed by the {@code Block}
 * <b>object</b>, so the registration needs {@code WareworksBlocks.WAREHOUSE_INPUT.get()}, and a Registrate entry is not
 * bound to its value until the registry event has run — long after every mod constructor. Create registers its own
 * three handlers from exactly here, for exactly this reason ({@code Create#init}, an {@code FMLCommonSetupEvent}
 * listener that defers them with {@code enqueueWork}: "These registrations use Create's registered objects directly so
 * they must run after registration has finished"). {@code SimpleRegistry} is thread-safe during parallel mod init and
 * {@code register} throws {@code IllegalArgumentException} if the key already has a value, so this must happen exactly
 * once; a single listener on the mod bus is that guarantee.
 */
public final class WareworksUnpackingHandlers {
    private WareworksUnpackingHandlers() {
    }

    /** Mod bus: queues the registration for common setup, where the blocks exist. */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(WareworksUnpackingHandlers::onCommonSetup);
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(WareworksUnpackingHandlers::registerHandlers);
    }

    private static void registerHandlers() {
        UnpackingHandler.REGISTRY.register(WareworksBlocks.WAREHOUSE_INPUT.get(),
                WarehouseInputUnpackingHandler.INSTANCE);
        Wareworks.LOGGER.debug("Registered the warehouse input's unpacking handler");
    }
}
