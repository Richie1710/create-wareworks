package dev.wareworks.content.station;

import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;

/**
 * The bridge between a pattern's fluid cell, which names its fluid by a plain registry id ({@code core.production.FluidCell}),
 * and Minecraft's fluids (issue #55, ADR-089).
 * <p>
 * A cell names the <b>source</b> fluid and nothing else: no data components, so two potions are the same fluid there. A
 * flowing fluid is turned into its source. The id of a fluid this installation does not have (a removed mod) is kept in
 * the cell and resolves to nothing here, which a caller treats as "unknown", never as a crash.
 * <p>
 * Static helpers, usable on both sides.
 */
public final class PatternFluids {
    private PatternFluids() {
    }

    /** What a filled container holds, as a fluid cell would name it. */
    public record Contained(String fluid, int millibuckets) {
    }

    /**
     * The fluid in a filled container (a bucket, or any item with a fluid handler) and how much of it, or empty for an
     * empty container or an item without a fluid. Never consumes anything: it simulates on a copy of one item.
     */
    public static Optional<Contained> contained(ItemStack stack) {
        if (stack == null || stack.isEmpty())
            return Optional.empty();
        try {
            return FluidUtil.getFluidContained(stack).filter(found -> !found.isEmpty())
                    .map(found -> new Contained(idOf(found.getFluid()), found.getAmount()));
        } catch (RuntimeException e) {
            // A handler of another mod that throws on a simulated drain is simply no fluid container.
            return Optional.empty();
        }
    }

    /** The source form of a flowing fluid; any other fluid is its own source. */
    public static Fluid sourceOf(Fluid fluid) {
        return fluid instanceof FlowingFluid flowing ? flowing.getSource() : fluid;
    }

    /** The registry id of the source of {@code fluid}, as a fluid cell stores it. */
    public static String idOf(Fluid fluid) {
        return BuiltInRegistries.FLUID.getKey(sourceOf(fluid)).toString();
    }

    /**
     * The fluid a cell's id names, if this installation has it and it is a real fluid: not empty, and not a fluid that has
     * no source form. Empty for a malformed id, an unknown one and {@code minecraft:empty}.
     */
    public static Optional<Fluid> resolve(@Nullable String id) {
        if (id == null)
            return Optional.empty();
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null)
            return Optional.empty();
        return BuiltInRegistries.FLUID.getOptional(location)
                .filter(fluid -> fluid != Fluids.EMPTY && sourceOf(fluid) != Fluids.EMPTY)
                .map(PatternFluids::sourceOf);
    }

    /**
     * The id a server may write into a cell for what a client sent, or empty when it is no fluid this server has: the
     * source form's id, so a flowing fluid sent by a crafted payload is stored as its source.
     */
    public static Optional<String> validId(@Nullable String id) {
        return resolve(id).map(PatternFluids::idOf);
    }

    /** A stack of {@code millibuckets} of the cell's fluid for drawing, or empty for an unknown fluid. */
    public static Optional<FluidStack> stackOf(@Nullable String id, int millibuckets) {
        return resolve(id).map(fluid -> new FluidStack(fluid, Math.max(1, millibuckets)));
    }
}
