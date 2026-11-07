package dev.wareworks.registry;

import dev.wareworks.Wareworks;
import dev.wareworks.content.handtruck.HandTruckLoad;
import dev.wareworks.content.remote.RemoteLink;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The mod's item data component types.
 * <p>
 * Two of them: what a loaded hand truck carries ({@link HandTruckLoad}, issue #37, ADR-070) and the terminal a
 * Warehouse Remote is linked to ({@link RemoteLink}, issue #44, ADR-077). Both are persistent (saved with the stack)
 * and synced to clients <b>in full</b>, because a creative client sends whole stacks back to the server and a client
 * copy without the data would empty the truck or break the link. Registered on the mod bus from the
 * {@code Wareworks} constructor, before the items that use them.
 */
public final class WareworksDataComponents {
    private static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Wareworks.ID);

    /** The container a hand truck carries: block state, block entity type and the block entity's save data. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<HandTruckLoad>> HAND_TRUCK_LOAD =
            COMPONENTS.registerComponentType("hand_truck_load", builder -> builder
                    .persistent(HandTruckLoad.CODEC).networkSynchronized(HandTruckLoad.STREAM_CODEC));

    /** The terminal a Warehouse Remote opens: its position, dimension and identity. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<RemoteLink>> REMOTE_LINK =
            COMPONENTS.registerComponentType("remote_link", builder -> builder
                    .persistent(RemoteLink.CODEC).networkSynchronized(RemoteLink.STREAM_CODEC));

    private WareworksDataComponents() {
    }

    /** Mod-bus registration, called from the {@code Wareworks} constructor. */
    public static void register(IEventBus modEventBus) {
        COMPONENTS.register(modEventBus);
    }
}
