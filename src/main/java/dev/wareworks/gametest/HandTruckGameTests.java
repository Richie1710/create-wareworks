package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;
import static dev.wareworks.gametest.WareworksGameTests.FLOOR_Y;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.simibubi.create.AllBlocks;

import dev.wareworks.Wareworks;
import dev.wareworks.content.handtruck.HandTruckCarry;
import dev.wareworks.content.handtruck.HandTruckItem;
import dev.wareworks.content.handtruck.HandTruckLoad;
import dev.wareworks.content.handtruck.HandTruckRules;
import dev.wareworks.content.handtruck.HandTruckSlotGuard;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksDataComponents;
import dev.wareworks.registry.WareworksItems;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * The hand truck (issue #37, ADR-070): loading and unloading through the server's real click dispatch
 * ({@link ClickingPlayer}, {@code ServerPlayerGameMode#useItemOn}), what may be carried and by whom, and every way a
 * loaded truck could leave its player's inventory.
 * <p>
 * {@link ItemCensus} brackets every load and unload. It counts a loaded truck as the empty truck plus what the carried
 * container holds and the carried container itself as nothing — a block before, on and after the truck — so a round
 * trip conserves exactly, and a truck that tips over adds exactly one block item, which the test declares.
 * <p>
 * Every case is a few clicks within a tick or two, and the player is never added to the level, so no other test sees
 * it. The size budget test changes a server config value and runs in its own batch ({@link ConfigOverrides}).
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class HandTruckGameTests {
    private static final String CONFIG_BATCH = "wareworksHandTruckConfig";
    /** Where the player stands, looking north: the containers are set down facing south, towards them. */
    private static final BlockPos STAND = new BlockPos(3, BASE_Y, 6);
    private static final BlockPos CHEST = new BlockPos(3, BASE_Y, 3);
    /** The floor block clicked to set a container down; it lands one above, at {@link #SET_DOWN}. */
    private static final BlockPos FLOOR = new BlockPos(1, FLOOR_Y, 3);
    private static final BlockPos SET_DOWN = FLOOR.above();
    private static final String NAME = "Ore Crate";
    private static final String LOCK = "brass key";
    private static final int CHEST_SLOTS = 27;

    private HandTruckGameTests() {
    }

    /**
     * A named, locked chest with mixed stacks — an enchanted sword, a shulker box with contents inside — goes onto the
     * truck and comes back exactly: the block is gone without a single drop, the truck carries the chest's save data,
     * and the chest set down two blocks away faces the player and saves the very same data again.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckChestRoundTrip(GameTestHelper helper) {
        helper.setBlock(CHEST, Blocks.CHEST.defaultBlockState());
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ChestBlockEntity chest = helper.getBlockEntity(CHEST);
            chest.setItem(0, new ItemStack(Items.DIAMOND, 64));
            chest.setItem(4, enchantedSword(helper));
            chest.setItem(9, filledShulkerBox());
            chest.setItem(26, new ItemStack(Items.COBBLESTONE, 17));
            nameAndLock(helper, chest);
            CompoundTag before = chest.saveWithoutMetadata(helper.getLevel().registryAccess());

            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, truck());
            Map<ItemKey, Long> census = ItemCensus.take(helper, player);

            InteractionResult loaded = click(helper, player, CHEST);
            helper.assertTrue(loaded.consumesAction(), "loading a chest is consumed: " + loaded);
            helper.assertBlockNotPresent(Blocks.CHEST, CHEST);
            helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "loading dropped items");
            HandTruckLoad load = HandTruckItem.load(player.getMainHandItem());
            helper.assertTrue(load != null, "the truck is loaded: " + player.getMainHandItem());
            helper.assertValueEqual(load.dataCopy(), before, "the carried save data");
            helper.assertTrue(load.state().is(Blocks.CHEST), "the carried block: " + load.state());
            helper.assertValueEqual(player.getMainHandItem().getHoverName().getString(), "Hand Truck with " + NAME,
                    "the loaded truck's name");
            ItemCensus.assertEquals(helper, census, "after loading", player);

            InteractionResult unloaded = click(helper, player, FLOOR);
            helper.assertTrue(unloaded.consumesAction(), "setting the chest down is consumed: " + unloaded);
            BlockState placed = helper.getBlockState(SET_DOWN);
            helper.assertTrue(placed.is(Blocks.CHEST), "a chest stands where the truck set it down: " + placed);
            helper.assertValueEqual(placed.getValue(ChestBlock.FACING), Direction.SOUTH,
                    "the chest faces the player, who looks north");
            ChestBlockEntity back = helper.getBlockEntity(SET_DOWN);
            helper.assertValueEqual(back.saveWithoutMetadata(helper.getLevel().registryAccess()), before,
                    "the chest's save data after the round trip (items, name, lock)");
            helper.assertValueEqual(back.getCustomName().getString(), NAME, "the chest's name");
            helper.assertTrue(!HandTruckItem.isLoaded(player.getMainHandItem()), "the truck is empty again");
            helper.assertTrue(player.getMainHandItem().is(WareworksItems.HAND_TRUCK.get()), "the truck stays in hand");
            ItemCensus.assertEquals(helper, census, "after setting it down", player);
        }).thenSucceed();
    }

    /**
     * An unopened loot chest travels with its loot table and seed and nothing rolled: the loot is decided by whoever
     * first opens it after it was set down, exactly as before.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckUnopenedLoot(GameTestHelper helper) {
        helper.setBlock(CHEST, Blocks.CHEST.defaultBlockState());
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ChestBlockEntity chest = helper.getBlockEntity(CHEST);
            chest.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON, 42L);
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, truck());

            helper.assertTrue(click(helper, player, CHEST).consumesAction(), "loading the loot chest");
            helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "loading rolled and dropped loot");
            HandTruckLoad load = HandTruckItem.load(player.getMainHandItem());
            helper.assertTrue(load != null && load.hasUnrolledLoot(), "the truck carries an unrolled loot table");
            helper.assertValueEqual(load.stackCount(), 0, "items on the truck");

            helper.assertTrue(click(helper, player, FLOOR).consumesAction(), "setting the loot chest down");
            ChestBlockEntity back = helper.getBlockEntity(SET_DOWN);
            // Read through the save data: getItem would roll the loot.
            CompoundTag saved = back.saveWithoutMetadata(helper.getLevel().registryAccess());
            helper.assertValueEqual(saved.getString("LootTable"), BuiltInLootTables.SIMPLE_DUNGEON.location().toString(),
                    "the loot table after the round trip");
            helper.assertValueEqual(saved.getLong("LootTableSeed"), 42L, "the loot seed after the round trip");
            helper.assertTrue(saved.getList("Items", Tag.TAG_COMPOUND).isEmpty(), "no loot was rolled: " + saved);
            helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "unloading dropped items");
        }).thenSucceed();
    }

    /**
     * One half of a double chest: the truck takes only that half's own 27 slots, the other half becomes a single
     * chest with its 27, and the half set down elsewhere has its 27 back.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckDoubleChestHalf(GameTestHelper helper) {
        BlockPos left = CHEST;
        // A LEFT half facing south joins the chest to its west (ChestBlock#getConnectedDirection).
        BlockPos right = CHEST.west();
        BlockState chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH);
        helper.setBlock(left, chest.setValue(ChestBlock.TYPE, ChestType.LEFT));
        helper.setBlock(right, chest.setValue(ChestBlock.TYPE, ChestType.RIGHT));
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            List<Item> items = distinctItems(2 * CHEST_SLOTS);
            ChestBlockEntity leftChest = helper.getBlockEntity(left);
            ChestBlockEntity rightChest = helper.getBlockEntity(right);
            for (int slot = 0; slot < CHEST_SLOTS; slot++) {
                leftChest.setItem(slot, new ItemStack(items.get(slot)));
                rightChest.setItem(slot, new ItemStack(items.get(CHEST_SLOTS + slot)));
            }
            List<ItemStack> leftBefore = contents(leftChest);
            List<ItemStack> rightBefore = contents(rightChest);
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, truck());
            Map<ItemKey, Long> census = ItemCensus.take(helper, player);

            helper.assertTrue(click(helper, player, left).consumesAction(), "loading one half of a double chest");
            BlockState other = helper.getBlockState(right);
            helper.assertTrue(other.is(Blocks.CHEST) && other.getValue(ChestBlock.TYPE) == ChestType.SINGLE,
                    "the other half is a single chest now: " + other);
            assertStacks(helper, contents(helper.getBlockEntity(right)), rightBefore, "the half left standing");
            helper.assertValueEqual(HandTruckItem.load(player.getMainHandItem()).stackCount(), CHEST_SLOTS,
                    "stacks on the truck: only the clicked half's own");
            ItemCensus.assertEquals(helper, census, "after loading one half", player);

            BlockPos away = new BlockPos(5, FLOOR_Y, 1);
            helper.assertTrue(click(helper, player, away).consumesAction(), "setting the half down");
            assertStacks(helper, contents(helper.getBlockEntity(away.above())), leftBefore, "the half set down");
            ItemCensus.assertEquals(helper, census, "after setting the half down", player);
        }).thenSucceed();
    }

    /** A barrel goes and comes back; it is set down closed, whatever it was when it was taken. */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckBarrelRoundTrip(GameTestHelper helper) {
        helper.setBlock(CHEST, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.OPEN, true));
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            BarrelBlockEntity barrel = helper.getBlockEntity(CHEST);
            barrel.setItem(3, new ItemStack(Items.WHEAT, 40));
            barrel.setItem(20, new ItemStack(Items.CARROT, 9));
            List<ItemStack> before = contents(barrel);
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, truck());
            Map<ItemKey, Long> census = ItemCensus.take(helper, player);

            helper.assertTrue(click(helper, player, CHEST).consumesAction(), "loading the barrel");
            helper.assertBlockNotPresent(Blocks.BARREL, CHEST);
            ItemCensus.assertEquals(helper, census, "after loading the barrel", player);
            helper.assertTrue(click(helper, player, FLOOR).consumesAction(), "setting the barrel down");
            BlockState placed = helper.getBlockState(SET_DOWN);
            helper.assertTrue(placed.is(Blocks.BARREL) && !placed.getValue(BarrelBlock.OPEN),
                    "a closed barrel stands there: " + placed);
            assertStacks(helper, contents(helper.getBlockEntity(SET_DOWN)), before, "the barrel set down");
            ItemCensus.assertEquals(helper, census, "after setting the barrel down", player);
        }).thenSucceed();
    }

    /**
     * What the tags deny or the server refuses is never loaded and stays as it was: the ender chest (it opens, as with
     * any other item), a shulker box, a warehouse input, Create's depot, item vault and toolbox. The rule's own
     * container requirement is checked on its own as well: a vault's block entity behind a state the carriable tag
     * accepts (as when a pack tags a vault) is still refused, because it is no plain {@link Container}.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckDeniedBlocks(GameTestHelper helper) {
        List<BlockPos> positions = List.of(new BlockPos(1, BASE_Y, 2), new BlockPos(2, BASE_Y, 2),
                new BlockPos(4, BASE_Y, 2), new BlockPos(5, BASE_Y, 2), new BlockPos(1, BASE_Y, 1),
                new BlockPos(5, BASE_Y, 1));
        List<BlockState> states = List.of(Blocks.ENDER_CHEST.defaultBlockState(),
                Blocks.SHULKER_BOX.defaultBlockState(), WareworksBlocks.WAREHOUSE_INPUT.getDefaultState(),
                AllBlocks.DEPOT.getDefaultState(), AllBlocks.ITEM_VAULT.getDefaultState(),
                AllBlocks.TOOLBOXES.get(DyeColor.BROWN).getDefaultState());
        for (int i = 0; i < positions.size(); i++)
            helper.setBlock(positions.get(i), states.get(i));
        helper.setBlock(CHEST, Blocks.CHEST.defaultBlockState());
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            BlockPos vault = helper.absolutePos(positions.get(4));
            BlockEntity vaultEntity = helper.getLevel().getBlockEntity(vault);
            helper.assertTrue(vaultEntity != null && !(vaultEntity instanceof Container),
                    "the vault's block entity is no plain container: " + vaultEntity);
            BlockState chestState = Blocks.CHEST.defaultBlockState();
            helper.assertTrue(HandTruckRules.isCarriableByTag(chestState), "a chest is carriable by tag");
            helper.assertTrue(!HandTruckRules.isCarriable(helper.getLevel(), vault, chestState, vaultEntity),
                    "a carriable-tagged block whose block entity is no container would be carried");
            helper.assertTrue(HandTruckRules.isCarriable(helper.getLevel(), helper.absolutePos(CHEST), chestState,
                    helper.getBlockEntity(CHEST)), "a real chest is refused");

            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            for (int i = 0; i < positions.size(); i++) {
                ItemStack truck = truck();
                player.setItemInHand(InteractionHand.MAIN_HAND, truck);
                // Per click: an empty truck is a plain item, so the depot may simply take it onto its surface.
                Map<ItemKey, Long> census = ItemCensus.take(helper, player);
                click(helper, player, positions.get(i));
                if (player.containerMenu != player.inventoryMenu)
                    player.closeContainer();
                helper.assertTrue(helper.getBlockState(positions.get(i)).is(states.get(i).getBlock()),
                        states.get(i) + " is still there");
                helper.assertTrue(!HandTruckItem.isLoaded(truck) && !HandTruckItem.holdsLoadedTruck(player),
                        "the truck took " + states.get(i));
                helper.assertBlockPresent(states.get(i).getBlock(), positions.get(i));
                helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), states.get(i) + " dropped items");
                ItemCensus.assertEquals(helper, census, "after clicking " + states.get(i), player);
            }
        }).thenSucceed();
    }

    /**
     * A forged load (a creative client can send any stack with any components) is checked again on unload: a command
     * block with a command in it, and a chest whose load names a command block's block entity, are never set down,
     * the position stays air and the truck stays as it was.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckForgedLoadRefused(GameTestHelper helper) {
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            CompoundTag command = new CompoundTag();
            command.putString("Command", "op wareworks-click");
            command.putBoolean("auto", true);
            ResourceLocation commandBlock = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(BlockEntityType.COMMAND_BLOCK);
            List<HandTruckLoad> forged = List.of(
                    new HandTruckLoad(Blocks.COMMAND_BLOCK.defaultBlockState(), commandBlock, command),
                    new HandTruckLoad(Blocks.CHEST.defaultBlockState(), commandBlock, command));
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            for (HandTruckLoad load : forged) {
                ItemStack truck = truck();
                truck.set(WareworksDataComponents.HAND_TRUCK_LOAD.get(), load);
                player.setItemInHand(InteractionHand.MAIN_HAND, truck);
                InteractionResult result = click(helper, player, FLOOR);
                helper.assertTrue(!result.consumesAction(), "a forged load was set down: " + load + " -> " + result);
                helper.assertBlockPresent(Blocks.AIR, SET_DOWN);
                helper.assertTrue(HandTruckItem.isLoaded(player.getMainHandItem()), "the forged truck was emptied");
                helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "a forged load dropped items");
            }
        }).thenSucceed();
    }

    /** A protection mod cancelling the break event keeps the chest where it is; so does adventure mode. */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckBreakEventCancelled(GameTestHelper helper) {
        BlockPos adventure = new BlockPos(5, BASE_Y, 3);
        helper.setBlock(CHEST, Blocks.CHEST.defaultBlockState());
        helper.setBlock(adventure, Blocks.CHEST.defaultBlockState());
        BlockPos protectedPos = helper.absolutePos(CHEST);
        Consumer<BlockEvent.BreakEvent> claim = event -> {
            if (event.getPos().equals(protectedPos))
                event.setCanceled(true);
        };
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ChestBlockEntity chest = helper.getBlockEntity(CHEST);
            chest.setItem(0, new ItemStack(Items.GOLD_INGOT, 5));
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, truck());
            NeoForge.EVENT_BUS.addListener(BlockEvent.BreakEvent.class, claim);
            InteractionResult result;
            try {
                result = click(helper, player, CHEST);
            } finally {
                NeoForge.EVENT_BUS.unregister(claim);
            }
            helper.assertTrue(result == InteractionResult.FAIL, "a cancelled break refuses the truck: " + result);
            helper.assertBlockPresent(Blocks.CHEST, CHEST);
            helper.assertValueEqual(((ChestBlockEntity) helper.getBlockEntity(CHEST)).getItem(0).getCount(), 5,
                    "the protected chest's gold");
            helper.assertTrue(!HandTruckItem.isLoaded(player.getMainHandItem()), "the truck stayed empty");
            assertTold(helper, player, WareworksLang.HAND_TRUCK_NOT_ALLOWED);

            player.setGameMode(GameType.ADVENTURE);
            click(helper, player, adventure);
            if (player.containerMenu != player.inventoryMenu)
                player.closeContainer();
            helper.assertBlockPresent(Blocks.CHEST, adventure);
            helper.assertTrue(!HandTruckItem.isLoaded(player.getMainHandItem()), "the truck stayed empty in adventure");
        }).thenSucceed();
    }

    /**
     * A protection mod cancelling the place event leaves nothing standing and nothing dropped, and the truck stays
     * loaded: the contents are only loaded into the block once the event has passed.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckPlaceEventCancelled(GameTestHelper helper) {
        helper.setBlock(CHEST, Blocks.CHEST.defaultBlockState());
        BlockPos refused = helper.absolutePos(SET_DOWN);
        Consumer<BlockEvent.EntityPlaceEvent> claim = event -> {
            if (event.getPos().equals(refused))
                event.setCanceled(true);
        };
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ChestBlockEntity chest = helper.getBlockEntity(CHEST);
            chest.setItem(0, new ItemStack(Items.EMERALD, 12));
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, truck());
            Map<ItemKey, Long> census = ItemCensus.take(helper, player);
            helper.assertTrue(click(helper, player, CHEST).consumesAction(), "loading the chest");

            NeoForge.EVENT_BUS.addListener(BlockEvent.EntityPlaceEvent.class, claim);
            InteractionResult result;
            try {
                result = click(helper, player, FLOOR);
            } finally {
                NeoForge.EVENT_BUS.unregister(claim);
            }
            helper.assertTrue(result == InteractionResult.FAIL, "a cancelled place refuses: " + result);
            helper.assertBlockNotPresent(Blocks.CHEST, SET_DOWN);
            helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "the refused place dropped items");
            helper.assertTrue(HandTruckItem.isLoaded(player.getMainHandItem()), "the truck is still loaded");
            assertTold(helper, player, WareworksLang.HAND_TRUCK_PLACE_NOT_ALLOWED);
            ItemCensus.assertEquals(helper, census, "after the refused place", player);
        }).thenSucceed();
    }

    /** One loaded truck per player: a second, empty one is refused while the first is carried. */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckOnlyOneLoaded(GameTestHelper helper) {
        BlockPos second = new BlockPos(5, BASE_Y, 3);
        helper.setBlock(CHEST, Blocks.CHEST.defaultBlockState());
        helper.setBlock(second, Blocks.CHEST.defaultBlockState());
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, truck());
            helper.assertTrue(click(helper, player, CHEST).consumesAction(), "loading the first chest");
            ItemStack first = player.getMainHandItem();
            player.getInventory().setItem(5, first);
            player.setItemInHand(InteractionHand.MAIN_HAND, truck());

            InteractionResult result = click(helper, player, second);
            helper.assertTrue(result == InteractionResult.FAIL, "a second load is refused: " + result);
            helper.assertBlockPresent(Blocks.CHEST, second);
            helper.assertTrue(!HandTruckItem.isLoaded(player.getMainHandItem()), "the second truck stayed empty");
            assertTold(helper, player, WareworksLang.HAND_TRUCK_ALREADY_LOADED);
        }).thenSucceed();
    }

    /** A Deployer's fake player never loads a truck: what it holds leaves through funnels. */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckFakePlayerPasses(GameTestHelper helper) {
        helper.setBlock(CHEST, Blocks.CHEST.defaultBlockState());
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            FakePlayer deployer = FakePlayerFactory.getMinecraft(helper.getLevel());
            ItemStack truck = truck();
            deployer.setItemInHand(InteractionHand.MAIN_HAND, truck);
            InteractionResult result = truck.onItemUseFirst(new UseOnContext(deployer, InteractionHand.MAIN_HAND,
                    topHit(helper.absolutePos(CHEST))));
            deployer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            helper.assertTrue(result == InteractionResult.PASS, "a fake player's truck passes: " + result);
            helper.assertBlockPresent(Blocks.CHEST, CHEST);
            helper.assertTrue(!HandTruckItem.isLoaded(truck), "the fake player's truck stayed empty");
        }).thenSucceed();
    }

    /** A chest over the configured size budget is refused with its size; nothing moves. */
    @GameTest(template = EMPTY_7X5X7, batch = CONFIG_BATCH)
    public static void handTruckTooHeavy(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.handTruckMaxLoadKiB, 16);
        helper.setBlock(CHEST, Blocks.CHEST.defaultBlockState());
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ChestBlockEntity chest = helper.getBlockEntity(CHEST);
            for (int slot = 0; slot < 3; slot++)
                chest.setItem(slot, heavyBook(slot));
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, truck());
            Map<ItemKey, Long> census = ItemCensus.take(helper, player);
            InteractionResult result = click(helper, player, CHEST);
            helper.assertTrue(result == InteractionResult.FAIL, "an over-budget chest is refused: " + result);
            helper.assertBlockPresent(Blocks.CHEST, CHEST);
            helper.assertTrue(!HandTruckItem.isLoaded(player.getMainHandItem()), "the truck stayed empty");
            assertTold(helper, player, WareworksLang.HAND_TRUCK_TOO_HEAVY);
            ItemCensus.assertEquals(helper, census, "after the refusal", player);
        }).thenSucceed();
    }

    @AfterBatch(batch = CONFIG_BATCH)
    public static void restoreHandTruckConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    /**
     * A loaded truck put into a chest slot — picked and placed, Shift-clicked, swapped in with a hotbar key — and into
     * the 2x2 crafting grid of the player's own inventory comes straight back to the player in the click's own
     * broadcast; the chest never keeps it.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckMenuReturns(GameTestHelper helper) {
        BlockPos target = new BlockPos(5, BASE_Y, 3);
        helper.setBlock(CHEST, Blocks.CHEST.defaultBlockState());
        helper.setBlock(target, Blocks.CHEST.defaultBlockState());
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ChestBlockEntity carried = helper.getBlockEntity(CHEST);
            carried.setItem(0, new ItemStack(Items.IRON_INGOT, 30));
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, truck());
            helper.assertTrue(click(helper, player, CHEST).consumesAction(), "loading the chest");
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

            int slot = truckSlot(helper, player);
            menu.clicked(menuSlotOf(slot), 0, ClickType.QUICK_MOVE, player);
            menu.broadcastChanges();
            assertReturned(helper, player, chest, census, "Shift-clicked");

            slot = truckSlot(helper, player);
            helper.assertTrue(slot < 9, "the truck is on the hotbar for the swap: slot " + slot);
            menu.clicked(0, slot, ClickType.SWAP, player);
            menu.broadcastChanges();
            assertReturned(helper, player, chest, census, "swapped in with a hotbar key");
            player.closeContainer();

            // The player's own inventory menu: its 2x2 crafting grid is a foreign container.
            HandTruckSlotGuard.attach(player, player.inventoryMenu);
            slot = truckSlot(helper, player);
            AbstractContainerMenu inventory = player.inventoryMenu;
            inventory.clicked(slot < 9 ? 36 + slot : slot, 0, ClickType.PICKUP, player);
            inventory.clicked(1, 0, ClickType.PICKUP, player);
            inventory.broadcastChanges();
            helper.assertTrue(inventory.getSlot(1).getItem().isEmpty(), "the crafting grid kept the loaded truck");
            helper.assertTrue(truckSlot(helper, player) >= 0, "the truck is back in the inventory");
            ItemCensus.assertEquals(helper, census, "after the crafting grid", player);
        }).thenSucceed();
    }

    /**
     * A loaded truck fits into no container item and no Wareworks handler: a shulker box face, a bundle and the
     * warehouse input's buffer all refuse it, while the empty truck is an ordinary item.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckRefusedByContainerItems(GameTestHelper helper) {
        BlockPos shulker = new BlockPos(1, BASE_Y, 1);
        BlockPos input = new BlockPos(5, BASE_Y, 1);
        helper.setBlock(CHEST, Blocks.CHEST.defaultBlockState());
        helper.setBlock(shulker, Blocks.SHULKER_BOX.defaultBlockState());
        helper.setBlock(input, WareworksBlocks.WAREHOUSE_INPUT.getDefaultState());
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, truck());
            helper.assertTrue(click(helper, player, CHEST).consumesAction(), "loading the chest");
            ItemStack loaded = player.getMainHandItem().copy();
            helper.assertTrue(HandTruckItem.isLoaded(loaded), "precondition: a loaded truck");

            helper.assertTrue(!loaded.canFitInsideContainerItems(), "a loaded truck fits into container items");
            helper.assertTrue(truck().canFitInsideContainerItems(), "an empty truck is an ordinary item");
            ShulkerBoxBlockEntity box = helper.getBlockEntity(shulker);
            helper.assertTrue(!box.canPlaceItemThroughFace(0, loaded, Direction.UP),
                    "a hopper could put a loaded truck into a shulker box");
            BundleContents.Mutable bundle = new BundleContents.Mutable(BundleContents.EMPTY);
            helper.assertValueEqual(bundle.tryInsert(loaded.copy()), 0, "items of a loaded truck a bundle took");
            IItemHandler buffer = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                    helper.absolutePos(input), null);
            helper.assertTrue(buffer != null, "precondition: the warehouse input has an item handler");
            for (int slot = 0; slot < buffer.getSlots(); slot++) {
                helper.assertTrue(!buffer.isItemValid(slot, loaded), "the input calls a loaded truck valid");
                helper.assertValueEqual(buffer.insertItem(slot, loaded.copy(), false).getCount(), 1,
                        "a loaded truck inserted into the warehouse input");
            }
        }).thenSucceed();
    }

    /**
     * A loaded truck that becomes an item entity over a hopper tips over on its first tick: no loaded truck exists
     * anywhere afterwards, the hopper holds only plain items, and the census has gained exactly the chest's block item.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckItemEntityTipsOver(GameTestHelper helper) {
        BlockPos hopper = new BlockPos(5, BASE_Y, 2);
        helper.setBlock(CHEST, Blocks.CHEST.defaultBlockState());
        helper.setBlock(hopper, Blocks.HOPPER.defaultBlockState());
        ClickingPlayer[] holder = new ClickingPlayer[1];
        @SuppressWarnings("unchecked")
        Map<ItemKey, Long>[] expected = new Map[1];
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ChestBlockEntity chest = helper.getBlockEntity(CHEST);
            chest.setItem(0, new ItemStack(Items.REDSTONE, 50));
            chest.setItem(1, new ItemStack(Items.LAPIS_LAZULI, 3));
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            holder[0] = player;
            player.setItemInHand(InteractionHand.MAIN_HAND, truck());
            helper.assertTrue(click(helper, player, CHEST).consumesAction(), "loading the chest");
            Map<ItemKey, Long> census = ItemCensus.take(helper, player);
            ItemCensus.change(census, ItemKey.of(Items.CHEST), 1);
            expected[0] = census;
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
            ItemCensus.assertEquals(helper, expected[0], "after the truck tipped over", holder[0]);
        }).thenSucceed();
    }

    /**
     * The loaded truck's click always wins over the clicked block: on Create's depot and on a warehouse input the
     * chest is set down on top, and neither block receives the truck.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckClickWinsOverBlocks(GameTestHelper helper) {
        BlockPos depot = new BlockPos(1, BASE_Y, 1);
        BlockPos input = new BlockPos(5, BASE_Y, 1);
        BlockPos second = new BlockPos(5, BASE_Y, 3);
        helper.setBlock(CHEST, Blocks.CHEST.defaultBlockState());
        helper.setBlock(second, Blocks.CHEST.defaultBlockState());
        helper.setBlock(depot, AllBlocks.DEPOT.getDefaultState());
        helper.setBlock(input, WareworksBlocks.WAREHOUSE_INPUT.getDefaultState());
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            for (BlockPos[] pair : List.of(new BlockPos[] { CHEST, depot }, new BlockPos[] { second, input })) {
                player.setItemInHand(InteractionHand.MAIN_HAND, truck());
                helper.assertTrue(click(helper, player, pair[0]).consumesAction(), "loading " + pair[0]);
                Map<ItemKey, Long> census = ItemCensus.take(helper, player);
                InteractionResult result = click(helper, player, pair[1]);
                helper.assertTrue(result.consumesAction(), "setting a chest down on " + pair[1] + ": " + result);
                helper.assertBlockPresent(Blocks.CHEST, pair[1].above());
                helper.assertTrue(!HandTruckItem.isLoaded(player.getMainHandItem()), "the truck is empty again");
                IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                        helper.absolutePos(pair[1]), null);
                if (handler != null)
                    for (int slot = 0; slot < handler.getSlots(); slot++)
                        helper.assertTrue(handler.getStackInSlot(slot).isEmpty(),
                                pair[1] + " received something: " + handler.getStackInSlot(slot));
                ItemCensus.assertEquals(helper, census, "after setting a chest down on " + pair[1], player);
            }
        }).thenSucceed();
    }

    /**
     * A player who dies with a loaded truck drops the plain stacks — the contents, the chest's block item and the empty
     * truck — before any gravestone mod sees the drops, never the loaded truck.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckDeathSpills(GameTestHelper helper) {
        helper.setBlock(CHEST, Blocks.CHEST.defaultBlockState());
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ChestBlockEntity chest = helper.getBlockEntity(CHEST);
            chest.setItem(0, new ItemStack(Items.COAL, 64));
            chest.setItem(1, new ItemStack(Items.COAL, 10));
            nameAndLock(helper, chest);
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, truck());
            helper.assertTrue(click(helper, player, CHEST).consumesAction(), "loading the chest");
            ItemStack loaded = player.getMainHandItem();
            Vec3 at = player.position();
            List<ItemEntity> drops = new ArrayList<>(List.of(
                    new ItemEntity(helper.getLevel(), at.x, at.y, at.z, loaded),
                    new ItemEntity(helper.getLevel(), at.x, at.y, at.z, new ItemStack(Items.BREAD, 3))));
            CommonHooks.onLivingDrops(player, helper.getLevel().damageSources().generic(), drops, false);

            List<ItemStack> dropped = drops.stream().map(ItemEntity::getItem).toList();
            helper.assertTrue(dropped.stream().noneMatch(HandTruckItem::isLoaded), "a loaded truck was dropped");
            long coal = dropped.stream().filter(stack -> stack.is(Items.COAL)).mapToLong(ItemStack::getCount).sum();
            helper.assertValueEqual(coal, 74L, "coal dropped from the truck");
            helper.assertTrue(dropped.stream().anyMatch(stack -> stack.is(Items.CHEST)
                    && stack.getHoverName().getString().equals(NAME)), "the named chest item was dropped: " + dropped);
            helper.assertTrue(dropped.stream().anyMatch(stack -> stack.is(WareworksItems.HAND_TRUCK.get())),
                    "the empty truck was dropped");
            helper.assertTrue(dropped.stream().anyMatch(stack -> stack.is(Items.BREAD)), "other drops stay");
            for (ItemStack stack : dropped)
                helper.assertTrue(stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
                        .equals(ItemContainerContents.EMPTY) && !stack.has(DataComponents.BLOCK_ENTITY_DATA),
                        "a dropped stack carries container data: " + stack);
        }).thenSucceed();
    }

    /**
     * The drop key is refused for a loaded truck, and one thrown out of a screen goes back into the inventory: no
     * item entity, nothing lost.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckTossReturned(GameTestHelper helper) {
        helper.setBlock(CHEST, Blocks.CHEST.defaultBlockState());
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ((ChestBlockEntity) helper.getBlockEntity(CHEST)).setItem(0, new ItemStack(Items.QUARTZ, 8));
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, truck());
            helper.assertTrue(click(helper, player, CHEST).consumesAction(), "loading the chest");
            Map<ItemKey, Long> census = ItemCensus.take(helper, player);

            helper.assertTrue(!player.drop(false), "the drop key dropped a loaded truck");
            helper.assertTrue(HandTruckItem.isLoaded(player.getMainHandItem()), "the truck left the hand");
            assertTold(helper, player, WareworksLang.HAND_TRUCK_DROP_REFUSED);

            // Thrown out of the inventory screen with the drop key over its slot.
            int hotbarSlot = 36 + player.getInventory().selected;
            player.inventoryMenu.clicked(hotbarSlot, 0, ClickType.THROW, player);
            helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(), "the thrown truck lies on the floor");
            helper.assertTrue(truckSlot(helper, player) >= 0, "the thrown truck is back in the inventory");
            assertTold(helper, player, WareworksLang.HAND_TRUCK_STAYS_WITH_YOU);
            ItemCensus.assertEquals(helper, census, "after the throws", player);
        }).thenSucceed();
    }

    /**
     * A loaded truck left on the cursor of a player who died is dropped by {@code AbstractContainerMenu#removed} after
     * the death drops: it must not go back into that dead inventory, which is never read again without
     * {@code keepInventory}. It tips over where the player stands: contents, the chest item and the empty truck.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckCursorOfDeadPlayerSpills(GameTestHelper helper) {
        helper.setBlock(CHEST, Blocks.CHEST.defaultBlockState());
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ((ChestBlockEntity) helper.getBlockEntity(CHEST)).setItem(0, new ItemStack(Items.EMERALD, 12));
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, truck());
            helper.assertTrue(click(helper, player, CHEST).consumesAction(), "loading the chest");
            Map<ItemKey, Long> census = ItemCensus.take(helper, player);
            ItemCensus.change(census, ItemKey.of(Items.CHEST), 1);
            player.inventoryMenu.setCarried(player.getMainHandItem());
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.setHealth(0.0F);
            helper.assertTrue(!player.isAlive(), "the player is dead");

            player.inventoryMenu.removed(player);
            helper.assertTrue(player.inventoryMenu.getCarried().isEmpty(), "the cursor still holds the truck");
            helper.assertTrue(truckSlot(helper, player) < 0, "the truck went back into the dead player's inventory");
            List<ItemStack> ground = helper.getEntities(EntityType.ITEM).stream().map(ItemEntity::getItem).toList();
            helper.assertTrue(ground.stream().noneMatch(HandTruckItem::isLoaded), "a loaded truck lies there");
            helper.assertTrue(ground.stream().anyMatch(stack -> stack.is(Items.EMERALD) && stack.getCount() == 12),
                    "the emeralds lie on the ground: " + ground);
            helper.assertTrue(ground.stream().anyMatch(stack -> stack.is(Items.CHEST)), "the chest item: " + ground);
            helper.assertTrue(ground.stream().anyMatch(stack -> stack.is(WareworksItems.HAND_TRUCK.get())),
                    "the empty truck: " + ground);
            ItemCensus.assertEquals(helper, census, "after the dead player's cursor tipped over", player);
        }).thenSucceed();
    }

    /**
     * The penalty applies only while a loaded truck is in the inventory, and never to creative players: the carry
     * stamp is written by the truck's own inventory tick, and the player tick stops gliding and sprinting.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void handTruckPenaltyFlag(GameTestHelper helper) {
        helper.setBlock(CHEST, Blocks.CHEST.defaultBlockState());
        ClickingPlayer[] holder = new ClickingPlayer[1];
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            holder[0] = player;
            player.setItemInHand(InteractionHand.MAIN_HAND, truck());
            player.getInventory().tick();
            helper.assertTrue(!HandTruckCarry.isCarrying(player), "an empty truck costs nothing");

            helper.assertTrue(click(helper, player, CHEST).consumesAction(), "loading the chest");
            player.getInventory().setItem(20, player.getMainHandItem());
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.getInventory().tick();
            helper.assertTrue(HandTruckCarry.isPenalised(player), "a loaded truck in the inventory slows the player");

            player.startFallFlying();
            player.setSprinting(true);
            HandTruckCarry.onPlayerTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(!player.isFallFlying(), "a carrying player still glides");
            helper.assertTrue(!player.isSprinting(), "a carrying player still sprints");

            player.setGameMode(GameType.CREATIVE);
            helper.assertTrue(HandTruckCarry.isCarrying(player) && !HandTruckCarry.isPenalised(player),
                    "a creative player is exempt");
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().setItem(20, ItemStack.EMPTY);
        }).thenIdle(2).thenExecute(() -> {
            // The stamp is the player's own tick counter, which the level advances; this player is in no level.
            holder[0].tickCount += 2;
            holder[0].getInventory().tick();
            helper.assertTrue(!HandTruckCarry.isCarrying(holder[0]),
                    "the penalty outlasts the truck leaving the inventory");
        }).thenSucceed();
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private static ItemStack truck() {
        return new ItemStack(WareworksItems.HAND_TRUCK.get());
    }

    /** One right-click on the top face of the test-relative {@code pos}, through {@code ServerPlayerGameMode#useItemOn}. */
    private static InteractionResult click(GameTestHelper helper, ClickingPlayer player, BlockPos pos) {
        return player.gameMode.useItemOn(player, helper.getLevel(), player.getItemInHand(InteractionHand.MAIN_HAND),
                InteractionHand.MAIN_HAND, topHit(helper.absolutePos(pos)));
    }

    private static BlockHitResult topHit(BlockPos absolute) {
        return new BlockHitResult(new Vec3(absolute.getX() + 0.5, absolute.getY() + 1.0, absolute.getZ() + 0.5),
                Direction.UP, absolute, false);
    }

    /** Gives {@code chest} the custom name {@value #NAME} and the lock {@value #LOCK}, as its save data holds them. */
    private static void nameAndLock(GameTestHelper helper, BlockEntity chest) {
        CompoundTag tag = chest.saveWithoutMetadata(helper.getLevel().registryAccess());
        tag.putString("CustomName", Component.Serializer.toJson(Component.literal(NAME),
                helper.getLevel().registryAccess()));
        tag.putString("Lock", LOCK);
        chest.loadWithComponents(tag, helper.getLevel().registryAccess());
        chest.setChanged();
    }

    private static ItemStack enchantedSword(GameTestHelper helper) {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.enchant(helper.getLevel().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS), 3);
        sword.setDamageValue(17);
        return sword;
    }

    private static ItemStack filledShulkerBox() {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.TORCH, 32),
                new ItemStack(Items.APPLE, 5))));
        return box;
    }

    /** A writable book of ten long pages: about ten KiB of save data. */
    private static ItemStack heavyBook(int salt) {
        List<Filterable<String>> pages = new ArrayList<>();
        for (int page = 0; page < 10; page++)
            pages.add(Filterable.passThrough(("page " + page + " of book " + salt + " ").repeat(50)));
        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(pages));
        return book;
    }

    /** {@code count} different items that stack to at least one, in registry order. */
    private static List<Item> distinctItems(int count) {
        List<Item> items = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR || item instanceof HandTruckItem)
                continue;
            items.add(item);
            if (items.size() == count)
                break;
        }
        return Collections.unmodifiableList(items);
    }

    private static List<ItemStack> contents(BlockEntity be) {
        net.minecraft.world.Container container = (net.minecraft.world.Container) be;
        List<ItemStack> stacks = new ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++)
            stacks.add(container.getItem(slot).copy());
        return stacks;
    }

    private static void assertStacks(GameTestHelper helper, List<ItemStack> actual, List<ItemStack> expected,
            String what) {
        helper.assertValueEqual(actual.size(), expected.size(), "slots of " + what);
        for (int slot = 0; slot < expected.size(); slot++)
            helper.assertTrue(ItemStack.matches(actual.get(slot), expected.get(slot)),
                    what + " slot " + slot + ": " + actual.get(slot) + " instead of " + expected.get(slot));
    }

    private static void assertTold(GameTestHelper helper, ClickingPlayer player, String relativeKey) {
        helper.assertTrue(player.toldKeys().contains(WareworksLang.key(relativeKey)),
                "the player was not told " + relativeKey + ": " + player.toldKeys());
    }

    /** The player inventory slot that holds the loaded truck, or -1. */
    private static int truckSlot(GameTestHelper helper, ClickingPlayer player) {
        for (int slot = 0; slot < player.getInventory().items.size(); slot++)
            if (HandTruckItem.isLoaded(player.getInventory().items.get(slot)))
                return slot;
        return -1;
    }

    /** The chest menu slot of player inventory slot {@code slot}: main slots after the chest's, then the hotbar. */
    private static int menuSlotOf(int slot) {
        return slot < 9 ? CHEST_SLOTS + 27 + slot : CHEST_SLOTS + slot - 9;
    }

    /** After a click into the chest: the chest is empty, the truck is in the inventory, the census unchanged. */
    private static void assertReturned(GameTestHelper helper, ClickingPlayer player, ChestBlockEntity chest,
            Map<ItemKey, Long> census, String how) {
        for (int slot = 0; slot < chest.getContainerSize(); slot++)
            helper.assertTrue(!HandTruckItem.isLoaded(chest.getItem(slot)), "the chest kept a loaded truck " + how);
        helper.assertTrue(truckSlot(helper, player) >= 0 || HandTruckItem.isLoaded(player.containerMenu.getCarried()),
                "the truck is not back with the player after it was " + how);
        assertTold(helper, player, WareworksLang.HAND_TRUCK_STAYS_WITH_YOU);
        ItemCensus.assertEquals(helper, census, "after the truck was " + how, player);
    }
}
