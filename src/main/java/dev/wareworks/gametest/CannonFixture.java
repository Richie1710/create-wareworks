package dev.wareworks.gametest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.schematics.SchematicItem;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.foundation.utility.CreatePaths;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * A real Create Schematicannon with a real schematic, for the GameTests that print with one
 * ({@link SchematicannonGameTests}, issue #23, and {@link SchematicannonRequesterGameTests}, issue #45).
 * <p>
 * The blocks of a {@link Site} are built first, then {@link #cannonFor} saves them into a structure file the way
 * Schematic and Quill saves one, writes it where the server keeps uploaded schematics, clears the site, and sets up a
 * running cannon with gunpowder, a deployed blueprint of the file and a chest beside it. From then on the cannon does
 * what it does in a world: it walks the schematic, takes what it needs from the chest, and pauses on "Missing Item"
 * when the chest runs dry.
 */
final class CannonFixture {
    private static final int CANNON_GUNPOWDER_SLOT = 4;

    private CannonFixture() {
    }

    /**
     * Where a test builds: the schematic's corner and size, and where the cannon and its chest stand. Both stand outside
     * the schematic and at least two blocks from every spot of it, which Create requires.
     */
    record Site(BlockPos min, Vec3i size, BlockPos cannon, BlockPos chest) {
    }

    /**
     * Saves the site into a schematic the way Schematic and Quill does, clears it, and sets up a running cannon with
     * gunpowder, a deployed blueprint of it and a chest beside it — holding one item of every block of the site when
     * {@code stocked}, nothing otherwise.
     */
    static SchematicannonBlockEntity cannonFor(GameTestHelper helper, Site site, boolean stocked) {
        return cannonFor(helper, site, stocked, () -> true);
    }

    /**
     * {@link #cannonFor(GameTestHelper, Site, boolean)}, pressing play for the player only while {@code playing} says
     * so: a cannon that was stopped, or whose schematic was loaded again, then waits paused on "ready" as in a world.
     */
    static SchematicannonBlockEntity cannonFor(GameTestHelper helper, Site site, boolean stocked,
            BooleanSupplier playing) {
        Map<BlockPos, Block> blocks = blocksOf(helper, site);

        BlockPos anchor = helper.absolutePos(site.min());
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(helper.getLevel(), anchor, site.size(), false, Blocks.STRUCTURE_VOID);
        Path dir = CreatePaths.UPLOADED_SCHEMATICS_DIR.resolve(SchematicPrint.OWNER);
        String file = "test_" + Long.toHexString(anchor.asLong()) + ".nbt";
        try {
            Files.createDirectories(dir);
            NbtIo.writeCompressed(template.save(new CompoundTag()), dir.resolve(file));
        } catch (IOException e) {
            throw new IllegalStateException("cannot write the test schematic to " + dir, e);
        }
        // Top layer first, so nothing is left standing on air while the rest is taken away; containers are emptied so
        // clearing them drops nothing.
        List<BlockPos> clearing = new ArrayList<>(blocks.keySet());
        clearing.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
        for (BlockPos pos : clearing) {
            if (blockEntityAt(helper, pos) instanceof Container container)
                container.clearContent();
            helper.setBlock(pos, Blocks.AIR);
        }

        ItemStack blueprint = SchematicItem.create(helper.getLevel(), file, SchematicPrint.OWNER);
        blueprint.set(AllDataComponents.SCHEMATIC_DEPLOYED, true);
        blueprint.set(AllDataComponents.SCHEMATIC_ANCHOR, anchor);

        helper.setBlock(site.chest(), Blocks.CHEST);
        if (stocked) {
            Container chest = (Container) helper.getBlockEntity(site.chest());
            int slot = 0;
            for (Block block : blocks.values())
                chest.setItem(slot++, new ItemStack(block.asItem()));
        }

        helper.setBlock(site.cannon(), AllBlocks.SCHEMATICANNON.getDefaultState());
        SchematicannonBlockEntity cannon = (SchematicannonBlockEntity) helper.getBlockEntity(site.cannon());
        cannon.inventory.setStackInSlot(0, blueprint);
        cannon.inventory.setStackInSlot(CANNON_GUNPOWDER_SLOT, new ItemStack(Items.GUNPOWDER, 64));
        cannon.state = SchematicannonBlockEntity.State.RUNNING;
        helper.onEachTick(() -> {
            // Loading the blueprint leaves the cannon paused and "ready", as the play button in its screen would.
            if (cannon.state == SchematicannonBlockEntity.State.PAUSED && "ready".equals(cannon.statusMsg)
                    && playing.getAsBoolean())
                cannon.state = SchematicannonBlockEntity.State.RUNNING;
        });
        return cannon;
    }

    /** The block entity at a test-relative position, or null; {@code GameTestHelper#getBlockEntity} fails instead. */
    @Nullable
    static BlockEntity blockEntityAt(GameTestHelper helper, BlockPos pos) {
        return helper.getLevel().getBlockEntity(helper.absolutePos(pos));
    }

    /** Every non-air block of the site, by test-relative position. */
    static Map<BlockPos, Block> blocksOf(GameTestHelper helper, Site site) {
        Map<BlockPos, Block> blocks = new LinkedHashMap<>();
        forEachSpot(site, pos -> {
            BlockState state = helper.getBlockState(pos);
            if (!state.isAir())
                blocks.put(pos, state.getBlock());
        });
        return blocks;
    }

    /** Every spot of the site, by test-relative position. */
    static void forEachSpot(Site site, Consumer<BlockPos> action) {
        for (int x = 0; x < site.size().getX(); x++)
            for (int y = 0; y < site.size().getY(); y++)
                for (int z = 0; z < site.size().getZ(); z++)
                    action.accept(site.min().offset(x, y, z));
    }
}
