package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;
import static dev.wareworks.gametest.WareworksGameTests.FLOOR_Y;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.handtruck.HandTruckCarry;
import dev.wareworks.content.handtruck.HandTruckItem;
import dev.wareworks.content.handtruck.HandTruckPallet;
import dev.wareworks.content.handtruck.HandTruckSlotGuard;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.storage.FluidBayBlock;
import dev.wareworks.content.storage.PalletEntity;
import dev.wareworks.content.storage.RackBayBlock;
import dev.wareworks.content.storage.RackBayBlockEntity;
import dev.wareworks.content.storage.StorageFilterBehaviour;
import dev.wareworks.content.storage.StorageFilterValueBox;
import dev.wareworks.content.storage.TieredBay;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.handtruck.HandTruckLimits;
import dev.wareworks.core.storage.BayTier;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksDataComponents;
import dev.wareworks.registry.WareworksEntityTypes;
import dev.wareworks.registry.WareworksItems;
import dev.wareworks.util.WareworksLang;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Pallets on the hand truck (ADR-084): an empty truck lifts a whole pallet, carries it under the rules of a carried
 * chest, and sets it down as a pallet or puts it whole into an empty rack bay that holds it all.
 * <p>
 * Entity clicks go through {@code Player#interactOn}, the call a {@code ServerboundInteractPacket} reaches, so the
 * {@code EntityInteract} event and every listener on it run; block clicks go through
 * {@code ServerPlayerGameMode#useItemOn} ({@link ClickingPlayer}). {@link ItemCensus} brackets every move: it counts a
 * pallet truck as the empty truck plus its item times its count, exactly as it counts a {@link PalletEntity}, so lifting,
 * carrying and both unloads conserve exactly.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class HandTruckPalletGameTests {
    private static final String CONFIG_BATCH = "wareworksHandTruckPalletConfig";
    /** Where the player stands, looking north. */
    private static final BlockPos STAND = new BlockPos(3, BASE_Y, 6);
    private static final BlockPos PALLET = new BlockPos(3, BASE_Y, 3);
    /** The floor block clicked to set a pallet down; it lands one above, at {@link #SET_DOWN}. */
    private static final BlockPos FLOOR = new BlockPos(1, FLOOR_Y, 3);
    private static final BlockPos SET_DOWN = FLOOR.above();
    private static final BlockPos BAY = new BlockPos(3, BASE_Y, 2);
    private static final BlockPos SECOND_BAY = new BlockPos(1, BASE_Y, 1);
    private static final BlockPos THIRD_BAY = new BlockPos(5, BASE_Y, 1);
    private static final BlockPos FLUID_BAY = new BlockPos(3, BASE_Y, 1);
    private static final BlockPos INTERFACE = new BlockPos(1, BASE_Y, 3);
    /** Every bay faces north, into the rack depth, so its aisle face is the south one. */
    private static final Direction FACING = Direction.NORTH;
    private static final Direction AISLE_FACE = FACING.getOpposite();

    private static final ItemKey COBBLESTONE = ItemKey.of(Items.COBBLESTONE);
    private static final ItemKey DIRT = ItemKey.of(Items.DIRT);
    private static final ItemKey DIORITE = ItemKey.of(Items.DIORITE);
    private static final int STACK = 64;
    /** A full wooden bay of cobblestone at the shipped capacity. */
    private static final int WOOD_FULL = BayTier.WOOD.defaultStacks() * STACK;
    /** A full brass bay of cobblestone at the shipped capacity. */
    private static final int BRASS_FULL = BayTier.BRASS.defaultStacks() * STACK;
    private static final int CHEST_SLOTS = 27;
    private static final double PIXEL = 1.0 / 16.0;

    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TIMEOUT_TICKS = 1600;
    private static final int SETTLE_TICKS = 60;
    private static final int TEST_RPM = 128;
    private static final RackPosition BAY_RACK = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition INPUT_RACK = new RackPosition(2, 0, Side.RIGHT);

    private HandTruckPalletGameTests() {
    }

    /**
     * A pallet of 4 096 cobblestone goes onto an empty truck and comes off it on the floor two blocks away: the pallet
     * is gone while it is carried, the truck carries the whole count under its own name, and the pallet set down
     * carries the same count again, turned towards the player. A second player clicking the lifted pallet in the same
     * tick finds it gone and gets nothing.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckPalletRoundTrip(GameTestHelper helper) {
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            PalletEntity pallet = spawn(helper, PALLET, COBBLESTONE, WOOD_FULL);
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, truck());
            Map<ItemKey, Long> census = ItemCensus.take(helper, player);

            InteractionResult lifted = player.interactOn(pallet, InteractionHand.MAIN_HAND);
            helper.assertTrue(lifted.consumesAction(), "lifting a pallet is consumed: " + lifted);
            helper.assertTrue(pallet.isRemoved(), "the lifted pallet is gone from the world");
            helper.assertTrue(pallets(helper).isEmpty(), "no pallet is left standing");
            HandTruckPallet carried = HandTruckItem.palletLoad(player.getMainHandItem());
            helper.assertTrue(carried != null, "the truck carries a pallet: " + player.getMainHandItem());
            helper.assertValueEqual(carried.item(), COBBLESTONE, "the carried item");
            helper.assertValueEqual(carried.count(), WOOD_FULL, "the carried count");
            helper.assertTrue(HandTruckItem.isLoaded(player.getMainHandItem()), "a pallet truck counts as loaded");
            helper.assertValueEqual(player.getMainHandItem().getHoverName().getString(),
                    "Hand Truck with a Pallet of Cobblestone", "the pallet truck's name");
            ItemCensus.assertEquals(helper, census, "after lifting the pallet", player);

            ClickingPlayer second = ClickingPlayer.at(helper, STAND);
            second.setItemInHand(InteractionHand.MAIN_HAND, truck());
            InteractionResult late = second.interactOn(pallet, InteractionHand.MAIN_HAND);
            helper.assertTrue(!late.consumesAction(), "a pallet already lifted is lifted again: " + late);
            helper.assertTrue(!HandTruckItem.isLoaded(second.getMainHandItem()), "the second truck stayed empty");

            InteractionResult setDown = click(helper, player, topHit(helper, FLOOR));
            helper.assertTrue(setDown.consumesAction(), "setting the pallet down is consumed: " + setDown);
            PalletEntity back = palletAt(helper, SET_DOWN);
            helper.assertValueEqual(back.carriedKey().orElse(null), COBBLESTONE, "the item on the pallet set down");
            helper.assertValueEqual(back.carriedCount(), WOOD_FULL, "the count on the pallet set down");
            helper.assertValueEqual(Mth.wrapDegrees(back.getYRot()), Mth.wrapDegrees(player.getYRot() + 180.0F),
                    "the pallet set down faces the player");
            helper.assertTrue(!HandTruckItem.isLoaded(player.getMainHandItem()), "the truck is empty again");
            helper.assertTrue(player.getMainHandItem().is(WareworksItems.HAND_TRUCK.get()), "the truck stays in hand");
            helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "nothing was dropped");
            ItemCensus.assertEquals(helper, census, "after setting the pallet down", player);
        }).thenSucceed();
    }

    /**
     * The whole load of a full brass bay goes into an empty brass bay in one click: 65 536 cobblestone, the truck empty,
     * the bay's fill level at its top step, nothing dropped and nothing gained.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckPalletIntoEmptyBay(GameTestHelper helper) {
        placeBay(helper, BAY, WareworksBlocks.RACK_BAY_BRASS.getDefaultState());
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            RackBayBlockEntity bay = rackBayAt(helper, BAY);
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, palletTruck(COBBLESTONE, BRASS_FULL));
            Map<ItemKey, Long> census = ItemCensus.take(helper, player);

            InteractionResult result = click(helper, player, topHit(helper, BAY));
            helper.assertTrue(result.consumesAction(), "the pallet goes into the bay: " + result);
            helper.assertValueEqual(bay.storedKey().orElse(null), COBBLESTONE, "the bay's item");
            helper.assertValueEqual(bay.storedCount(), BRASS_FULL, "the bay's count");
            helper.assertValueEqual(bay.fillStep(), 4, "the bay shows its top fill level");
            helper.assertValueEqual(helper.getBlockState(BAY).getValue(TieredBay.FILL),
                    4, "the block state's fill level");
            helper.assertTrue(!HandTruckItem.isLoaded(player.getMainHandItem()), "the truck is empty again");
            helper.assertTrue(pallets(helper).isEmpty() && helper.getEntities(EntityType.ITEM).isEmpty(),
                    "no pallet and no item entity appeared");
            ItemCensus.assertEquals(helper, census, "after the pallet went into the bay", player);
        }).thenSucceed();
    }

    /**
     * Every refusal at a bay changes nothing, with its own line: a bay that holds something, a filter that does not take
     * the item, a wooden bay one item too small (and the same bay taking exactly its capacity), and a fluid bay.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckPalletBayRefusals(GameTestHelper helper) {
        placeBay(helper, BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState());
        placeBay(helper, SECOND_BAY, WareworksBlocks.RACK_BAY_ANDESITE.getDefaultState());
        placeBay(helper, THIRD_BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState());
        helper.setBlock(FLUID_BAY, WareworksBlocks.FLUID_BAY_COPPER.getDefaultState().setValue(FluidBayBlock.FACING,
                FACING));
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            RackBayBlockEntity holding = rackBayAt(helper, BAY);
            holding.insert(DIRT.toStack(1), false);
            RackBayBlockEntity filtered = rackBayAt(helper, SECOND_BAY);
            helper.assertTrue(filtered.setStoreFilter(DIORITE.toStack()), "the diorite filter is set");
            RackBayBlockEntity small = rackBayAt(helper, THIRD_BAY);
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);

            assertRefused(helper, player, BAY, palletTruck(COBBLESTONE, STACK), WareworksLang.HAND_TRUCK_BAY_NOT_EMPTY);
            helper.assertValueEqual(holding.storedCount(), 1, "the bay that held dirt");
            assertRefused(helper, player, SECOND_BAY, palletTruck(COBBLESTONE, STACK),
                    WareworksLang.HAND_TRUCK_BAY_FILTERED);
            helper.assertTrue(filtered.storedKey().isEmpty(), "the filtered bay stayed empty");
            assertRefused(helper, player, THIRD_BAY, palletTruck(COBBLESTONE, WOOD_FULL + 1),
                    WareworksLang.HAND_TRUCK_BAY_TOO_SMALL);
            helper.assertTrue(small.storedKey().isEmpty(), "the small bay stayed empty");
            assertRefused(helper, player, FLUID_BAY, palletTruck(COBBLESTONE, STACK),
                    WareworksLang.HAND_TRUCK_BAY_WRONG_KIND);

            player.setItemInHand(InteractionHand.MAIN_HAND, palletTruck(COBBLESTONE, WOOD_FULL));
            Map<ItemKey, Long> census = ItemCensus.take(helper, player);
            helper.assertTrue(click(helper, player, topHit(helper, THIRD_BAY)).consumesAction(),
                    "a pallet of exactly the bay's capacity goes in");
            helper.assertValueEqual(small.storedCount(), WOOD_FULL, "the wooden bay is full");
            ItemCensus.assertEquals(helper, census, "after filling the wooden bay to its capacity", player);
        }).thenSucceed();
    }

    /**
     * A crane on its way to an empty bay with goods: the pallet is refused, because the crane would arrive at a bay
     * the player has just filled. The crane delivers, and nothing is lost on either side.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void handTruckPalletCraneBringing(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS);
        BlockPos bayPos = aisle.rackPos(BAY_RACK);
        helper.setBlock(bayPos, WareworksBlocks.RACK_BAY_BRASS.getDefaultState().setValue(RackBayBlock.FACING,
                aisle.sideDirection(BAY_RACK)));
        RackBayBlockEntity bay = rackBayAt(helper, bayPos);
        aisle.build(true);
        aisle.input(INPUT_RACK);
        ClickingPlayer player = ClickingPlayer.at(helper, aisle.rackPos(INPUT_RACK));
        player.setItemInHand(InteractionHand.MAIN_HAND, palletTruck(DIRT, STACK * 2));
        Map<ItemKey, Long> conserved = ItemCensus.of(DIRT, STACK * 2,
                ItemKey.of(WareworksItems.HAND_TRUCK.get()), 1);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the crane brings goods", player));

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
                    helper.assertTrue(bay.storedKey().isEmpty(), "precondition: the bay is still empty");
                    InteractionResult result = click(helper, player, topHit(helper, bayPos));
                    helper.assertTrue(result == InteractionResult.FAIL, "the pallet is refused: " + result);
                    assertTold(helper, player, WareworksLang.HAND_TRUCK_BAY_INCOMING);
                    helper.assertValueEqual(HandTruckItem.palletLoad(player.getMainHandItem()).count(), STACK * 2,
                            "the truck still carries the dirt");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(bay.storedCount(), STACK,
                        "the crane delivered into the bay"))
                .thenExecuteAfter(SETTLE_TICKS, aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * The unload click lands right in a bay's filter slot: the bay's slot lets a loaded truck through, the pallet goes
     * into the bay and the filter stays as it was. On a warehouse interface's slot the pallet truck does not become
     * the filter either; it sets the pallet down in front of the interface.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckPalletInsideTheValueBox(GameTestHelper helper) {
        placeBay(helper, BAY, WareworksBlocks.RACK_BAY_ANDESITE.getDefaultState());
        helper.setBlock(INTERFACE.relative(FACING), Blocks.CHEST);
        helper.setBlock(INTERFACE, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, FACING));
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            RackBayBlockEntity bay = rackBayAt(helper, BAY);
            bay.setStoreFilter(COBBLESTONE.toStack());
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, palletTruck(COBBLESTONE, 1_000));
            Map<ItemKey, Long> census = ItemCensus.take(helper, player);

            helper.assertTrue(click(helper, player, slotHit(helper, BAY)).consumesAction(),
                    "the pallet goes in through the filter slot");
            helper.assertValueEqual(bay.storedCount(), 1_000, "the bay's count");
            helper.assertTrue(ItemStack.matches(bay.storeFilter(), COBBLESTONE.toStack()),
                    "the filter is still cobblestone: " + bay.storeFilter());
            ItemCensus.assertEquals(helper, census, "after the click in the bay's filter slot", player);

            player.setItemInHand(InteractionHand.MAIN_HAND, palletTruck(DIRT, STACK));
            census = ItemCensus.take(helper, player);
            helper.assertTrue(click(helper, player, slotHit(helper, INTERFACE)).consumesAction(),
                    "the pallet is set down in front of the interface");
            helper.assertTrue(slotAt(helper, INTERFACE).getFilter().isEmpty(),
                    "the pallet truck became the interface's filter: " + slotAt(helper, INTERFACE).getFilter());
            helper.assertValueEqual(palletAt(helper, INTERFACE.relative(AISLE_FACE)).carriedCount(), STACK,
                    "the pallet in front of the interface");
            ItemCensus.assertEquals(helper, census, "after the click in the interface's filter slot", player);
        }).thenSucceed();
    }

    /**
     * A Deployer's fake player neither lifts a pallet (its truck stays empty, the pallet stays) nor unloads one: a
     * loaded truck never leaves a player, so a fake player holding one is refused outright.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckPalletFakePlayerPasses(GameTestHelper helper) {
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            PalletEntity pallet = spawn(helper, PALLET, COBBLESTONE, STACK);
            FakePlayer deployer = FakePlayerFactory.getMinecraft(helper.getLevel());
            ItemStack truck = truck();
            deployer.setItemInHand(InteractionHand.MAIN_HAND, truck);
            InteractionResult result;
            try {
                result = deployer.interactOn(pallet, InteractionHand.MAIN_HAND);
            } finally {
                deployer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
            helper.assertTrue(result == InteractionResult.PASS, "a fake player's truck passes: " + result);
            helper.assertTrue(!pallet.isRemoved() && pallet.carriedCount() == STACK, "the pallet stays as it was");
            helper.assertTrue(!HandTruckItem.isLoaded(truck), "the fake player's truck stayed empty");

            ItemStack loaded = palletTruck(COBBLESTONE, STACK);
            deployer.setItemInHand(InteractionHand.MAIN_HAND, loaded);
            try {
                result = loaded.onItemUseFirst(new UseOnContext(deployer, InteractionHand.MAIN_HAND,
                        topHit(helper, FLOOR)));
            } finally {
                deployer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
            helper.assertTrue(result == InteractionResult.FAIL, "a fake player cannot unload a pallet: " + result);
            helper.assertTrue(HandTruckItem.isLoaded(loaded), "the fake player's pallet truck stayed loaded");
            helper.assertValueEqual(pallets(helper).size(), 1, "pallets in the world");
        }).thenSucceed();
    }

    /**
     * One load per player, whichever kind: an empty truck is refused on a pallet while a chest is carried, an empty
     * truck is refused on a chest while a pallet is carried, and a pallet truck clicked on a pallet says so too.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckPalletOnlyOneLoaded(GameTestHelper helper) {
        BlockPos chestPos = new BlockPos(5, BASE_Y, 3);
        helper.setBlock(chestPos, Blocks.CHEST.defaultBlockState());
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            PalletEntity pallet = spawn(helper, PALLET, COBBLESTONE, STACK);
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.getInventory().setItem(5, chestTruck(helper));
            player.setItemInHand(InteractionHand.MAIN_HAND, truck());
            InteractionResult result = player.interactOn(pallet, InteractionHand.MAIN_HAND);
            helper.assertTrue(result == InteractionResult.FAIL, "a pallet is refused beside a chest truck: " + result);
            helper.assertTrue(!pallet.isRemoved(), "the pallet stays");
            helper.assertTrue(!HandTruckItem.isLoaded(player.getMainHandItem()), "the second truck stayed empty");
            assertTold(helper, player, WareworksLang.HAND_TRUCK_ALREADY_LOADED);

            player.told.clear();
            player.getInventory().setItem(5, palletTruck(DIRT, STACK));
            result = click(helper, player, topHit(helper, chestPos));
            helper.assertTrue(result == InteractionResult.FAIL, "a chest is refused beside a pallet truck: " + result);
            helper.assertBlockPresent(Blocks.CHEST, chestPos);
            helper.assertTrue(!HandTruckItem.isLoaded(player.getMainHandItem()), "the empty truck stayed empty");
            assertTold(helper, player, WareworksLang.HAND_TRUCK_ALREADY_LOADED);

            player.told.clear();
            player.getInventory().setItem(5, ItemStack.EMPTY);
            player.setItemInHand(InteractionHand.MAIN_HAND, palletTruck(DIRT, STACK));
            result = player.interactOn(pallet, InteractionHand.MAIN_HAND);
            helper.assertTrue(result == InteractionResult.FAIL, "a pallet truck on a pallet is refused: " + result);
            helper.assertTrue(!pallet.isRemoved() && pallet.carriedCount() == STACK, "the pallet stays as it was");
            assertTold(helper, player, WareworksLang.HAND_TRUCK_ALREADY_LOADED);
        }).thenSucceed();
    }

    /**
     * Protection: in adventure mode a pallet is neither lifted (refused with its line) nor set down (NeoForge turns the
     * click away first), and a protection mod that cancels the entity click at its own priority keeps the truck from
     * ever seeing it.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckPalletProtected(GameTestHelper helper) {
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            PalletEntity pallet = spawn(helper, PALLET, COBBLESTONE, STACK);
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setGameMode(GameType.ADVENTURE);
            player.setItemInHand(InteractionHand.MAIN_HAND, truck());
            InteractionResult result = player.interactOn(pallet, InteractionHand.MAIN_HAND);
            helper.assertTrue(result == InteractionResult.FAIL, "adventure mode lifts no pallet: " + result);
            helper.assertTrue(!pallet.isRemoved(), "the pallet stays");
            assertTold(helper, player, WareworksLang.HAND_TRUCK_NOT_ALLOWED);

            player.setItemInHand(InteractionHand.MAIN_HAND, palletTruck(DIRT, STACK));
            Map<ItemKey, Long> census = ItemCensus.take(helper, player);
            // NeoForge turns an adventure click away before the truck sees it (IItemStackExtension#onItemUseFirst passes
            // without a matching can_place_on), so nothing happens and nothing is said, as for a carried chest.
            result = click(helper, player, topHit(helper, FLOOR));
            helper.assertTrue(!result.consumesAction(), "adventure mode sets no pallet down: " + result);
            helper.assertTrue(HandTruckItem.isLoaded(player.getMainHandItem()), "the truck stays loaded");
            helper.assertTrue(pallets(helper).size() == 1, "no second pallet was set down");
            ItemCensus.assertEquals(helper, census, "after the refusals in adventure mode", player);

            player.setGameMode(GameType.SURVIVAL);
            player.setItemInHand(InteractionHand.MAIN_HAND, truck());
            Consumer<PlayerInteractEvent.EntityInteract> claim = event -> {
                if (event.getTarget() == pallet) {
                    event.setCanceled(true);
                    event.setCancellationResult(InteractionResult.FAIL);
                }
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, false, PlayerInteractEvent.EntityInteract.class, claim);
            try {
                result = player.interactOn(pallet, InteractionHand.MAIN_HAND);
            } finally {
                NeoForge.EVENT_BUS.unregister(claim);
            }
            helper.assertTrue(result == InteractionResult.FAIL, "the protection mod's answer stands: " + result);
            helper.assertTrue(!pallet.isRemoved() && pallet.carriedCount() == STACK, "the claimed pallet stays");
            helper.assertTrue(!HandTruckItem.isLoaded(player.getMainHandItem()), "the truck stayed empty");
        }).thenSucceed();
    }

    /**
     * A claim at the landing position, not at the clicked block: a protection mod cancelling the place event there
     * keeps the pallet on the truck, sets nothing down and drops nothing, exactly as for a carried chest
     * ({@code handTruckPlaceEventCancelled}).
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckPalletPlaceEventCancelled(GameTestHelper helper) {
        BlockPos refused = helper.absolutePos(SET_DOWN);
        Consumer<BlockEvent.EntityPlaceEvent> claim = event -> {
            if (event.getPos().equals(refused))
                event.setCanceled(true);
        };
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, palletTruck(COBBLESTONE, WOOD_FULL));
            Map<ItemKey, Long> census = ItemCensus.take(helper, player);

            NeoForge.EVENT_BUS.addListener(BlockEvent.EntityPlaceEvent.class, claim);
            InteractionResult result;
            try {
                result = click(helper, player, topHit(helper, FLOOR));
            } finally {
                NeoForge.EVENT_BUS.unregister(claim);
            }
            helper.assertTrue(result == InteractionResult.FAIL, "a cancelled place refuses: " + result);
            helper.assertTrue(pallets(helper).isEmpty(), "no pallet was set down");
            helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "nothing was dropped");
            HandTruckPallet carried = HandTruckItem.palletLoad(player.getMainHandItem());
            helper.assertTrue(carried != null && carried.count() == WOOD_FULL, "the truck keeps the whole pallet");
            assertTold(helper, player, WareworksLang.HAND_TRUCK_PLACE_NOT_ALLOWED);
            ItemCensus.assertEquals(helper, census, "after the refused set-down", player);

            helper.assertTrue(click(helper, player, topHit(helper, FLOOR)).consumesAction(),
                    "without the claim the same click sets it down");
            helper.assertValueEqual(palletAt(helper, SET_DOWN).carriedCount(), WOOD_FULL, "the whole count");
            ItemCensus.assertEquals(helper, census, "after setting the pallet down", player);
        }).thenSucceed();
    }

    /**
     * A player who dies carrying a pallet leaves it as a pallet with the whole count where they died, and the drops
     * hold only the empty truck: no loaded truck reaches a gravestone mod, and no item is lost.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckPalletDeathSetsDown(GameTestHelper helper) {
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            ItemStack loaded = palletTruck(COBBLESTONE, BRASS_FULL);
            Vec3 at = player.position();
            List<ItemEntity> drops = new ArrayList<>(List.of(
                    new ItemEntity(helper.getLevel(), at.x, at.y, at.z, loaded),
                    new ItemEntity(helper.getLevel(), at.x, at.y, at.z, new ItemStack(Items.BREAD, 3))));
            CommonHooks.onLivingDrops(player, helper.getLevel().damageSources().generic(), drops, false);

            List<ItemStack> dropped = drops.stream().map(ItemEntity::getItem).toList();
            helper.assertTrue(dropped.stream().noneMatch(HandTruckItem::isLoaded), "a loaded truck was dropped");
            helper.assertValueEqual(dropped.stream().filter(stack -> stack.is(WareworksItems.HAND_TRUCK.get()))
                    .count(), 1L, "empty trucks among the drops");
            helper.assertTrue(dropped.stream().noneMatch(stack -> stack.is(Items.COBBLESTONE)),
                    "the load was dropped as stacks: " + dropped);
            helper.assertTrue(dropped.stream().anyMatch(stack -> stack.is(Items.BREAD)), "other drops stay");
            PalletEntity pallet = palletAt(helper, STAND);
            helper.assertValueEqual(pallet.carriedKey().orElse(null), COBBLESTONE, "the item on the pallet left behind");
            helper.assertValueEqual(pallet.carriedCount(), BRASS_FULL, "the count on the pallet left behind");
        }).thenSucceed();
    }

    /**
     * A pallet truck that becomes an item entity over a hopper tips over on its first tick: a pallet with the whole
     * count and the empty truck lie there, no loaded truck exists anywhere, and the census, which counts the hopper's
     * slots and the pallet alike, is exactly what it was.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckPalletItemEntityTipsOver(GameTestHelper helper) {
        BlockPos hopper = new BlockPos(5, BASE_Y, 2);
        helper.setBlock(hopper, Blocks.HOPPER.defaultBlockState());
        ClickingPlayer[] holder = new ClickingPlayer[1];
        @SuppressWarnings("unchecked")
        Map<ItemKey, Long>[] expected = new Map[1];
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            holder[0] = player;
            player.setItemInHand(InteractionHand.MAIN_HAND, palletTruck(COBBLESTONE, WOOD_FULL));
            expected[0] = ItemCensus.take(helper, player);
            ItemStack loaded = player.getMainHandItem();
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            Vec3 above = helper.absoluteVec(Vec3.atCenterOf(hopper.above()));
            helper.getLevel().addFreshEntity(new ItemEntity(helper.getLevel(), above.x, above.y, above.z, loaded));
        }).thenExecuteAfter(5, () -> {
            for (ItemEntity entity : helper.getEntities(EntityType.ITEM))
                helper.assertTrue(!HandTruckItem.isLoaded(entity.getItem()), "a loaded truck still lies there");
            IItemHandler hopperItems = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                    helper.absolutePos(hopper), null);
            for (int slot = 0; slot < hopperItems.getSlots(); slot++)
                helper.assertTrue(!HandTruckItem.isLoaded(hopperItems.getStackInSlot(slot)),
                        "the hopper took a loaded truck");
            helper.assertValueEqual(pallets(helper).size(), 1, "pallets where the truck tipped over");
            ItemCensus.assertEquals(helper, expected[0], "after the pallet truck tipped over", holder[0]);
        }).thenSucceed();
    }

    /**
     * The slot guard holds for a pallet truck as for a chest truck: put into a chest slot, picked and placed or
     * Shift-clicked, it comes straight back to the player.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckPalletMenuReturns(GameTestHelper helper) {
        BlockPos target = new BlockPos(5, BASE_Y, 3);
        helper.setBlock(target, Blocks.CHEST.defaultBlockState());
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, palletTruck(COBBLESTONE, BRASS_FULL));
            Map<ItemKey, Long> census = ItemCensus.take(helper, player);
            ChestBlockEntity chest = helper.getBlockEntity(target);
            player.openMenu(chest);
            AbstractContainerMenu menu = player.containerMenu;
            helper.assertTrue(menu != player.inventoryMenu, "the chest menu opened");
            // A chest menu: 27 chest slots, then 27 main inventory slots, then the 9 hotbar slots.
            int hotbar = CHEST_SLOTS + 27;

            menu.clicked(hotbar, 0, ClickType.PICKUP, player);
            menu.clicked(0, 0, ClickType.PICKUP, player);
            menu.broadcastChanges();
            assertReturned(helper, player, chest, census, "picked and placed");

            int slot = truckSlot(player);
            menu.clicked(slot < 9 ? CHEST_SLOTS + 27 + slot : CHEST_SLOTS + slot - 9, 0, ClickType.QUICK_MOVE, player);
            menu.broadcastChanges();
            assertReturned(helper, player, chest, census, "Shift-clicked");
            player.closeContainer();
            HandTruckSlotGuard.attach(player, player.inventoryMenu);
        }).thenSucceed();
    }

    /**
     * The carried count survives a save, as {@code ItemStack.CODEC} writes a player's inventory, far above the 99 a
     * stack's own count may be, and crosses the wire unchanged. A forged count above what any bay holds is read
     * clamped and logged, and the truck is kept; a truck saved before the pallet component existed loads empty.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckPalletSurvivesSave(GameTestHelper helper) {
        LogCapture warnings = LogCapture.ofWarnings();
        helper.startSequence().thenExecute(() -> {
            RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
            ItemStack truck = palletTruck(COBBLESTONE, BRASS_FULL);
            Tag saved = ItemStack.CODEC.encodeStart(ops, truck).getOrThrow();
            ItemStack back = ItemStack.CODEC.parse(ops, saved).getOrThrow();
            helper.assertValueEqual(HandTruckItem.palletLoad(back), HandTruckItem.palletLoad(truck),
                    "the pallet after a save and a load");
            helper.assertValueEqual(HandTruckItem.palletLoad(back).count(), BRASS_FULL, "the count after a save");

            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                    helper.getLevel().registryAccess());
            ItemStack.STREAM_CODEC.encode(buffer, truck);
            ItemStack sent = ItemStack.STREAM_CODEC.decode(buffer);
            helper.assertTrue(ItemStack.matches(sent, truck), "the truck over the wire: " + sent);

            CompoundTag forged = ((CompoundTag) saved).copy();
            CompoundTag component = forged.getCompound("components").getCompound("wareworks:hand_truck_pallet");
            helper.assertTrue(component.contains("count"), "precondition: the component's shape " + forged);
            component.putInt("count", HandTruckLimits.MAX_PALLET_COUNT + 5);
            ItemStack clamped = ItemStack.CODEC.parse(ops, forged).getOrThrow();
            helper.assertTrue(clamped.is(WareworksItems.HAND_TRUCK.get()), "the forged truck is kept");
            helper.assertValueEqual(HandTruckItem.palletLoad(clamped).count(), HandTruckLimits.MAX_PALLET_COUNT,
                    "a forged count is clamped");
            component.putInt("count", -3);
            helper.assertValueEqual(HandTruckItem.palletLoad(ItemStack.CODEC.parse(ops, forged).getOrThrow()).count(),
                    1, "a forged count below one");

            CompoundTag old = new CompoundTag();
            old.putString("id", WareworksItems.HAND_TRUCK.getId().toString());
            old.putInt("count", 1);
            ItemStack oldTruck = ItemStack.CODEC.parse(ops, old).getOrThrow();
            helper.assertTrue(oldTruck.is(WareworksItems.HAND_TRUCK.get()) && !HandTruckItem.isLoaded(oldTruck),
                    "a truck saved before the pallet component loads empty");
        }).thenIdle(2).thenExecute(() -> {
            List<String> recorded = warnings.closeAndTake();
            helper.assertTrue(LogCapture.firstContaining(recorded, "hand truck's pallet", "items are lost") != null,
                    "the clamp from above is logged as a loss: " + recorded);
        }).thenSucceed();
    }

    /** A pallet whose item's components are over the configured size budget is refused with its size; nothing moves. */
    @GameTest(template = EMPTY_7X5X7, batch = CONFIG_BATCH)
    public static void handTruckPalletTooHeavy(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.handTruckMaxLoadKiB, HandTruckLimits.MIN_KIB);
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ItemStack named = new ItemStack(Items.COBBLESTONE);
            named.set(DataComponents.CUSTOM_NAME, Component.literal("heavy ".repeat(4_000)));
            ItemKey heavy = ItemKey.of(named);
            PalletEntity pallet = spawn(helper, PALLET, heavy, STACK);
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, truck());
            Map<ItemKey, Long> census = ItemCensus.take(helper, player);
            InteractionResult result = player.interactOn(pallet, InteractionHand.MAIN_HAND);
            helper.assertTrue(result == InteractionResult.FAIL, "an over-budget pallet is refused: " + result);
            helper.assertTrue(!pallet.isRemoved() && pallet.carriedCount() == STACK, "the pallet stays as it was");
            helper.assertTrue(!HandTruckItem.isLoaded(player.getMainHandItem()), "the truck stayed empty");
            assertTold(helper, player, WareworksLang.HAND_TRUCK_TOO_HEAVY);
            ItemCensus.assertEquals(helper, census, "after the refusal", player);
        }).thenSucceed();
    }

    @AfterBatch(batch = CONFIG_BATCH)
    public static void restoreHandTruckPalletConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    /** A pallet truck anywhere in the inventory slows its player and stops gliding and sprinting, as a chest truck. */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckPalletPenaltyFlag(GameTestHelper helper) {
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.getInventory().setItem(20, palletTruck(COBBLESTONE, STACK));
            player.getInventory().tick();
            helper.assertTrue(HandTruckCarry.isPenalised(player), "a pallet truck in the inventory slows the player");
            player.getInventory().setItem(20, ItemStack.EMPTY);
        }).thenSucceed();
    }

    /** A pallet truck is no item for a rack bay's handler or a warehouse input's buffer: both refuse it. */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckPalletRefusedByBayAndBuffer(GameTestHelper helper) {
        BlockPos input = new BlockPos(5, BASE_Y, 1);
        placeBay(helper, BAY, WareworksBlocks.RACK_BAY_BRASS.getDefaultState());
        helper.setBlock(input, WareworksBlocks.WAREHOUSE_INPUT.getDefaultState());
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ItemStack loaded = palletTruck(COBBLESTONE, STACK);
            helper.assertTrue(!loaded.canFitInsideContainerItems(), "a pallet truck fits into container items");
            for (BlockPos pos : List.of(BAY, input)) {
                IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                        helper.absolutePos(pos), null);
                helper.assertTrue(handler != null, "precondition: an item handler at " + pos);
                for (int slot = 0; slot < handler.getSlots(); slot++) {
                    helper.assertTrue(!handler.isItemValid(slot, loaded), pos + " calls a pallet truck valid");
                    helper.assertValueEqual(handler.insertItem(slot, loaded.copy(), false).getCount(), 1,
                            "items of a pallet truck " + pos + " took");
                }
            }
            helper.assertTrue(rackBayAt(helper, BAY).storedKey().isEmpty(), "the bay stayed empty");
        }).thenSucceed();
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private static ItemStack truck() {
        return new ItemStack(WareworksItems.HAND_TRUCK.get());
    }

    /** A truck carrying a pallet of {@code count} items of {@code key}, as a lift would leave it. */
    private static ItemStack palletTruck(ItemKey key, int count) {
        ItemStack truck = truck();
        truck.set(WareworksDataComponents.HAND_TRUCK_PALLET.get(), new HandTruckPallet(key, count));
        return truck;
    }

    /** A truck carrying a chest with a little in it, taken with a real click from a chest placed for it. */
    private static ItemStack chestTruck(GameTestHelper helper) {
        BlockPos chestPos = new BlockPos(1, BASE_Y, 5);
        helper.setBlock(chestPos, Blocks.CHEST.defaultBlockState());
        ((ChestBlockEntity) helper.getBlockEntity(chestPos)).setItem(0, new ItemStack(Items.IRON_INGOT, 3));
        ClickingPlayer loader = ClickingPlayer.at(helper, STAND);
        loader.setItemInHand(InteractionHand.MAIN_HAND, truck());
        helper.assertTrue(click(helper, loader, topHit(helper, chestPos)).consumesAction(), "loading the chest");
        return loader.getMainHandItem();
    }

    private static PalletEntity spawn(GameTestHelper helper, BlockPos pos, ItemKey key, int load) {
        helper.assertTrue(PalletEntity.spawn(helper.getLevel(), helper.absolutePos(pos), key, load),
                "the level accepted a pallet at " + pos);
        return palletAt(helper, pos);
    }

    private static List<PalletEntity> pallets(GameTestHelper helper) {
        return helper.getEntities(WareworksEntityTypes.PALLET.get());
    }

    /** The one pallet standing in the block at the test-relative {@code pos}. */
    private static PalletEntity palletAt(GameTestHelper helper, BlockPos pos) {
        BlockPos absolute = helper.absolutePos(pos);
        List<PalletEntity> found = pallets(helper).stream()
                .filter(pallet -> pallet.blockPosition().equals(absolute)).toList();
        if (found.size() != 1) {
            helper.fail("exactly one pallet must stand at " + pos + ", found " + found.size() + " of "
                    + pallets(helper), pos);
            throw new IllegalStateException("unreachable");
        }
        return found.get(0);
    }

    private static void placeBay(GameTestHelper helper, BlockPos pos, BlockState bay) {
        helper.setBlock(pos, bay.setValue(RackBayBlock.FACING, FACING));
    }

    private static RackBayBlockEntity rackBayAt(GameTestHelper helper, BlockPos pos) {
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(pos)) instanceof RackBayBlockEntity bay)) {
            helper.fail("missing rack bay block entity", pos);
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

    /** A hit on the aisle face at the centre of the filter slot, as {@code StorageFilterClickGameTests} builds it. */
    private static BlockHitResult slotHit(GameTestHelper helper, BlockPos pos) {
        BlockPos absolute = helper.absolutePos(pos);
        StorageFilterBehaviour slot = slotAt(helper, pos);
        if (!(slot.getSlotPositioning() instanceof ValueBoxTransform.Sided sided)) {
            helper.fail("the store filter slot is not a sided value box", pos);
            throw new IllegalStateException("unreachable");
        }
        Vec3 centre = Vec3.atLowerCornerOf(absolute).add(sided.fromSide(AISLE_FACE)
                .getLocalOffset(helper.getLevel(), absolute, helper.getLevel().getBlockState(absolute)));
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

    /** A refused unload on the bay at {@code pos}: {@code FAIL}, the truck unchanged, the census unchanged, the line. */
    private static void assertRefused(GameTestHelper helper, ClickingPlayer player, BlockPos pos, ItemStack truck,
            String relativeKey) {
        player.told.clear();
        player.setItemInHand(InteractionHand.MAIN_HAND, truck.copy());
        Map<ItemKey, Long> census = ItemCensus.take(helper, player);
        InteractionResult result = click(helper, player, topHit(helper, pos));
        helper.assertTrue(result == InteractionResult.FAIL, relativeKey + " refuses: " + result);
        helper.assertTrue(ItemStack.matches(player.getMainHandItem(), truck), relativeKey + " keeps the truck as it was");
        helper.assertTrue(pallets(helper).isEmpty(), relativeKey + " set a pallet down");
        assertTold(helper, player, relativeKey);
        ItemCensus.assertEquals(helper, census, "after the refusal " + relativeKey, player);
    }

    private static void assertTold(GameTestHelper helper, ClickingPlayer player, String relativeKey) {
        helper.assertTrue(player.toldKeys().contains(WareworksLang.key(relativeKey)),
                "the player was not told " + relativeKey + ": " + player.toldKeys());
    }

    /** The player inventory slot that holds the loaded truck, or -1. */
    private static int truckSlot(ClickingPlayer player) {
        for (int slot = 0; slot < player.getInventory().items.size(); slot++)
            if (HandTruckItem.isLoaded(player.getInventory().items.get(slot)))
                return slot;
        return -1;
    }

    /** After a click into the chest: the chest holds no loaded truck, the truck is back, the census unchanged. */
    private static void assertReturned(GameTestHelper helper, ClickingPlayer player, ChestBlockEntity chest,
            Map<ItemKey, Long> census, String how) {
        for (int slot = 0; slot < chest.getContainerSize(); slot++)
            helper.assertTrue(!HandTruckItem.isLoaded(chest.getItem(slot)), "the chest kept a pallet truck " + how);
        helper.assertTrue(truckSlot(player) >= 0 || HandTruckItem.isLoaded(player.containerMenu.getCarried()),
                "the pallet truck is not back with the player after it was " + how);
        assertTold(helper, player, WareworksLang.HAND_TRUCK_STAYS_WITH_YOU);
        ItemCensus.assertEquals(helper, census, "after the pallet truck was " + how, player);
    }
}
