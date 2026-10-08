package dev.wareworks.content.fluid;

import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * Fluids <b>by registry id</b>, for everything that leaves the server: block entity update tags, goggle data and
 * display rows. The fluid sibling of {@code content.item.ItemTypeSummaries} (M30, issue #21).
 * <p>
 * Goggle and content data is part of block entity update tags, which are also part of every chunk packet (2 MB client
 * NBT quota), so a fluid crosses the wire as its registry id and a millibucket count and <b>never</b> as a
 * {@link FluidKey} or a {@code FluidStack}: the id is bounded by its own length, while a stack's component patch is
 * not. A fluid bay's update tag is exactly that pair, which is what keeps its size independent of what is in it.
 * <p>
 * Writing and reading never throw. An unregistered fluid maps to the default id, and reading filters
 * {@link Fluids#EMPTY} back out, so a tag written by a server that has a mod the client does not reads as "no fluid"
 * rather than as the wrong one — the rule {@code ItemTypeSummaries} already applies to {@code minecraft:air}.
 * <p>
 * There is deliberately <b>no</b> summary-of-many-fluids tag here yet. {@code ItemTypeSummaries} carries one because
 * an inventory has slots and hundreds of item types; a fluid bay has neither — it holds one fluid and an amount — so
 * the only bounded thing to sync is the pair below. A controller-wide fluid digest arrives with the readouts that need
 * it, and inventing its tag format before then would be a format nobody has a reader for.
 */
public final class FluidTypeSummaries {
    private FluidTypeSummaries() {
    }

    /**
     * The registry id of {@code fluid} as a string, for bounded client sync and for value-based ordering
     * ({@link FluidKey#ORDER}) — never a {@code FluidStack} and never a fluid's identity hash.
     */
    public static String fluidId(Fluid fluid) {
        return BuiltInRegistries.FLUID.getKey(fluid).toString();
    }

    /**
     * The registered fluid with the id written by {@link #fluidId}; empty for invalid, unknown or
     * {@code minecraft:empty} ids.
     */
    public static Optional<Fluid> fluidById(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null)
            return Optional.empty();
        return BuiltInRegistries.FLUID.getOptional(location).filter(fluid -> fluid != Fluids.EMPTY);
    }
}
