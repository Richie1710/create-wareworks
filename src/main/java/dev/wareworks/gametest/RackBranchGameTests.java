package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseLayout;
import dev.wareworks.content.crane.CraneJobSummary;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.JobType;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.core.warehouse.LocationRecord;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of the <b>branch</b> a rack position carries since M21 (ADR-033), and of the one promise that came with it:
 * a warehouse with a single aisle writes and syncs exactly the bytes it wrote before the field existed.
 * <p>
 * {@code branchisomittedfromaonebranchsave} builds a full warehouse — storage with items, input, output, production
 * station, stock keeper with a rule, and a misaligned interface — and walks the controller's save, the dock's save and
 * both update tags: every rack position in them must be written in the pre-M21 shape {@code {X, Y, Side}}, with no
 * branch key and no branch side table anywhere. {@code branchsurvivesaracktagroundtrip} is the other half, the one the
 * first test cannot show because nothing produces a branch yet: a position on a further branch does write its {@code B}
 * and reads back as itself.
 * <p>
 * Together they pin the rule that makes a 0.5.0 world open unchanged: <b>the branch is written only when it is not
 * {@link RackPosition#FIRST_BRANCH}</b>, in every one of the three NBT codecs for a rack position (the controller's
 * records, the controller's rack tags for production orders and keeper rules, and the crane's job and goggle summary).
 * <p>
 * Layout: the {@link AisleFixture} aisle on {@code aisle_16x10x7} (controller x = 0, dock x = 1 at z = 3 along +X,
 * {@value #RAILS} rails).
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class RackBranchGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 4;
    private static final int TIMEOUT_TICKS = 400;
    /** Long enough for the membership pass and the first snapshots of every location. */
    private static final int SETTLE_TICKS = 20;

    private static final RackPosition STORAGE = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition MISALIGNED = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition INPUT = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition OUTPUT = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition PRODUCTION = new RackPosition(3, 0, Side.RIGHT);
    private static final RackPosition KEEPER = new RackPosition(4, 0, Side.RIGHT);

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final int STORED = 12;
    private static final long KEEPER_MAXIMUM = 512L;

    /** The pre-M21 keys of a rack position in NBT, and the two keys that only a branched warehouse may add. */
    private static final String X = "X";
    private static final String Y = "Y";
    private static final String SIDE = "Side";
    private static final String BRANCH = "B";
    private static final String MISALIGNED_BRANCHES = "MisalignedBranches";
    private static final String LOCATIONS = "Locations";
    private static final String SOURCE = "Source";
    private static final String TARGET = "Target";
    private static final String MISALIGNED_TAG = "Misaligned";
    private static final String STOCK_RULES = "StockRules";

    /** A branch that is not the first one, for the round trip no live warehouse can produce yet. */
    private static final int OTHER_BRANCH = 3;

    /** Keys of the aisle-name tag (M25, issue #15), and the names and value-box index the carry test uses. */
    private static final String NAMES = "Names";
    private static final String LETTER_KEY = "C";
    private static final int LETTER_D = 3;
    /** A letter no aisle of this fixture ever holds, for the carry without a warehouse. */
    private static final int LETTER_F = 5;
    private static final String ORES = "Ores";
    private static final String METALS = "Metals";

    private RackBranchGameTests() {
    }

    /**
     * A warehouse with one aisle saves and syncs every rack position in the pre-M21 shape: {@code X}, {@code Y} and
     * {@code Side}, and no {@code B}. This is the migration promise of ADR-033 in the only form a test can check — the
     * bytes themselves — rather than as a claim about the code.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void branchIsOmittedFromAOneBranchSave(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE, new ItemStack(Items.IRON_INGOT, STORED));
        aisle.input(INPUT);
        aisle.output(OUTPUT);
        aisle.production(PRODUCTION);
        aisle.stockKeeper(KEEPER);
        // An interface turned along the aisle instead of across it: a member at a rack position that owns no record,
        // which is what fills the controller's misaligned list.
        helper.setBlock(aisle.rackPos(MISALIGNED), WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, AisleFixture.AISLE));

        helper.startSequence().thenIdle(SETTLE_TICKS).thenExecute(() -> {
            WarehouseStockKeeperBlockEntity keeper = keeperAt(helper, aisle);
            keeper.editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L);
            keeper.editRule(0, StockKeeperRules.FIELD_MAXIMUM, null, KEEPER_MAXIMUM);
        }).thenIdle(2).thenExecute(() -> {
            HolderLookup.Provider registries = helper.getLevel().registryAccess();
            WarehouseControllerBlockEntity controller = aisle.controller();
            StackerCraneBlockEntity dock = aisle.dock();

            CompoundTag saved = controller.saveWithoutMetadata(registries);
            // The save has to contain the things whose rack positions we are checking, or the check proves nothing.
            helper.assertTrue(saved.getList(LOCATIONS, Tag.TAG_COMPOUND).size() >= 5,
                    "every member of the aisle is recorded");
            helper.assertTrue(saved.getIntArray(MISALIGNED_TAG).length > 0, "the misaligned interface is recorded");
            helper.assertTrue(saved.getList(STOCK_RULES, Tag.TAG_COMPOUND).size() == 1, "the keeper's rules are saved");

            // A walk that found no rack position at all would pass on nothing, so the count is asserted too: five
            // members and the keeper's rack tag beside them.
            helper.assertTrue(assertNoBranchAnywhere(helper, saved, "the controller's save") >= 6,
                    "the walk really visited the saved rack positions");
            assertNoBranchAnywhere(helper, controller.getUpdateTag(registries), "the controller's update tag");
            assertNoBranchAnywhere(helper, dock.saveWithoutMetadata(registries), "the dock's save");
            assertNoBranchAnywhere(helper, dock.getUpdateTag(registries), "the dock's update tag");

            for (LocationRecord record : controller.locations())
                helper.assertTrue(record.position().isOnFirstBranch(),
                        "every record of a one-aisle warehouse is on branch 0, not " + record);
        }).thenSucceed();
    }

    /**
     * The other direction, which no live warehouse can show yet: a rack position on a further branch writes its
     * {@code B} and reads back as itself. Checked on {@link CraneJobSummary}, the one rack codec with a public
     * surface; the controller's two codecs are the same rule written the same way.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void branchSurvivesARackTagRoundTrip(GameTestHelper helper) {
        CraneJobSummary onFirst = new CraneJobSummary(JobType.STORE, Items.IRON_INGOT.asItem(), STORED,
                RackPosition.of(1, 0, Side.LEFT), RackPosition.of(2, 1, Side.RIGHT), LocationKind.STORAGE);
        CompoundTag first = new CompoundTag();
        onFirst.write(first);
        assertNoBranchAnywhere(helper, first, "a summary on branch 0");
        helper.assertValueEqual(CraneJobSummary.read(first), Optional.of(onFirst), "branch 0 round trip");

        CraneJobSummary onOther = new CraneJobSummary(JobType.STORE, Items.IRON_INGOT.asItem(), STORED,
                RackPosition.of(OTHER_BRANCH, 1, 0, Side.LEFT), RackPosition.of(2, 1, Side.RIGHT),
                LocationKind.STORAGE);
        CompoundTag other = new CompoundTag();
        onOther.write(other);
        helper.assertValueEqual(other.getCompound(SOURCE).getInt(BRANCH), OTHER_BRANCH, "the source's branch");
        helper.assertFalse(other.getCompound(TARGET).contains(BRANCH),
                "a target still on branch 0 writes no branch, even beside a source that does");
        helper.assertValueEqual(CraneJobSummary.read(other), Optional.of(onOther), "branch round trip");

        // Crafted data: a branch outside the address letters is no rack position, so the tag is refused rather than
        // loaded as some other location.
        CompoundTag crafted = other.copy();
        crafted.getCompound(SOURCE).putInt(BRANCH, RackPosition.MAX_BRANCH + 1);
        helper.assertValueEqual(CraneJobSummary.read(crafted), Optional.empty(), "a branch out of range is refused");

        helper.succeed();
    }

    /**
     * A name follows the letter it was given to (M25, issue #15). Scrolling the controller's value box from A to D
     * renames the aisle, and the label goes with it — on the block entity and in the save.
     * <p>
     * It is a <b>swap</b>, not an overwrite, and that is what makes the gesture safe: scrolling the box is reversible,
     * so the table's answer to it has to be reversible too. Scrolling D back to A restores exactly what was there, and
     * a letter change can therefore never destroy a name a player gave another aisle.
     * <p>
     * The last part is the case that needed both halves to hold at once (M25 review fix): <b>the dock is broken
     * first</b>. Names deliberately outlive a lost dock and the value box deliberately still scrolls then, so a letter
     * really moves while the controller has no warehouse — and the name has to move with it. It used to stay behind,
     * and because the carry is a swap, scrolling back then moved it onto the letter the rebuilt warehouse had just
     * taken, a state no further scroll could repair.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void aisleNameFollowsItsLetter(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);

        helper.startSequence().thenIdle(SETTLE_TICKS).thenExecute(() -> {
            WarehouseControllerBlockEntity controller = aisle.controller();
            HolderLookup.Provider registries = helper.getLevel().registryAccess();
            ScrollValueBehaviour letter = BlockEntityBehaviour.get(controller, ScrollValueBehaviour.TYPE);
            if (letter == null) {
                helper.fail("the controller has no aisle letter value box", aisle.controllerPos());
                return;
            }
            helper.assertValueEqual(controller.setAisleName('A', ORES), ORES, "aisle A is named");
            helper.assertValueEqual(controller.aisleName('A'), Optional.of(ORES), "and reads back");

            letter.setValue(LETTER_D);
            helper.assertValueEqual(controller.aisleLetter(), 'D', "the value box moved");
            helper.assertValueEqual(controller.warehouse().flatMap(WarehouseLayout::letter), Optional.of('D'),
                    "and so did the warehouse's own letter");
            helper.assertTrue(controller.aisleName('A').isEmpty(), "A is nobody's aisle any more");
            helper.assertValueEqual(controller.aisleName('D'), Optional.of(ORES), "the name went with the letter");
            ListTag saved = controller.saveWithoutMetadata(registries).getList(NAMES, Tag.TAG_COMPOUND);
            helper.assertValueEqual(saved.size(), 1, "one saved name");
            helper.assertValueEqual(saved.getCompound(0).getString(LETTER_KEY), "D", "saved under the new letter");

            // The other direction: a scroll is reversible, so this has to be too.
            letter.setValue(0);
            helper.assertValueEqual(controller.aisleName('A'), Optional.of(ORES), "scrolling back restores the name");
            helper.assertTrue(controller.aisleName('D').isEmpty(), "and leaves nothing behind on D");

            // A swap, not an overwrite: a second name on the letter being scrolled onto is moved, never dropped.
            helper.assertValueEqual(controller.setAisleName('D', METALS), METALS, "a second aisle is named");
            letter.setValue(LETTER_D);
            helper.assertValueEqual(controller.aisleName('D'), Optional.of(ORES), "the dock aisle kept its name");
            helper.assertValueEqual(controller.aisleName('A'), Optional.of(METALS),
                    "and the name on the letter it took moved the other way, instead of being destroyed");
            helper.assertValueEqual(controller.aisleNames().size(), 2, "both names are still there");
        })
                // Without a warehouse: break the dock, so the controller's layout is dropped while the names stay.
                .thenExecute(() -> helper.setBlock(aisle.dockPos(), Blocks.AIR))
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertTrue(controller.warehouse().isEmpty(), "the broken dock took the warehouse away");
                    helper.assertValueEqual(controller.aisleName('D'), Optional.of(ORES),
                            "while the names outlive it, which is the whole point of keeping them");
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    ScrollValueBehaviour letter = BlockEntityBehaviour.get(controller, ScrollValueBehaviour.TYPE);
                    if (letter == null) {
                        helper.fail("the controller has no aisle letter value box", aisle.controllerPos());
                        return;
                    }
                    letter.setValue(LETTER_F);
                    helper.assertValueEqual(controller.aisleLetter(), 'F', "the box still scrolls without a dock");
                    helper.assertValueEqual(controller.aisleName('F'), Optional.of(ORES),
                            "and the name moved with the letter, dock or no dock");
                    helper.assertTrue(controller.aisleName('D').isEmpty(), "nothing is left behind on D");
                    ListTag saved = controller.saveWithoutMetadata(registries).getList(NAMES, Tag.TAG_COMPOUND);
                    helper.assertValueEqual(saved.size(), 2, "both names are still saved");

                    // Reversible here too: this is where leaving the name behind became unrepairable, because the
                    // swap would then have moved it onto the letter the warehouse was about to take.
                    letter.setValue(LETTER_D);
                    helper.assertValueEqual(controller.aisleName('D'), Optional.of(ORES), "scrolling back restores it");
                    helper.assertTrue(controller.aisleName('F').isEmpty(), "and leaves nothing on F");
                })
                // And the rebuilt warehouse finds its own label on the letter it comes back with.
                .thenExecute(aisle::placeDock)
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertTrue(controller.warehouse().isPresent(), "the rebuilt dock brings the warehouse back");
                    helper.assertValueEqual(controller.aisleLetter(), 'D', "on the letter the box carries");
                    helper.assertValueEqual(controller.aisleName(controller.aisleLetter()), Optional.of(ORES),
                            "and the label is on it");
                    helper.assertValueEqual(controller.namedAisles().get('D'), ORES,
                            "so every surface of this warehouse shows it again");
                })
                .thenSucceed();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /**
     * Fails unless no rack position anywhere in {@code tag} carries a branch: no compound that looks like a rack
     * position ({@code X}, {@code Y} and {@code Side}) has a {@code B}, and no branch side table is present.
     *
     * @return how many rack positions the walk found, so a caller can rule out a check that passed on nothing
     */
    private static int assertNoBranchAnywhere(GameTestHelper helper, CompoundTag tag, String what) {
        Deque<CompoundTag> pending = new ArrayDeque<>();
        pending.add(tag);
        int rackTags = 0;
        while (!pending.isEmpty()) {
            CompoundTag current = pending.removeFirst();
            helper.assertFalse(current.contains(MISALIGNED_BRANCHES),
                    what + " must carry no branch side table while the warehouse has one aisle");
            if (isRackTag(current)) {
                rackTags++;
                helper.assertFalse(current.contains(BRANCH),
                        what + " writes a branch for the rack position " + current + ", which must stay implicit");
            }
            for (String key : current.getAllKeys()) {
                Tag child = current.get(key);
                if (child instanceof CompoundTag compound)
                    pending.add(compound);
                else if (child instanceof ListTag list && list.getElementType() == Tag.TAG_COMPOUND)
                    for (int i = 0; i < list.size(); i++)
                        pending.add(list.getCompound(i));
            }
        }
        return rackTags;
    }

    /** Whether {@code tag} is the NBT form of a rack position: {@code {X: int, Y: int, Side: "L"|"R"}}. */
    private static boolean isRackTag(CompoundTag tag) {
        return tag.contains(X, Tag.TAG_INT) && tag.contains(Y, Tag.TAG_INT) && tag.contains(SIDE, Tag.TAG_STRING);
    }

    private static WarehouseStockKeeperBlockEntity keeperAt(GameTestHelper helper, AisleFixture aisle) {
        if (helper.getBlockEntity(aisle.rackPos(KEEPER)) instanceof WarehouseStockKeeperBlockEntity keeper)
            return keeper;
        helper.fail("no stock keeper at " + KEEPER, aisle.rackPos(KEEPER));
        throw new AssertionError("unreachable");
    }
}
