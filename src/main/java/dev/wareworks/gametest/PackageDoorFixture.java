package dev.wareworks.gametest;

import java.util.List;
import java.util.Map;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.logistics.box.PackageStyles;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;

import dev.wareworks.content.item.ItemKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

/**
 * The building blocks of a <b>package door</b> in a GameTest (M26 and M27, issue #18): a Create Packager with its back
 * to a station, a vanilla sign that names it, a package built from its component, and the readings a test asserts on.
 * <p>
 * Shared by {@link PackageHandoverGameTests}, which pins the door itself, and {@link PackageDoorGameTests}, which orders
 * goods to one from the warehouse terminal. Every accessor fails the test rather than throwing anything else, so it is
 * safe inside sequences. A package is always built from its component here and never through Create's own helpers that
 * a production change could reach, so a box in a test is exactly what the test says it is.
 */
final class PackageDoorFixture {
    private PackageDoorFixture() {
    }

    /**
     * A Create Packager at {@code pos} facing {@code facing} — so its <b>back</b> touches the block at
     * {@code pos - facing}, which is the station it serves.
     */
    static void placePackager(GameTestHelper helper, BlockPos pos, Direction facing) {
        helper.setBlock(pos, AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, facing));
    }

    /**
     * Writes {@code lines} on the front or back of the sign at {@code pos}, placing a standing sign first if there is
     * none. Lines beyond {@code SignText.LINES} are a programming error in the test, not a case to handle.
     */
    static void writeSign(GameTestHelper helper, BlockPos pos, boolean front, String... lines) {
        if (!(helper.getBlockState(pos).getBlock() instanceof SignBlock))
            helper.setBlock(pos, Blocks.OAK_SIGN);
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(pos));
        if (!(be instanceof SignBlockEntity sign)) {
            helper.fail("missing sign block entity", pos);
            return;
        }
        SignText text = new SignText();
        for (int line = 0; line < lines.length && line < SignText.LINES; line++)
            text = text.setMessage(line, Component.literal(lines[line]));
        helper.assertTrue(sign.setText(text, front), "the sign took the text");
    }

    /** A deterministic Create package holding {@code contents}, built from the component and never through Create. */
    static ItemStack packageOf(ItemStack... contents) {
        ItemStack box = PackageStyles.getDefaultBox();
        box.set(AllDataComponents.PACKAGE_CONTENTS, ItemContainerContents.fromItems(List.of(contents)));
        return box;
    }

    /**
     * What {@code box} holds, in the terms the conservation census uses: a package counts as its contents, so this is
     * also exactly what the census reports for a box standing anywhere in the test.
     */
    static Map<ItemKey, Long> contentsOf(ItemStack box) {
        return ItemCensus.of(ItemKey.of(box), 1);
    }

    static PackagerBlockEntity packagerAt(GameTestHelper helper, BlockPos pos) {
        PackagerBlockEntity be = AllBlockEntityTypes.PACKAGER.getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (be == null) {
            helper.fail("missing Create packager block entity", pos);
            throw new IllegalStateException("unreachable");
        }
        return be;
    }

    /** A fresh block entity of the same type loaded from {@code tag}, standing in for a chunk load. */
    static <T extends BlockEntity> T loadCopy(GameTestHelper helper, T live, CompoundTag tag, Class<T> type) {
        BlockEntity loaded = BlockEntity.loadStatic(live.getBlockPos(), live.getBlockState(), tag,
                helper.getLevel().registryAccess());
        if (!type.isInstance(loaded)) {
            helper.fail("a saved " + type.getSimpleName() + " must load again as one");
            return live;
        }
        return type.cast(loaded);
    }
}
