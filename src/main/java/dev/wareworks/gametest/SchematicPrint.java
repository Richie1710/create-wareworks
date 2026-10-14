package dev.wareworks.gametest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.schematics.SchematicExport;
import com.simibubi.create.content.schematics.SchematicItem;
import com.simibubi.create.content.schematics.SchematicPrinter;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.simibubi.create.foundation.utility.BlockHelper;
import com.simibubi.create.foundation.utility.CreatePaths;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A block's way through a Create schematic, taken with Create's own code from end to end (issue #25): what a schematic
 * file holds, what a print hands the printed block, and what Create's material list asks for.
 * <p>
 * <b>Saving</b> is {@code SchematicExport#saveSchematic} on the <b>server</b> level, which is exactly Schematic and
 * Quill's "save and upload instantly" ({@code ServerSchematicLoader#handleInstantSchematic}): vanilla
 * {@code StructureTemplate#fillFromWorld} with entities, storing every block entity's <b>full save</b>. That is the worst
 * case on purpose — a save on the client level holds only what clients are sent — so a test that starts here starts
 * from a file that really does hold the goods. The file goes where the server keeps uploaded schematics, which is the
 * copy the Schematicannon and a server-side checker read.
 * <p>
 * <b>Printing</b> is {@link SchematicPrinter} loaded from a deployed blueprint with block data processed — the
 * Schematicannon's setting, and the creative instant print's for every player who may not use operator blocks — and
 * then, for every position, exactly what the creative instant print ({@code SchematicPlacePacket}) does:
 * {@link BlockHelper#prepareBlockEntityData} on the block entity the printer loaded from the file, and
 * {@link BlockHelper#placeSchematicBlock} with it; every entity the printer hands out is added to the level. The
 * Schematicannon, the creative print and a deployer printing all build a block's data with that same call. A print
 * with {@code overStanding} also prints where the same block already stands: a real printer gets there only when a
 * block state property differs (a dock turned another way, a terminal's screen on another side) and, at the cannon,
 * with <i>Replace Block Entities</i> on — the load into the standing block entity is the same either way, and that load
 * is what such a print tests.
 */
final class SchematicPrint {
    /** The owner folder the test schematics are uploaded to (shared with {@link SchematicannonGameTests}). */
    static final String OWNER = "wareworks_gametest";

    private SchematicPrint() {
    }

    /**
     * One block of a print.
     *
     * @param target      absolute position it was printed at
     * @param state       the state printed
     * @param file        the block's {@code nbt} entry in the schematic file, or null when the file has none
     * @param data        what Create's printer handed the printed block entity (a copy, taken before
     *                    {@code placeSchematicBlock} adds the position), or null when it hands it nothing
     * @param requirement what Create's material list asks for this block, read off the block entity loaded from the file
     */
    record Printed(BlockPos target, BlockState state, @Nullable CompoundTag file, @Nullable CompoundTag data,
                 ItemRequirement requirement) {
    }

    /**
     * @param blocks   every non-air block printed, in the printer's order
     * @param entities every entity the printer handed out (and that was added to the level)
     */
    record Result(List<Printed> blocks, List<Entity> entities) {
        /** The printed block at the absolute position {@code target}; fails the test when there is none. */
        Printed at(GameTestHelper helper, BlockPos target) {
            for (Printed block : blocks)
                if (block.target().equals(target))
                    return block;
            helper.fail("nothing was printed at " + target + "; printed " + blocks.stream().map(Printed::target).toList());
            throw new IllegalStateException("unreachable");
        }
    }

    /**
     * Saves the test-relative box spanned by {@code first} and {@code second} as Schematic and Quill's instant upload
     * does, and answers the file name. {@code name} must be unique among all tests: the file is overwritten by the next
     * run instead of piling up, and it is written, loaded and printed within one tick, so tests running side by side in
     * one batch never share one.
     */
    static String save(GameTestHelper helper, String name, BlockPos first, BlockPos second) {
        String file = "safety_" + name + ".nbt";
        SchematicExport.SchematicExportResult result = SchematicExport.saveSchematic(dir(), file, true,
                helper.getLevel(), helper.absolutePos(first), helper.absolutePos(second));
        if (result == null)
            helper.fail("Create could not save the schematic " + file);
        return file;
    }

    /**
     * The raw contents of a saved schematic file, read back from disk: the very bytes a server-side checker such as
     * Create: Schematic Checker inspects.
     */
    static CompoundTag fileContents(GameTestHelper helper, String file) {
        try {
            return NbtIo.readCompressed(dir().resolve(file), NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            helper.fail("cannot read the schematic " + file + ": " + e);
            throw new IllegalStateException("unreachable", e);
        }
    }

    /**
     * Writes a copy of the schematic {@code file} as {@code "safety_" + name + ".nbt"}, with {@code edit} applied to
     * every block entry's {@code nbt} compound first, and answers the new file name. It makes the files the server-side
     * save cannot: above all the one an ordinary Schematic and Quill save writes, which is taken on the <b>client</b>
     * level and so holds only what clients are sent ({@code SchematicAndQuillHandler#saveSchematic}). {@code name}
     * follows the rule of {@link #save}.
     */
    static String rewrite(GameTestHelper helper, String file, String name, Consumer<CompoundTag> edit) {
        CompoundTag contents = fileContents(helper, file);
        ListTag blocks = contents.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag entry = blocks.getCompound(i);
            if (entry.contains("nbt", Tag.TAG_COMPOUND))
                edit.accept(entry.getCompound("nbt"));
        }
        String rewritten = "safety_" + name + ".nbt";
        try {
            NbtIo.writeCompressed(contents, dir().resolve(rewritten));
        } catch (IOException e) {
            helper.fail("cannot write the schematic " + rewritten + ": " + e);
        }
        return rewritten;
    }

    /**
     * Prints the schematic {@code file} with its lowest corner at the test-relative {@code anchor} (see the class
     * comment). Positions whose state already matches are skipped like Create skips them, unless {@code overStanding}.
     */
    static Result print(GameTestHelper helper, String file, BlockPos anchor, boolean overStanding) {
        ServerLevel level = helper.getLevel();
        BlockPos anchorAbsolute = helper.absolutePos(anchor);
        ItemStack blueprint = SchematicItem.create(level, file, OWNER);
        blueprint.set(AllDataComponents.SCHEMATIC_DEPLOYED, true);
        blueprint.set(AllDataComponents.SCHEMATIC_ANCHOR, anchorAbsolute);

        Map<BlockPos, CompoundTag> entries = fileEntries(fileContents(helper, file));
        SchematicPrinter printer = new SchematicPrinter();
        printer.loadSchematic(blueprint, level, true);
        if (!printer.isLoaded() || printer.isErrored())
            helper.fail("Create's printer could not load the schematic " + file);

        List<Printed> blocks = new ArrayList<>();
        List<Entity> entities = new ArrayList<>();
        while (printer.advanceCurrentPos()) {
            if (!overStanding && !printer.shouldPlaceCurrent(level))
                continue;
            printer.handleCurrentTarget((target, state, blockEntity) -> {
                if (state.isAir())
                    return; // the creative print leaves air alone unless a server config says otherwise
                CompoundTag data = BlockHelper.prepareBlockEntityData(level, state, blockEntity);
                blocks.add(new Printed(target.immutable(), state, entries.get(target.subtract(anchorAbsolute)),
                        data == null ? null : data.copy(), ItemRequirement.of(state, blockEntity)));
                BlockHelper.placeSchematicBlock(level, state, target, null, data);
            }, (target, entity) -> {
                entities.add(entity);
                level.addFreshEntity(entity);
            });
        }
        return new Result(blocks, entities);
    }

    /** Every block entry of a schematic file that carries an {@code nbt} compound, by its position in the file. */
    private static Map<BlockPos, CompoundTag> fileEntries(CompoundTag contents) {
        Map<BlockPos, CompoundTag> entries = new HashMap<>();
        ListTag blocks = contents.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag entry = blocks.getCompound(i);
            if (!entry.contains("nbt", Tag.TAG_COMPOUND))
                continue;
            ListTag pos = entry.getList("pos", Tag.TAG_INT);
            entries.put(new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2)), entry.getCompound("nbt"));
        }
        return entries;
    }

    /** Every entity entry of a schematic file. */
    static List<CompoundTag> fileEntities(CompoundTag contents) {
        List<CompoundTag> entities = new ArrayList<>();
        ListTag list = contents.getList("entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
            entities.add(list.getCompound(i).getCompound("nbt"));
        return entities;
    }

    private static Path dir() {
        return CreatePaths.UPLOADED_SCHEMATICS_DIR.resolve(OWNER);
    }
}
