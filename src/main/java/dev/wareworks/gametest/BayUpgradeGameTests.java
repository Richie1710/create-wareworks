package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.content.handtruck.HandTruckItem;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.storage.FluidBayBlock;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import dev.wareworks.content.storage.PalletEntity;
import dev.wareworks.content.storage.RackBayBlock;
import dev.wareworks.content.storage.RackBayBlockEntity;
import dev.wareworks.content.storage.StorageFilterBehaviour;
import dev.wareworks.content.storage.StorageFilterValueBox;
import dev.wareworks.content.storage.TieredBay;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.storage.BayTier;
import dev.wareworks.core.storage.FluidBayTier;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksItems;
import dev.wareworks.util.WareworksLang;
import net.minecraft.advancements.critereon.BlockPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.AdventureModePredicate;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * The upgrade kits (ADR-083): a bay becomes the next tier of its family <b>where it stands</b>, through the server's
 * real click dispatch ({@link ClickingPlayer}, {@code ServerPlayerGameMode#useItemOn}) and through a real Deployer.
 * <p>
 * The claim every test pins in its own way is that the block changes and the block entity <b>stays</b>: the very same
 * object, so goods, filter, priority, address and a crane's target all survive by construction, and nothing — no
 * pallet, no filter item, no fluid — leaves the bay. {@link ItemCensus} brackets every click; the one declared change
 * is the kit a survival player or a Deployer uses up.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class BayUpgradeGameTests {
    /** Every bay faces north, into the rack depth, so its aisle face is the south one. */
    private static final Direction FACING = Direction.NORTH;
    private static final Direction AISLE_FACE = FACING.getOpposite();
    private static final BlockPos BAY = new BlockPos(3, BASE_Y, 3);
    private static final BlockPos BAY_ABOVE = BAY.above();
    private static final BlockPos SECOND_BAY = new BlockPos(1, BASE_Y, 3);
    private static final BlockPos FLUID_BAY = new BlockPos(5, BASE_Y, 3);
    /** A brass bay standing alone, two blocks behind {@link #BAY}. */
    private static final BlockPos BRASS_BAY = new BlockPos(3, BASE_Y, 1);
    private static final BlockPos STAND = new BlockPos(3, BASE_Y, 5);
    /** The warehouse interface whose value box a loaded hand truck and a kit are clicked on. */
    private static final BlockPos INTERFACE = new BlockPos(1, BASE_Y, 3);
    private static final BlockPos TRUCK_CHEST = new BlockPos(5, BASE_Y, 5);

    private static final ItemKey COBBLESTONE = ItemKey.of(Items.COBBLESTONE);
    private static final ItemKey ANDESITE_KIT = ItemKey.of(WareworksItems.RACK_UPGRADE_KIT_ANDESITE.get());
    private static final int LOAD = 3_000;
    private static final int PRIORITY = 3;
    private static final int WATER = 40_000;
    private static final double PIXEL = 1.0 / 16.0;
    private static final int SETTLE_TICKS = 60;
    private static final int TEST_RPM = 128;

    // --- the aisle tests -------------------------------------------------------------------------------------------

    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TIMEOUT_TICKS = 1600;
    private static final RackPosition BAY_RACK = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition INPUT_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final int STACK = 64;
    /** A wooden bay of cobblestone at the shipped capacity: 64 stacks. */
    private static final int WOOD_FULL = BayTier.WOOD.defaultStacks() * STACK;
    private static final int PRELOADED = 20;

    private BayUpgradeGameTests() {
    }

    /**
     * The whole promise of a kit: a wooden bay holding 3 000 cobblestone behind a List Filter at priority 3 becomes an
     * andesite bay with the very same block entity, the same goods, the same filter and the same priority, and nothing
     * is dropped — no pallet, no filter item. The survival player's kit is used up, and that is the only change.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void upgradeKeepsEverything(GameTestHelper helper) {
        placeBay(helper, BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState());
        LogCapture warnings = LogCapture.ofWarnings();
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            RackBayBlockEntity bay = rackBayAt(helper, BAY);
            helper.assertTrue(bay.insert(COBBLESTONE.toStack(LOAD), false).isEmpty(), "the bay takes the load");
            ItemStack filter = FilterCells.list(Items.COBBLESTONE).toStack();
            helper.assertTrue(bay.setStoreFilter(filter), "the List Filter is set");
            bay.setStorePriority(PRIORITY);
            ItemStack filterBefore = bay.storeFilter();

            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, WareworksItems.RACK_UPGRADE_KIT_ANDESITE.asStack(2));
            Map<ItemKey, Long> census = ItemCensus.take(helper, player);

            InteractionResult result = click(helper, player, faceHit(helper, BAY));
            helper.assertTrue(result.consumesAction(), "the kit is used: " + result);
            helper.assertBlockPresent(WareworksBlocks.RACK_BAY_ANDESITE.get(), BAY);
            helper.assertTrue(blockEntityAt(helper, BAY) == bay, "the very same block entity stands there");
            helper.assertTrue(bay.storedKey().filter(COBBLESTONE::equals).isPresent(), "the same item");
            helper.assertValueEqual(bay.storedCount(), LOAD, "the same count");
            helper.assertValueEqual(bay.tier(), BayTier.ANDESITE, "the bay reads its new tier");
            helper.assertTrue(ItemStack.matches(bay.storeFilter(), filterBefore),
                    "the same List Filter: " + bay.storeFilter());
            helper.assertValueEqual(bay.storePriority(), PRIORITY, "the same priority");
            helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "one kit was used up");
            helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "nothing was dropped");
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(PalletEntity.class, helper.getBounds()).isEmpty(),
                    "no pallet was left");
            ItemCensus.change(census, ANDESITE_KIT, -1);
            ItemCensus.assertEquals(helper, census, "after the upgrade", player);
            // The capability answers for the new tier at once: a machine sees the new room.
            IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                    helper.absolutePos(BAY), null);
            helper.assertTrue(handler != null && handler.getStackInSlot(0).getCount() == LOAD,
                    "the capability still holds the load");
            helper.assertValueEqual(helper.getLevel().getBlockState(helper.absolutePos(BAY)).getValue(TieredBay.FILL),
                    bay.fillStep(), "the fill level follows the new capacity");
        }).thenIdle(2).thenExecute(() -> {
            String bayPos = helper.absolutePos(BAY).toString();
            List<String> recorded = warnings.closeAndTake();
            helper.assertTrue(LogCapture.firstContaining(recorded, bayPos) == null,
                    "no warning about the bay: " + recorded);
        }).thenSucceed();
    }

    /** A creative player's kit is not used up; the bay is upgraded all the same. */
    @GameTest(template = EMPTY_7X5X7)
    public static void upgradeInCreativeKeepsTheKit(GameTestHelper helper) {
        placeBay(helper, BAY, WareworksBlocks.RACK_BAY_ANDESITE.getDefaultState());
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setGameMode(GameType.CREATIVE);
            player.setItemInHand(InteractionHand.MAIN_HAND, WareworksItems.RACK_UPGRADE_KIT_BRASS.asStack());
            helper.assertTrue(click(helper, player, faceHit(helper, BAY)).consumesAction(), "the kit is used");
            helper.assertBlockPresent(WareworksBlocks.RACK_BAY_BRASS.get(), BAY);
            helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "the creative player keeps the kit");
        }).thenSucceed();
    }

    /**
     * The column rule, the one rule an upgrade can break: the top bay of a wooden column is refused while the bay below
     * is still wood, with the column line and the kit kept; bottom first, then the top, and both succeed.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void upgradeRefusedWhenTheBayBelowIsWeaker(GameTestHelper helper) {
        placeBay(helper, BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState());
        placeBay(helper, BAY_ABOVE, WareworksBlocks.RACK_BAY_WOOD.getDefaultState());
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            RackBayBlockEntity top = rackBayAt(helper, BAY_ABOVE);
            top.insert(COBBLESTONE.toStack(PRELOADED), false);
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, WareworksItems.RACK_UPGRADE_KIT_ANDESITE.asStack(2));
            Map<ItemKey, Long> census = ItemCensus.take(helper, player);

            InteractionResult refused = click(helper, player, faceHit(helper, BAY_ABOVE));
            helper.assertTrue(refused == InteractionResult.FAIL, "the top bay is refused: " + refused);
            helper.assertBlockPresent(WareworksBlocks.RACK_BAY_WOOD.get(), BAY_ABOVE);
            helper.assertValueEqual(top.storedCount(), PRELOADED, "and keeps its goods");
            helper.assertValueEqual(player.getMainHandItem().getCount(), 2, "the kit is kept");
            assertTold(helper, player, WareworksLang.BAY_UPGRADE_COLUMN);
            ItemCensus.assertEquals(helper, census, "after the refusal", player);

            helper.assertTrue(click(helper, player, faceHit(helper, BAY)).consumesAction(), "the bottom first");
            helper.assertBlockPresent(WareworksBlocks.RACK_BAY_ANDESITE.get(), BAY);
            helper.assertTrue(click(helper, player, faceHit(helper, BAY_ABOVE)).consumesAction(), "then the top");
            helper.assertBlockPresent(WareworksBlocks.RACK_BAY_ANDESITE.get(), BAY_ABOVE);
            helper.assertTrue(blockEntityAt(helper, BAY_ABOVE) == top, "the top bay kept its block entity");
            helper.assertValueEqual(top.storedCount(), PRELOADED, "and its goods");
            helper.assertTrue(player.getMainHandItem().isEmpty(), "both kits are used up");
            ItemCensus.change(census, ANDESITE_KIT, -2);
            ItemCensus.assertEquals(helper, census, "after both upgrades", player);
            helper.assertFalse(stateAt(helper, BAY).getValue(TieredBay.OVERLOADED), "the column is legal");
        }).thenSucceed();
    }

    /**
     * A kit fits exactly the tier it starts from of its own family: a brass kit on wood, an andesite kit on andesite and
     * on brass, a rack kit on a fluid bay and the fluid kit on a rack bay are all refused with the "fits" line, the bay
     * and the kit unchanged.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void wrongKitRefused(GameTestHelper helper) {
        placeBay(helper, BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState());
        placeBay(helper, SECOND_BAY, WareworksBlocks.RACK_BAY_ANDESITE.getDefaultState());
        placeBay(helper, BRASS_BAY, WareworksBlocks.RACK_BAY_BRASS.getDefaultState());
        helper.setBlock(FLUID_BAY, WareworksBlocks.FLUID_BAY_COPPER.getDefaultState().setValue(FluidBayBlock.FACING,
                FACING));
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            assertWrongKit(helper, player, BAY, WareworksItems.RACK_UPGRADE_KIT_BRASS.asStack(),
                    WareworksBlocks.RACK_BAY_WOOD.get(), "a brass kit on wood");
            assertWrongKit(helper, player, SECOND_BAY, WareworksItems.RACK_UPGRADE_KIT_ANDESITE.asStack(),
                    WareworksBlocks.RACK_BAY_ANDESITE.get(), "an andesite kit on andesite");
            assertWrongKit(helper, player, BRASS_BAY, WareworksItems.RACK_UPGRADE_KIT_ANDESITE.asStack(),
                    WareworksBlocks.RACK_BAY_BRASS.get(), "an andesite kit on brass, which would be a downgrade");
            assertWrongKit(helper, player, BRASS_BAY, WareworksItems.RACK_UPGRADE_KIT_BRASS.asStack(),
                    WareworksBlocks.RACK_BAY_BRASS.get(), "a brass kit on the top tier");
            assertWrongKit(helper, player, FLUID_BAY, WareworksItems.RACK_UPGRADE_KIT_ANDESITE.asStack(),
                    WareworksBlocks.FLUID_BAY_COPPER.get(), "a rack kit on a fluid bay");
            assertWrongKit(helper, player, BAY, WareworksItems.FLUID_BAY_UPGRADE_KIT_BRASS.asStack(),
                    WareworksBlocks.RACK_BAY_WOOD.get(), "the fluid kit on a rack bay");
        }).thenSucceed();
    }

    /**
     * A wooden bay a command put under an andesite one is overloaded; upgrading it repairs the column — it carries
     * andesite now — so the flag clears and the bay accepts store jobs again. Upgrading bottom up is also how a broken
     * column is mended.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void upgradeClearsAnOverloadedFlag(GameTestHelper helper) {
        placeBay(helper, BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState());
        placeBay(helper, BAY_ABOVE, WareworksBlocks.RACK_BAY_ANDESITE.getDefaultState());
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            RackBayBlockEntity bay = rackBayAt(helper, BAY);
            helper.assertTrue(stateAt(helper, BAY).getValue(TieredBay.OVERLOADED), "the command broke the column");
            helper.assertFalse(bay.acceptsStoring(), "so the wooden bay takes no store jobs");
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, WareworksItems.RACK_UPGRADE_KIT_ANDESITE.asStack());
            helper.assertTrue(click(helper, player, faceHit(helper, BAY)).consumesAction(),
                    "an overloaded bay may be upgraded");
            helper.assertBlockPresent(WareworksBlocks.RACK_BAY_ANDESITE.get(), BAY);
            helper.assertFalse(stateAt(helper, BAY).getValue(TieredBay.OVERLOADED), "the flag is clear");
            helper.assertTrue(bay.acceptsStoring(), "and the bay takes store jobs again");
        }).thenIdle(2).thenExecute(() -> helper.assertFalse(stateAt(helper, BAY).getValue(TieredBay.OVERLOADED),
                "and no neighbour update brings the flag back")).thenSucceed();
    }

    /**
     * A claim mod or a logger cancels the place event the upgrade posts: the bay is swapped back to wood with the
     * same block entity and every item, the kit is kept, and no pallet appears — the swap back is the same in-place
     * swap, never a snapshot restore that would break the bay.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void upgradePlaceEventCancelled(GameTestHelper helper) {
        placeBay(helper, BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState());
        BlockPos refused = helper.absolutePos(BAY);
        Consumer<BlockEvent.EntityPlaceEvent> claim = event -> {
            if (event.getPos().equals(refused))
                event.setCanceled(true);
        };
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            RackBayBlockEntity bay = rackBayAt(helper, BAY);
            bay.insert(COBBLESTONE.toStack(LOAD), false);
            bay.setStoreFilter(FilterCells.list(Items.COBBLESTONE).toStack());
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, WareworksItems.RACK_UPGRADE_KIT_ANDESITE.asStack());
            Map<ItemKey, Long> census = ItemCensus.take(helper, player);

            NeoForge.EVENT_BUS.addListener(BlockEvent.EntityPlaceEvent.class, claim);
            InteractionResult result;
            try {
                result = click(helper, player, faceHit(helper, BAY));
            } finally {
                NeoForge.EVENT_BUS.unregister(claim);
            }
            helper.assertTrue(result == InteractionResult.FAIL, "a cancelled place refuses: " + result);
            helper.assertBlockPresent(WareworksBlocks.RACK_BAY_WOOD.get(), BAY);
            helper.assertTrue(blockEntityAt(helper, BAY) == bay, "the same block entity after the swap back");
            helper.assertValueEqual(bay.storedCount(), LOAD, "with every item");
            helper.assertTrue(bay.hasStoreFilter(), "and its filter");
            helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "the kit is kept");
            helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "nothing was dropped");
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(PalletEntity.class, helper.getBounds()).isEmpty(),
                    "no pallet was left");
            assertTold(helper, player, WareworksLang.BAY_UPGRADE_NOT_ALLOWED);
            ItemCensus.assertEquals(helper, census, "after the refused upgrade", player);
        }).thenSucceed();
    }

    /**
     * An adventure player may not change a bay, exactly as they may not place a block. NeoForge itself passes such a
     * click before the kit sees it ({@code IItemStackExtension#onItemUseFirst} without a {@code can_place_on}), and the
     * bay's own gesture passes a kit on, so nothing happens at all; the kit's own adventure check is what stops a kit
     * that does carry {@code can_place_on} but no {@code can_break}. A kit with both, for the bay itself, upgrades it:
     * the {@code can_place_on} test lands on the bay, not on the block behind it (air here), so this test fails both
     * without the {@code can_break} check and with the build check aimed behind the bay.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void upgradeRefusedInAdventure(GameTestHelper helper) {
        placeBay(helper, BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState());
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setGameMode(GameType.ADVENTURE);
            player.setItemInHand(InteractionHand.MAIN_HAND, WareworksItems.RACK_UPGRADE_KIT_ANDESITE.asStack());
            InteractionResult result = click(helper, player, faceHit(helper, BAY));
            helper.assertFalse(result.consumesAction(), "an adventure player's click does nothing: " + result);
            helper.assertBlockPresent(WareworksBlocks.RACK_BAY_WOOD.get(), BAY);
            helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "the kit is kept");

            ItemStack placing = WareworksItems.RACK_UPGRADE_KIT_ANDESITE.asStack();
            placing.set(DataComponents.CAN_PLACE_ON, new AdventureModePredicate(List.of(BlockPredicate.Builder.block()
                    .of(WareworksBlocks.RACK_BAY_WOOD.get()).build()), true));
            player.setItemInHand(InteractionHand.MAIN_HAND, placing);
            InteractionResult refused = click(helper, player, faceHit(helper, BAY));
            helper.assertTrue(refused == InteractionResult.FAIL, "a kit that may be placed on the bay is still "
                    + "refused without breaking rights: " + refused);
            helper.assertBlockPresent(WareworksBlocks.RACK_BAY_WOOD.get(), BAY);
            helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "the kit is kept");
            assertTold(helper, player, WareworksLang.BAY_UPGRADE_NOT_ALLOWED);

            ItemStack rights = placing.copy();
            rights.set(DataComponents.CAN_BREAK, new AdventureModePredicate(List.of(BlockPredicate.Builder.block()
                    .of(WareworksBlocks.RACK_BAY_WOOD.get()).build()), true));
            player.setItemInHand(InteractionHand.MAIN_HAND, rights);
            InteractionResult upgraded = click(helper, player, faceHit(helper, BAY));
            helper.assertTrue(upgraded.consumesAction(), "a kit that may be placed on and break the bay upgrades it: "
                    + upgraded);
            helper.assertBlockPresent(WareworksBlocks.RACK_BAY_ANDESITE.get(), BAY);
            helper.assertTrue(player.getMainHandItem().isEmpty(), "the adventure player's kit is used up");
        }).thenSucceed();
    }

    /**
     * The fluid kit, the one that matters most: breaking a fluid bay loses the fluid, an upgrade keeps all of it. A
     * copper bay with 40 buckets of water becomes a brass one with exactly 40 000 mB, and the "lost" warning a broken
     * fluid bay logs is never written.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void fluidUpgradeKeepsTheFluid(GameTestHelper helper) {
        helper.setBlock(FLUID_BAY, WareworksBlocks.FLUID_BAY_COPPER.getDefaultState().setValue(FluidBayBlock.FACING,
                FACING));
        LogCapture warnings = LogCapture.ofWarnings();
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            FluidBayBlockEntity bay = fluidBayAt(helper, FLUID_BAY);
            helper.assertValueEqual(bay.fill(new FluidStack(Fluids.WATER, WATER), false), WATER, "the water is in");
            bay.setStorePriority(PRIORITY);
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, WareworksItems.FLUID_BAY_UPGRADE_KIT_BRASS.asStack());
            helper.assertTrue(click(helper, player, faceHit(helper, FLUID_BAY)).consumesAction(), "the kit is used");
            helper.assertBlockPresent(WareworksBlocks.FLUID_BAY_BRASS.get(), FLUID_BAY);
            helper.assertTrue(blockEntityAt(helper, FLUID_BAY) == bay, "the very same block entity");
            helper.assertValueEqual(bay.tier(), FluidBayTier.BRASS, "it reads its new tier");
            helper.assertValueEqual(bay.millibuckets(), WATER, "every millibucket is kept");
            helper.assertTrue(bay.storedFluid().isPresent(), "of the same fluid");
            helper.assertValueEqual(bay.storePriority(), PRIORITY, "and the priority");
            helper.assertTrue(player.getMainHandItem().isEmpty(), "the kit is used up");
        }).thenIdle(2).thenExecute(() -> {
            List<String> recorded = warnings.closeAndTake();
            helper.assertTrue(LogCapture.firstContaining(recorded, helper.absolutePos(FLUID_BAY).toString()) == null,
                    "no warning about the fluid bay: " + recorded);
        }).thenSucceed();
    }

    /**
     * The guard is scoped to the kit: a plain {@code setBlock} of another tier over a filled bay — a command, another
     * mod — still resets it as breaking does, with a new block entity and the load on one pallet.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void tierSwapWithoutAKitStillResets(GameTestHelper helper) {
        placeBay(helper, BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState());
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            RackBayBlockEntity bay = rackBayAt(helper, BAY);
            bay.insert(COBBLESTONE.toStack(LOAD), false);
            Map<ItemKey, Long> census = ItemCensus.take(helper);
            BlockState old = stateAt(helper, BAY);
            helper.getLevel().setBlock(helper.absolutePos(BAY),
                    WareworksBlocks.RACK_BAY_ANDESITE.get().withPropertiesOf(old), Block.UPDATE_ALL);
            RackBayBlockEntity fresh = rackBayAt(helper, BAY);
            helper.assertTrue(fresh != bay, "a new block entity stands there");
            helper.assertValueEqual(fresh.storedCount(), 0, "and it is empty");
            List<PalletEntity> pallets = helper.getLevel().getEntitiesOfClass(PalletEntity.class,
                    helper.getBounds().inflate(3.0));
            helper.assertValueEqual(pallets.size(), 1, "the load left as one pallet");
            helper.assertValueEqual(pallets.getFirst().carriedCount(), LOAD, "with every item");
            ItemCensus.assertEquals(helper, census, "after the plain tier swap");
        }).thenSucceed();
    }

    /** An upgraded bay saves and loads as the andesite bay it now is, with its goods, filter and priority. */
    @GameTest(template = EMPTY_7X5X7)
    public static void upgradedBayPersistence(GameTestHelper helper) {
        placeBay(helper, BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState());
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            RackBayBlockEntity bay = rackBayAt(helper, BAY);
            bay.insert(COBBLESTONE.toStack(LOAD), false);
            bay.setStoreFilter(FilterCells.list(Items.COBBLESTONE).toStack());
            bay.setStorePriority(PRIORITY);
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, WareworksItems.RACK_UPGRADE_KIT_ANDESITE.asStack());
            helper.assertTrue(click(helper, player, faceHit(helper, BAY)).consumesAction(), "the kit is used");

            CompoundTag saved = bay.saveWithFullMetadata(helper.getLevel().registryAccess());
            BlockEntity loaded = BlockEntity.loadStatic(bay.getBlockPos(), stateAt(helper, BAY), saved,
                    helper.getLevel().registryAccess());
            if (!(loaded instanceof RackBayBlockEntity copy)) {
                helper.fail("the upgraded bay must load as a rack bay: " + loaded, BAY);
                return;
            }
            helper.assertValueEqual(copy.tier(), BayTier.ANDESITE, "the loaded bay is andesite");
            helper.assertValueEqual(copy.storedCount(), LOAD, "with its goods");
            helper.assertTrue(copy.storedKey().filter(COBBLESTONE::equals).isPresent(), "of the same item");
            helper.assertTrue(ItemStack.matches(copy.storeFilter(), bay.storeFilter()), "its filter");
            helper.assertValueEqual(copy.storePriority(), PRIORITY, "and its priority");
            helper.assertValueEqual(copy.capacityFor(COBBLESTONE), bay.capacityFor(COBBLESTONE),
                    "and the andesite capacity");
        }).thenSucceed();
    }

    /**
     * The kit wins over the bay's own filter slot (ADR-083): a click right in the slot's 4 px sphere upgrades the bay
     * and leaves the filter as it was, where any other item would become the filter. On a warehouse interface the kit
     * is an ordinary item and does become the filter, so the exclusion really asks for a bay.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void upgradeInsideTheValueBox(GameTestHelper helper) {
        placeBay(helper, BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState());
        placeInterface(helper);
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            RackBayBlockEntity bay = rackBayAt(helper, BAY);
            bay.setStoreFilter(COBBLESTONE.toStack());
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, WareworksItems.RACK_UPGRADE_KIT_ANDESITE.asStack());
            helper.assertTrue(click(helper, player, slotHit(helper, BAY)).consumesAction(),
                    "the kit is used inside the value box");
            helper.assertBlockPresent(WareworksBlocks.RACK_BAY_ANDESITE.get(), BAY);
            helper.assertTrue(ItemStack.matches(bay.storeFilter(), COBBLESTONE.toStack()),
                    "the filter is still cobblestone: " + bay.storeFilter());

            ItemStack kit = WareworksItems.RACK_UPGRADE_KIT_ANDESITE.asStack();
            player.setItemInHand(InteractionHand.MAIN_HAND, kit.copy());
            click(helper, player, slotHit(helper, INTERFACE));
            helper.assertTrue(ItemStack.matches(slotAt(helper, INTERFACE).getFilter(), kit),
                    "on an interface the kit is an ordinary filter item: " + slotAt(helper, INTERFACE).getFilter());
        }).thenSucceed();
    }

    /**
     * The latent bug ADR-083 fixes on the way: a <b>loaded</b> hand truck clicked on a filter slot became that slot's
     * ghost filter, its whole load in its components. Now the slot lets it through to the truck's own click, which sets
     * the chest down in front of the interface; the filter stays empty and nothing is lost.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void loadedTruckIsNeverAFilter(GameTestHelper helper) {
        placeInterface(helper);
        helper.setBlock(TRUCK_CHEST, Blocks.CHEST);
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            if (helper.getLevel().getBlockEntity(helper.absolutePos(TRUCK_CHEST)) instanceof ChestBlockEntity chest)
                chest.setItem(0, new ItemStack(Items.DIAMOND, 12));
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(WareworksItems.HAND_TRUCK.get()));
            Map<ItemKey, Long> census = ItemCensus.take(helper, player);
            helper.assertTrue(click(helper, player, topHit(helper, TRUCK_CHEST)).consumesAction(), "the chest is loaded");
            helper.assertTrue(HandTruckItem.isLoaded(player.getMainHandItem()), "the truck carries it");

            StorageFilterBehaviour slot = slotAt(helper, INTERFACE);
            helper.assertTrue(slot.bypassesInput(player.getMainHandItem()), "the slot lets a loaded truck through");
            helper.assertFalse(slot.mayInteract(player), "and draws no box for it");
            click(helper, player, slotHit(helper, INTERFACE));
            helper.assertTrue(slot.getFilter().isEmpty(), "the loaded truck did not become the filter: "
                    + slot.getFilter());
            ItemCensus.assertEquals(helper, census, "after the click on the filter slot", player);
        }).thenSucceed();
    }

    /**
     * A Deployer may use a kit (ADR-083): an upgrade moves no item, and a Deployer can already break a bay. A powered
     * Deployer holding an andesite kit upgrades the wooden bay in front of it and uses the kit up; a second one at the
     * top of a wooden column is refused silently and keeps its kit.
     */
    @GameTest(template = EMPTY_7X5X7, timeoutTicks = 400)
    public static void upgradeByDeployer(GameTestHelper helper) {
        BlockPos lone = new BlockPos(2, BASE_Y, 1);
        BlockPos columnFoot = new BlockPos(5, BASE_Y, 1);
        BlockPos columnHead = columnFoot.above();
        BlockPos loneDeployer = lone.relative(AISLE_FACE, 2);
        BlockPos headDeployer = columnHead.relative(AISLE_FACE, 2);
        placeBay(helper, lone, WareworksBlocks.RACK_BAY_WOOD.getDefaultState());
        placeBay(helper, columnFoot, WareworksBlocks.RACK_BAY_WOOD.getDefaultState());
        placeBay(helper, columnHead, WareworksBlocks.RACK_BAY_WOOD.getDefaultState());
        placeDeployer(helper, loneDeployer);
        placeDeployer(helper, headDeployer);
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            rackBayAt(helper, lone).insert(COBBLESTONE.toStack(PRELOADED), false);
            rackBayAt(helper, columnHead).insert(COBBLESTONE.toStack(PRELOADED), false);
            deployerHand(helper, loneDeployer).insertItem(0, WareworksItems.RACK_UPGRADE_KIT_ANDESITE.asStack(), false);
            deployerHand(helper, headDeployer).insertItem(0, WareworksItems.RACK_UPGRADE_KIT_ANDESITE.asStack(), false);
            Map<ItemKey, Long> census = ItemCensus.take(helper);
            ItemCensus.change(census, ANDESITE_KIT, -1);
            helper.onEachTick(() -> {
                if (stateAt(helper, lone).is(WareworksBlocks.RACK_BAY_ANDESITE.get()))
                    ItemCensus.assertEquals(helper, census, "once the Deployer upgraded its bay");
            });
        }).thenWaitUntil(() -> helper.assertBlockPresent(WareworksBlocks.RACK_BAY_ANDESITE.get(), lone))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(rackBayAt(helper, lone).storedCount(), PRELOADED, "the goods stay");
                    helper.assertTrue(deployerHand(helper, loneDeployer).getStackInSlot(0).isEmpty(),
                            "the Deployer used its kit up");
                    helper.assertBlockPresent(WareworksBlocks.RACK_BAY_WOOD.get(), columnHead);
                    helper.assertValueEqual(deployerHand(helper, headDeployer).getStackInSlot(0).getCount(), 1,
                            "the refused Deployer keeps its kit");
                    helper.assertValueEqual(rackBayAt(helper, columnHead).storedCount(), PRELOADED,
                            "and the column's top bay keeps its goods");
                })
                .thenSucceed();
    }

    /**
     * The controller re-reads the capacity: the only cobblestone location is a full wooden bay, so 64 cobblestone wait
     * at the input; one kit later the crane stores them into the same bay.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void upgradeLetsTheCraneStoreMore(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        BlockPos bayPos = aisle.rackPos(BAY_RACK);
        helper.setBlock(bayPos, WareworksBlocks.RACK_BAY_WOOD.getDefaultState().setValue(RackBayBlock.FACING,
                aisle.sideDirection(BAY_RACK)));
        RackBayBlockEntity bay = rackBayAt(helper, bayPos);
        helper.assertTrue(bay.insert(COBBLESTONE.toStack(WOOD_FULL), false).isEmpty(), "the bay is filled up");
        aisle.build(true);
        aisle.input(INPUT_RACK);
        ClickingPlayer player = ClickingPlayer.at(helper, aisle.rackPos(INPUT_RACK));
        player.setItemInHand(InteractionHand.MAIN_HAND, WareworksItems.RACK_UPGRADE_KIT_ANDESITE.asStack());
        Map<ItemKey, Long> conserved = ItemCensus.of(COBBLESTONE, WOOD_FULL, ANDESITE_KIT, 1);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the bay is upgraded", player));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), COBBLESTONE.toStack(STACK));
                    ItemCensus.change(conserved, COBBLESTONE, STACK);
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(INPUT_RACK, COBBLESTONE), (long) STACK,
                            "the full wooden bay takes nothing, so the items wait at the input");
                    helper.assertValueEqual(bay.storedCount(), WOOD_FULL, "the bay is still full");
                    helper.assertTrue(click(helper, player, faceHit(helper, bayPos, aisle.sideDirection(BAY_RACK)))
                            .consumesAction(), "the kit is used on the full bay");
                    ItemCensus.change(conserved, ANDESITE_KIT, -1);
                    helper.assertBlockPresent(WareworksBlocks.RACK_BAY_ANDESITE.get(), bayPos);
                    helper.assertTrue(blockEntityAt(helper, bayPos) == bay, "the same block entity");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(bay.storedCount(), WOOD_FULL + STACK,
                        "the crane stored the waiting stack into the upgraded bay"))
                .thenExecuteAfter(SETTLE_TICKS, aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * An upgrade while the crane is carrying goods to the very bay: the block entity is the same, so the job's target
     * and its reservation stay valid, the crane delivers, and nothing is lost.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void upgradeDuringACraneJob(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        BlockPos bayPos = aisle.rackPos(BAY_RACK);
        helper.setBlock(bayPos, WareworksBlocks.RACK_BAY_WOOD.getDefaultState().setValue(RackBayBlock.FACING,
                aisle.sideDirection(BAY_RACK)));
        RackBayBlockEntity bay = rackBayAt(helper, bayPos);
        bay.insert(COBBLESTONE.toStack(PRELOADED), false);
        aisle.build(true);
        aisle.input(INPUT_RACK);
        ClickingPlayer player = ClickingPlayer.at(helper, aisle.rackPos(INPUT_RACK));
        player.setItemInHand(InteractionHand.MAIN_HAND, WareworksItems.RACK_UPGRADE_KIT_ANDESITE.asStack());
        Map<ItemKey, Long> conserved = ItemCensus.of(COBBLESTONE, PRELOADED, ANDESITE_KIT, 1);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the crane carries to the bay",
                player));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 1, 0))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    aisle.insertAll(aisle.handlerAt(aisle.rackPos(INPUT_RACK)), COBBLESTONE.toStack(STACK));
                    ItemCensus.change(conserved, COBBLESTONE, STACK);
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.dock().heldItems().count(COBBLESTONE) > 0
                        && aisle.dock().currentJob().isPresent(), "the crane carries cobblestone in a job"))
                .thenExecute(() -> {
                    helper.assertTrue(click(helper, player, faceHit(helper, bayPos, aisle.sideDirection(BAY_RACK)))
                            .consumesAction(), "the kit is used while the crane is on its way");
                    ItemCensus.change(conserved, ANDESITE_KIT, -1);
                    helper.assertBlockPresent(WareworksBlocks.RACK_BAY_ANDESITE.get(), bayPos);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(bay.storedCount(), PRELOADED + STACK,
                        "the crane delivered into the upgraded bay"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    aisle.assertIdleAndEmpty();
                    helper.assertTrue(blockEntityAt(helper, bayPos) == bay, "the same block entity throughout");
                })
                .thenSucceed();
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private static void placeBay(GameTestHelper helper, BlockPos pos, BlockState bay) {
        helper.setBlock(pos, bay.setValue(RackBayBlock.FACING, FACING));
    }

    private static void placeInterface(GameTestHelper helper) {
        helper.setBlock(INTERFACE.relative(FACING), Blocks.CHEST);
        helper.setBlock(INTERFACE, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, FACING));
    }

    /** A Deployer facing north at {@code pos}, turned by a creative motor east of it at {@value #TEST_RPM} RPM. */
    private static void placeDeployer(GameTestHelper helper, BlockPos pos) {
        // A Deployer facing along Z turns about X when its axis runs along the first coordinate, so the motor beside it
        // drives it through its east face.
        helper.setBlock(pos, AllBlocks.DEPLOYER.getDefaultState().setValue(DirectionalAxisKineticBlock.FACING, FACING)
                .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, true));
        BlockPos motor = pos.east();
        helper.setBlock(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING,
                Direction.WEST));
        if (helper.getLevel().getBlockEntity(helper.absolutePos(motor)) instanceof CreativeMotorBlockEntity be)
            be.generatedSpeed.setValue(TEST_RPM);
    }

    private static IItemHandler deployerHand(GameTestHelper helper, BlockPos pos) {
        IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(pos),
                null);
        if (handler == null)
            helper.fail("the Deployer exposes no item handler", pos);
        return handler;
    }

    private static BlockState stateAt(GameTestHelper helper, BlockPos pos) {
        return helper.getLevel().getBlockState(helper.absolutePos(pos));
    }

    private static BlockEntity blockEntityAt(GameTestHelper helper, BlockPos pos) {
        return helper.getLevel().getBlockEntity(helper.absolutePos(pos));
    }

    private static RackBayBlockEntity rackBayAt(GameTestHelper helper, BlockPos pos) {
        if (!(blockEntityAt(helper, pos) instanceof RackBayBlockEntity bay)) {
            helper.fail("missing rack bay block entity", pos);
            throw new IllegalStateException("unreachable");
        }
        return bay;
    }

    private static FluidBayBlockEntity fluidBayAt(GameTestHelper helper, BlockPos pos) {
        if (!(blockEntityAt(helper, pos) instanceof FluidBayBlockEntity bay)) {
            helper.fail("missing fluid bay block entity", pos);
            throw new IllegalStateException("unreachable");
        }
        return bay;
    }

    private static StorageFilterBehaviour slotAt(GameTestHelper helper, BlockPos pos) {
        FilteringBehaviour behaviour = BlockEntityBehaviour.get(helper.getLevel(), helper.absolutePos(pos),
                FilteringBehaviour.TYPE);
        if (!(behaviour instanceof StorageFilterBehaviour slot)) {
            helper.fail("no store filter slot at " + pos + ": " + behaviour, pos);
            throw new IllegalStateException("unreachable");
        }
        return slot;
    }

    /** A hit on the aisle face of a bay facing {@link #FACING}, well beside its filter slot. */
    private static BlockHitResult faceHit(GameTestHelper helper, BlockPos pos) {
        return faceHit(helper, pos, FACING);
    }

    /**
     * A hit on the aisle face of the bay at {@code pos}, which faces {@code facing}: 5 px to the side of the face's
     * centre and 4 px above it, which is well outside the filter slot's sphere (asserted).
     */
    private static BlockHitResult faceHit(GameTestHelper helper, BlockPos pos, Direction facing) {
        BlockPos absolute = helper.absolutePos(pos);
        Direction face = facing.getOpposite();
        Direction side = face.getClockWise();
        Vec3 onFace = Vec3.atCenterOf(absolute)
                .add(face.getStepX() * 0.5, 0, face.getStepZ() * 0.5)
                .add(side.getStepX() * 5 * PIXEL, 4 * PIXEL, side.getStepZ() * 5 * PIXEL);
        if (BlockEntityBehaviour.get(helper.getLevel(), absolute, FilteringBehaviour.TYPE) instanceof StorageFilterBehaviour
                slot && slot.getSlotPositioning() instanceof ValueBoxTransform.Sided sided) {
            sided.fromSide(face);
            helper.assertFalse(slot.testHit(onFace), "the hit at " + onFace + " lies beside the filter slot");
        }
        return new BlockHitResult(onFace, face, absolute, false);
    }

    /** A hit on the aisle face at the centre of the filter slot, as {@code StorageFilterClickGameTests} builds it. */
    private static BlockHitResult slotHit(GameTestHelper helper, BlockPos pos) {
        BlockPos absolute = helper.absolutePos(pos);
        StorageFilterBehaviour slot = slotAt(helper, pos);
        if (!(slot.getSlotPositioning() instanceof ValueBoxTransform.Sided sided)) {
            helper.fail("the store filter slot is not a sided value box", pos);
            throw new IllegalStateException("unreachable");
        }
        Vec3 centre = Vec3.atLowerCornerOf(absolute).add(sided.fromSide(AISLE_FACE)
                .getLocalOffset(helper.getLevel(), absolute, stateAt(helper, pos)));
        double inset = StorageFilterValueBox.FACE_INSET_PIXELS * PIXEL;
        Vec3 onFace = centre.add(AISLE_FACE.getStepX() * inset, AISLE_FACE.getStepY() * inset,
                AISLE_FACE.getStepZ() * inset);
        helper.assertTrue(slot.testHit(onFace), "the hit at " + onFace + " lies on the " + pos + " filter slot");
        return new BlockHitResult(onFace, AISLE_FACE, absolute, false);
    }

    private static BlockHitResult topHit(GameTestHelper helper, BlockPos pos) {
        BlockPos absolute = helper.absolutePos(pos);
        return new BlockHitResult(new Vec3(absolute.getX() + 0.5, absolute.getY() + 1.0, absolute.getZ() + 0.5),
                Direction.UP, absolute, false);
    }

    /** One right-click through {@code ServerPlayerGameMode#useItemOn}, the server's whole handling of a press. */
    private static InteractionResult click(GameTestHelper helper, ClickingPlayer player, BlockHitResult hit) {
        return player.gameMode.useItemOn(player, helper.getLevel(), player.getItemInHand(InteractionHand.MAIN_HAND),
                InteractionHand.MAIN_HAND, hit);
    }

    private static void assertWrongKit(GameTestHelper helper, ClickingPlayer player, BlockPos pos, ItemStack kit,
            Block expected, String what) {
        BlockEntity before = blockEntityAt(helper, pos);
        player.told.clear();
        player.setItemInHand(InteractionHand.MAIN_HAND, kit.copy());
        InteractionResult result = click(helper, player, faceHit(helper, pos));
        helper.assertTrue(result == InteractionResult.FAIL, what + " is refused: " + result);
        helper.assertBlockPresent(expected, pos);
        helper.assertTrue(blockEntityAt(helper, pos) == before, what + " leaves the block entity alone");
        helper.assertTrue(ItemStack.matches(player.getMainHandItem(), kit), what + " keeps the kit");
        assertTold(helper, player, WareworksLang.BAY_UPGRADE_FITS);
    }

    private static void assertTold(GameTestHelper helper, ClickingPlayer player, String relativeKey) {
        helper.assertTrue(player.toldKeys().contains(WareworksLang.key(relativeKey)),
                "the player was not told " + relativeKey + ": " + player.toldKeys());
    }
}
