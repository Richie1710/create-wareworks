package dev.wareworks.content.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.PatternFluids;
import dev.wareworks.core.production.FluidSupply;
import dev.wareworks.core.stock.StockAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;

/**
 * What one warehouse controller can deliver of fluid to a production plan (issue #55, ADR-089): the production planner's
 * {@link FluidSupply} over the controller's own fluid accounting.
 * <p>
 * <b>One container per fluid: its bucket.</b> A fluid cell is delivered in the fluid's own bucket item, and only when
 * that bucket is <i>exactly</i> what an empty bucket fills to ({@code FillRecipes}: emptied and refilled on probes), so the
 * millibuckets one container holds are the measured content, never a constant. A fluid without a bucket, an unknown id
 * (a removed mod) and a fluid whose bucket does not refill cleanly all answer "cannot be delivered", which the planner
 * turns into a named refusal and never into a crash.
 * <p>
 * The fluid a plan may claim is {@link WarehouseControllerBlockEntity#availableFluidTo}: what the bays hold, less what
 * open fill requests and open fluid lines already owe ({@link WarehouseControllerBlockEntity#fillLedger()}), behind the
 * fluid keeper rule's reserve for the taker's {@code access}. Answers are remembered per fluid for this one plan, so the
 * planner's repeated walks see one consistent world and the ledger is built once per fluid.
 * <p>
 * Server thread only.
 */
final class ControllerFluidSupply implements FluidSupply<ItemKey> {
    private final WarehouseControllerBlockEntity controller;
    private final StockAccess access;
    private final Map<String, Optional<Delivery<ItemKey>>> known = new HashMap<>();

    ControllerFluidSupply(WarehouseControllerBlockEntity controller, StockAccess access) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.access = Objects.requireNonNull(access, "access");
    }

    @Override
    public Optional<Delivery<ItemKey>> delivery(String fluidId) {
        return known.computeIfAbsent(Objects.requireNonNull(fluidId, "fluidId"), this::measure);
    }

    @Override
    public int bufferSlots() {
        return WareworksConfig.productionBufferSlots();
    }

    @Override
    public int stackSize(ItemKey key) {
        return key.getMaxStackSize();
    }

    private Optional<Delivery<ItemKey>> measure(String fluidId) {
        Optional<Fluid> resolved = PatternFluids.resolve(fluidId);
        if (resolved.isEmpty())
            return Optional.empty();
        Fluid fluid = resolved.get();
        Item bucket = fluid.getBucket();
        if (bucket == Items.AIR)
            return Optional.empty();
        ItemKey filled = ItemKey.of(bucket);
        FluidKey fluidKey = FluidKey.of(fluid);
        return controller.fillRecipe(filled).filter(recipe -> recipe.fluid().equals(fluidKey))
                .map(recipe -> new Delivery<>(filled, recipe.emptyKey(), recipe.millibuckets(),
                        controller.availableFluidTo(access, fluidKey)));
    }
}
