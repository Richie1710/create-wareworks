package dev.wareworks.registry;

import com.simibubi.create.AllTags;
import com.tterrag.registrate.builders.BlockBuilder;
import com.tterrag.registrate.util.nullness.NonNullFunction;

import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.Tags;

/**
 * Tags used by Wareworks and Registrate transforms that apply them. Registrate writes the tag files during
 * {@code runData}.
 */
public final class WareworksTags {
    /** {@code create:non_movable}: Create contraptions refuse to move the block. */
    public static final TagKey<Block> NON_MOVABLE = AllTags.AllBlockTags.NON_MOVABLE.tag;
    /** {@code c:relocation_not_supported}: loader-wide opt-out for block movers (honoured by Create too). */
    public static final TagKey<Block> RELOCATION_NOT_SUPPORTED = Tags.Blocks.RELOCATION_NOT_SUPPORTED;

    private WareworksTags() {
    }

    /**
     * Protects a warehouse state block (crane dock, controller, interface, stations) from being moved by contraptions
     * or other movers, so warehouse state is never carried away (ADR-013).
     * Usage: {@code .transform(WareworksTags.relocationProtected())}.
     */
    public static <T extends Block, P> NonNullFunction<BlockBuilder<T, P>, BlockBuilder<T, P>> relocationProtected() {
        return builder -> builder.tag(NON_MOVABLE, RELOCATION_NOT_SUPPORTED);
    }
}
