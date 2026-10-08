package dev.wareworks.registry;

import java.util.List;
import java.util.function.Consumer;

import dev.wareworks.content.station.WarehouseInputBlockEntity;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import dev.wareworks.content.storage.PalletEntity;
import dev.wareworks.content.storage.RackBayBlockEntity;
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
 * all for every side, and a rack bay its own one-slot, one-type handler — the whole of its contents, because a bay
 * <b>is</b> the storage location (§3.8, M28). The views are final fields, so NeoForge's automatic
 * invalidation (placement, removal, chunk load/unload) is enough; a station only invalidates itself when a load changed
 * its slot count. The warehouse interface, controller and crane expose no item capability — an interface deliberately
 * exposes nothing, because the inventory it reads answers for itself. Create mechanical arms reach
 * the stations through these same views, via the interaction point types in {@link WareworksArmInteractionPoints}.
 * <p>
 * <b>The rack bay is the one entry here that is a storage location</b> (M28, §3.8). Everything else registered above is
 * a station or a buffer in front of the warehouse; a bay <i>is</i> a rack position, so its registrar is what makes a
 * storage location fillable by a funnel, a chute, a hopper or a belt without the input station and
 * without the crane. Nothing teleports — a machine makes one real {@code IItemHandler} call at the block in front of it
 * — and the controller is only told, in the same tick, that the contents changed. Callers that do not use the
 * capability at all (belts, belt tunnels, weighted ejectors) go through the bay's {@code DirectBeltInputBehaviour}
 * instead, as they do at the warehouse input. A Create <b>mechanical arm</b> is the one caller this capability does
 * <i>not</i> buy: an arm reaches only blocks a registered {@link WareworksArmInteractionPoints arm interaction point}
 * accepts, and the bay has none.
 * <p>
 * <b>The fluid bay is the one entry that is not an item capability, and the one that is sided</b> (M30, issue #21,
 * §3.9). It registers {@code Capabilities.FluidHandler.BLOCK} and <b>no</b> {@code ItemHandler.BLOCK} at all: a bay
 * that took a lava bucket would have to hand back an empty bucket as the insert remainder, and a funnel does not read
 * a remainder of a <i>different</i> item — it would take the lava and destroy the bucket. So M28's headline above
 * does <b>not</b> transfer to fluids: a funnel, a chute, a belt and a hopper cannot fill a fluid bay, and the
 * compensation is the pipe at the back, which is the fluid analogue and strictly better for bulk. It is also the
 * first registrar here that answers <b>per face</b> — every entry above answers on all sides: the aisle face carries
 * no fluid connection, so a pipe is never in the crane's lane, while the lateral, top, bottom and back faces do (and
 * a {@code null} query does too, or a fluid census could not see the bay's contents at all). Whether a pipe may also
 * <b>draw off</b> is one server config key, {@code storage.fluidBayPipeExtraction}; the bay's own operations go
 * through its ungated handler, which is Create's own {@code forceFill} pattern.
 * <p>
 * <b>The pallet is the one entry that is not a block at all</b> (M28, §3.8): the load of a broken rack bay, carried by
 * {@code PalletEntity}. It registers the <i>entity</i> item capabilities — {@code ItemHandler.ENTITY} and
 * {@code ENTITY_AUTOMATION} — over one slot that is <b>extract-only</b>, because the only way goods may get <i>into</i>
 * a warehouse's storage is a bay, and a pallet a machine could fill would be the portable container the owner refused.
 * A vanilla hopper under a pallet drains it and a Create Deployer can take from it; Create's own belts, chutes and
 * funnels cannot see it, which also means no Create logistics block can delete or teleport one.
 */
public final class WareworksCapabilities {
    private static final List<Consumer<RegisterCapabilitiesEvent>> REGISTRARS = List.of(
            WarehouseInputBlockEntity::registerCapabilities,
            WarehouseOutputBlockEntity::registerCapabilities,
            WarehouseTerminalBlockEntity::registerCapabilities,
            WarehouseProductionBlockEntity::registerCapabilities,
            RackBayBlockEntity::registerCapabilities,
            FluidBayBlockEntity::registerCapabilities,
            PalletEntity::registerCapabilities);

    private WareworksCapabilities() {
    }

    /** Mod-bus listener, added in the {@code Wareworks} constructor. */
    public static void register(RegisterCapabilitiesEvent event) {
        for (Consumer<RegisterCapabilitiesEvent> registrar : REGISTRARS)
            registrar.accept(event);
    }
}
