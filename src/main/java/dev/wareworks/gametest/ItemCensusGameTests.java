package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.logistics.box.PackageEntity;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.box.PackageStyles;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * The conservation gate itself under test (M26): {@link ItemCensus} counts a Create package as its contents, counts the
 * box as nothing, and sees a package lying in the world.
 * <p>
 * Every other test's conservation assertion is only worth what this one proves. Without these two rules a box that
 * swallowed 64 iron reads as 64 items vanishing into one {@code create:package} key, and a loose package is invisible
 * altogether — a {@link PackageEntity} is a {@code LivingEntity}, not an {@code ItemEntity}. A package test written on
 * top of that would pass whether the contents survived or were voided.
 * <p>
 * The packages here are built by hand rather than by a Packager, deterministically from
 * {@link PackageStyles#getDefaultBox} (never {@code getRandomBox}, which picks a style at random): these tests are
 * about the census, not about Create's machines.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class ItemCensusGameTests {
    /** Items in the test box, enough that counting the box instead would be a glaring loss. */
    private static final int IRON_IN_BOX = 64;
    /** A second type in the same box, so every occupied slot has to be counted, not just the first. */
    private static final int PLANKS_IN_BOX = 7;
    /**
     * Slots of a hand-crafted {@code create:package_contents} component, beyond {@link PackageItem#SLOTS}. The
     * component permits 256, so such a stack is reachable with a command or another mod; reading it through
     * {@link PackageItem#getContents} would throw.
     */
    private static final int CRAFTED_SLOTS = 12;
    /** More than a package entity's 5 HP ({@code PackageEntity#createPackageAttributes}). */
    private static final float KILLING_DAMAGE = 10f;

    private ItemCensusGameTests() {
    }

    /**
     * A package in an inventory is counted as its contents and the box as nothing — including an empty box, a second
     * box beside the first, and a crafted component with more slots than a package has.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void censusCountsAPackageAsItsContents(GameTestHelper helper) {
        BlockPos chestPos = new BlockPos(2, BASE_Y, 3);
        helper.setBlock(chestPos, Blocks.CHEST);
        IItemHandler chest = handlerAt(helper, chestPos);

        ItemStack box = packageOf(new ItemStack(Items.IRON_INGOT, IRON_IN_BOX),
                new ItemStack(Items.OAK_PLANKS, PLANKS_IN_BOX));
        helper.assertTrue(PackageItem.isPackage(box), "the fixture must be a real Create package");
        insert(helper, chest, box.copy());

        Map<ItemKey, Long> expected = ItemCensus.of(ItemKey.of(Items.IRON_INGOT), IRON_IN_BOX,
                ItemKey.of(Items.OAK_PLANKS), PLANKS_IN_BOX);
        ItemCensus.assertEquals(helper, expected, "a package in a chest");
        helper.assertFalse(ItemCensus.take(helper).containsKey(ItemKey.of(box)),
                "the box must not be an item key of its own");
        // An expectation written as the package itself must come out as the same census, or a test could never state
        // "I fed one box" (ItemCensus#of and #change apply the same rule).
        ItemCensus.assertEquals(helper, ItemCensus.of(ItemKey.of(box), 1), "an expectation stated as one package");

        // An empty box is nothing at all. That is what lets Create create a box out of nothing on the way out and
        // consume it whole on the way in without either move showing up as a conservation violation.
        insert(helper, chest, PackageStyles.getDefaultBox());
        ItemCensus.assertEquals(helper, expected, "an empty box beside the full one");

        // A component with more slots than PackageItem.SLOTS must be counted, not crash the run.
        insert(helper, chest, craftedOversizedPackage());
        ItemCensus.change(expected, ItemKey.of(Items.COPPER_INGOT), CRAFTED_SLOTS);
        ItemCensus.assertEquals(helper, expected, "a crafted package of " + CRAFTED_SLOTS + " slots");
        helper.succeed();
    }

    /**
     * A package lying in the world is counted, although it is a {@code LivingEntity} the item-entity sweep cannot see,
     * and killing it changes nothing: the contents drop and the box simply ceases to exist.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void censusCountsAPackageEntity(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemCensus.assertEquals(helper, ItemCensus.of(), "an empty test");

        Vec3 spot = helper.absoluteVec(new Vec3(3.5, BASE_Y, 3.5));
        PackageEntity box = PackageEntity.fromItemStack(level, spot,
                packageOf(new ItemStack(Items.IRON_INGOT, IRON_IN_BOX)));
        helper.assertTrue(level.addFreshEntity(box), "the package entity was not added to the level");
        helper.assertTrue(box.isAlive(), "the package entity must be alive to be counted");

        Map<ItemKey, Long> expected = ItemCensus.of(ItemKey.of(Items.IRON_INGOT), IRON_IN_BOX);
        ItemCensus.assertEquals(helper, expected, "a package lying in the world");

        // Both rules together: the contents drop as item entities, the box is gone, and nothing has no loot table of
        // its own, so the census does not move by a single item.
        box.hurt(level.damageSources().explosion(null, null), KILLING_DAMAGE);
        helper.assertTrue(box.isRemoved(), "the package entity must be gone after being destroyed");
        ItemCensus.assertEquals(helper, expected, "the contents dropped where the box was");
        helper.succeed();
    }

    /** A deterministic Create package holding {@code stacks}, one per slot. */
    private static ItemStack packageOf(ItemStack... stacks) {
        ItemStack box = PackageStyles.getDefaultBox();
        box.set(AllDataComponents.PACKAGE_CONTENTS, ItemContainerContents.fromItems(List.of(stacks)));
        return box;
    }

    /** A package whose component declares {@link #CRAFTED_SLOTS} slots, which no Packager would ever build. */
    private static ItemStack craftedOversizedPackage() {
        List<ItemStack> slots = new ArrayList<>();
        for (int slot = 0; slot < CRAFTED_SLOTS; slot++)
            slots.add(new ItemStack(Items.COPPER_INGOT));
        return packageOf(slots.toArray(ItemStack[]::new));
    }

    private static IItemHandler handlerAt(GameTestHelper helper, BlockPos pos) {
        IItemHandler handler = helper.getLevel()
                .getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(pos), null);
        if (handler == null)
            helper.fail("no item handler", pos);
        return handler;
    }

    private static void insert(GameTestHelper helper, IItemHandler handler, ItemStack stack) {
        ItemStack rest = ItemHandlerHelper.insertItem(handler, stack, false);
        helper.assertTrue(rest.isEmpty(), "inventory rejected " + rest);
    }
}
