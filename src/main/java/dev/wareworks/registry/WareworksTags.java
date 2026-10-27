package dev.wareworks.registry;

import com.simibubi.create.AllTags;
import com.tterrag.registrate.builders.BlockBuilder;
import com.tterrag.registrate.providers.RegistrateTagsProvider;
import com.tterrag.registrate.util.nullness.NonNullFunction;

import dev.wareworks.Wareworks;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
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
    /**
     * {@code wareworks:hand_truck_carriable}: what a hand truck may pick up (issue #37, ADR-070). Every chest and
     * barrel the loader's common tags know ({@code #c:chests}, {@code #c:barrels}); a modpack adds more here. The
     * server also requires the block entity to be a plain vanilla {@code Container} ({@code HandTruckRules}).
     */
    public static final TagKey<Block> HAND_TRUCK_CARRIABLE =
            TagKey.create(Registries.BLOCK, Wareworks.asResource("hand_truck_carriable"));
    /**
     * {@code wareworks:hand_truck_denied}: never carried, even when also carriable. The ender chest (it holds nothing
     * of its own), shulker boxes (already portable) and everything a block mover may not move, which includes every
     * Wareworks block with a block entity (all but the rail) through {@link #relocationProtected()}; the rail is
     * neither carriable nor a container.
     */
    public static final TagKey<Block> HAND_TRUCK_DENIED =
            TagKey.create(Registries.BLOCK, Wareworks.asResource("hand_truck_denied"));

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

    /**
     * Writes the hand truck's two tags during {@code runData}. Added from {@code WareworksDatagen} before Registrate
     * builds its root provider.
     */
    public static void generateHandTruckTags(RegistrateTagsProvider<Block> provider) {
        provider.addTag(HAND_TRUCK_CARRIABLE).addTag(Tags.Blocks.CHESTS).addTag(Tags.Blocks.BARRELS);
        provider.addTag(HAND_TRUCK_DENIED).addTag(Tags.Blocks.CHESTS_ENDER).addTag(BlockTags.SHULKER_BOXES)
                .addTag(RELOCATION_NOT_SUPPORTED).addTag(NON_MOVABLE);
    }
}
