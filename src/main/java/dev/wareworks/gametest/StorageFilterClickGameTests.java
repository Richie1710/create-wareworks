package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.content.storage.FluidBayBlock;
import dev.wareworks.content.storage.FluidBayBlockEntity;
import dev.wareworks.content.storage.RackBayBlock;
import dev.wareworks.content.storage.RackBayBlockEntity;
import dev.wareworks.content.storage.StorageFilterBehaviour;
import dev.wareworks.content.storage.StorageFilterClicks;
import dev.wareworks.content.storage.StorageFilterValueBox;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A click on the store filter slot of the three storage locations — a warehouse interface, a rack bay and a fluid bay —
 * <b>through the server's real dispatch</b> ({@link ClickingPlayer}, issue #30, ADR-028): a click with an item sets
 * the filter on the press, and everything else reaches what it reached before.
 * <ul>
 * <li>{@code filterclicksetsfilteronpress} — a stack, a single item and a filled bucket set the filter in the same
 * call that stands for the press, as a sample of one, with the hand, the bay, the tank and the chest behind the
 * interface untouched and nothing placed against the face; the single item replaces the stack on the press; the same
 * item again is not applied a second time;</li>
 * <li>{@code filterclickwithcreatefilteritems} — a List Filter and an Attribute Filter go into the slot as Create puts
 * them there: taken from a survival player's hand, the one they replace given back, and an identical one changes
 * nothing;</li>
 * <li>{@code filterclickemptyhandandwrenchkeepthewarmup} — an empty hand and Create's wrench are left to Create's
 * handler: the press changes nothing on the server (the board's warmup is the client's), a rack bay gives nothing out,
 * the wrench turns nothing, and the release Create sends still clears the filter for the empty hand; an item in the
 * off hand neither makes an empty main hand a filter click nor is the off hand's own click taken;</li>
 * <li>{@code filterclickpassesthroughtheothers} — the clipboard copies the slot through Create's own handler; the
 * Mechanical Arm item, a fake player, an adventure-mode and a spectating player are not taken; a sneaking click puts a
 * whole stack into a rack bay and a click beside the slot one item, as before;</li>
 * <li>{@code filterclickyieldstoaclaim} — a protection listener at {@code HIGH} that registered after this mod, as a
 * claim mod's can, still refuses a click with an item, and nothing is set; without it the same click sets the
 * filter.</li>
 * </ul>
 * Every case asserts {@link StorageFilterClicks#takenBy} as well: the click is cancelled with the same
 * {@code SUCCESS} by Create's own handler either way, and only that answer tells "set on the press" from "left to the
 * warmup".
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class StorageFilterClickGameTests {
    private static final BlockPos INTERFACE = new BlockPos(1, BASE_Y, 3);
    private static final BlockPos RACK_BAY = new BlockPos(3, BASE_Y, 3);
    private static final BlockPos FLUID_BAY = new BlockPos(5, BASE_Y, 3);
    /** Where the player stands: in the aisle, two blocks in front of the three aisle faces. */
    private static final BlockPos STAND = new BlockPos(3, BASE_Y, 5);
    /** Every block faces north, into the rack depth, so its aisle face (and filter slot) is the south one. */
    private static final Direction FACING = Direction.NORTH;
    private static final Direction AISLE_FACE = FACING.getOpposite();

    private static final int STACK = 16;
    private static final int STORED = 5;
    private static final double PIXEL = 1.0 / 16.0;

    private StorageFilterClickGameTests() {
    }

    /**
     * One of the three storage locations, the stack or filled bucket a player would dedicate it with, and the single
     * item or other bucket that replaces it.
     */
    private record Location(String name, BlockPos pos, Supplier<ItemStack> dedication, Supplier<ItemStack> other) {
    }

    private static List<Location> locations() {
        return List.of(
                new Location("warehouse interface", INTERFACE, () -> new ItemStack(Items.COBBLESTONE, STACK),
                        () -> new ItemStack(Items.IRON_INGOT)),
                new Location("rack bay", RACK_BAY, () -> new ItemStack(Items.COBBLESTONE, STACK),
                        () -> new ItemStack(Items.IRON_INGOT)),
                new Location("fluid bay", FLUID_BAY, () -> new ItemStack(Items.WATER_BUCKET),
                        () -> new ItemStack(Items.LAVA_BUCKET)));
    }

    /**
     * The press sets it: {@code ServerPlayerGameMode#useItemOn} is the server's whole handling of the press, and before
     * issue #30 a filter click left it with nothing set — Create's handler cancelled it for the client warmup, and only
     * the release packet that followed applied the filter.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void filterClickSetsFilterOnPress(GameTestHelper helper) {
        placeLocations(helper);
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            for (Location location : locations()) {
                StorageFilterBehaviour slot = slotAt(helper, location.pos());
                BlockHitResult hit = slotHit(helper, location.pos());
                ItemStack held = location.dedication().get();
                ItemStack before = held.copy();

                assertTaken(helper, player, hit, held, location, "a click with " + before);
                InteractionResult result = click(helper, player, hit, held, false);
                helper.assertTrue(result.consumesAction(), "a click with " + before + " on the " + location.name()
                        + "'s filter slot is consumed, so the client neither places nor uses the item: " + result);
                assertFilter(helper, slot, before.copyWithCount(1), location, "the press with " + before);
                helper.assertTrue(ItemStack.matches(player.getMainHandItem(), before),
                        "the " + before + " is still in the hand, untouched: " + player.getMainHandItem());
                assertNothingMoved(helper, location);
                helper.assertTrue(slot.acceptsValueSettings(), "the " + location.name()
                        + "'s slot takes value settings again once the press is dispatched");

                ItemStack other = location.other().get();
                click(helper, player, hit, other.copy(), false);
                assertFilter(helper, slot, other.copyWithCount(1), location, "the press with " + other);

                // The same item again: the slot already holds that sample, so it is not set a second time.
                ItemStack sample = slot.getFilter();
                InteractionResult again = click(helper, player, hit, other.copy(), false);
                helper.assertTrue(again.consumesAction(), "a second click with the filter's own item on the "
                        + location.name() + " is still consumed: " + again);
                helper.assertTrue(slot.getFilter() == sample,
                        "a second click with " + other + " does not set the " + location.name() + "'s filter again");
            }
        }).thenSucceed();
    }

    /**
     * A Create filter item is applied by Create's own {@code FilteringBehaviour#onShortInteract}, which is what the
     * release reached before: a survival player loses the filter item to the slot and gets the replaced one back.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void filterClickWithCreateFilterItems(GameTestHelper helper) {
        placeLocations(helper);
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            for (Location location : locations()) {
                player.getInventory().clearContent();
                StorageFilterBehaviour slot = slotAt(helper, location.pos());
                BlockHitResult hit = slotHit(helper, location.pos());

                assertTaken(helper, player, hit, AllItems.ATTRIBUTE_FILTER.asStack(), location,
                        "a click with an Attribute Filter");
                click(helper, player, hit, AllItems.ATTRIBUTE_FILTER.asStack(), false);
                helper.assertTrue(AllItems.ATTRIBUTE_FILTER.isIn(slot.getFilter()),
                        "the " + location.name() + "'s slot holds the Attribute Filter: " + slot.getFilter());
                helper.assertTrue(player.getMainHandItem().isEmpty(),
                        "the Attribute Filter left the survival player's hand: " + player.getMainHandItem());

                ItemStack list = AllItems.FILTER.asStack();
                list.set(AllDataComponents.FILTER_ITEMS,
                        ItemContainerContents.fromItems(List.of(new ItemStack(Items.IRON_INGOT))));
                click(helper, player, hit, list.copy(), false);
                helper.assertTrue(AllItems.FILTER.isIn(slot.getFilter()),
                        "a List Filter replaces it in the " + location.name() + "'s slot: " + slot.getFilter());
                helper.assertValueEqual(slot.getFilter().get(AllDataComponents.FILTER_ITEMS),
                        list.get(AllDataComponents.FILTER_ITEMS), "the List Filter in the slot keeps its list");
                helper.assertTrue(player.getMainHandItem().isEmpty(), "and left the hand too");
                helper.assertValueEqual(player.getInventory().countItem(AllItems.ATTRIBUTE_FILTER.get()), 1,
                        "the replaced Attribute Filter came back to the player");

                // An identical List Filter: the slot already holds it, so nothing is swapped. Create's
                // FilterItemStack.of trimmed the stored copy in place, which leaves removal entries in its patch, so
                // this also proves that the comparison trims the held one the same way.
                ItemStack sample = slot.getFilter();
                click(helper, player, hit, list.copy(), false);
                helper.assertTrue(slot.getFilter() == sample, "an identical List Filter changes nothing");
                helper.assertTrue(ItemStack.matches(player.getMainHandItem(), list),
                        "and stays in the hand as it was: " + player.getMainHandItem());
                helper.assertValueEqual(player.getInventory().countItem(AllItems.FILTER.get()), 1,
                        "no List Filter was given back for it");
                helper.assertValueEqual(player.getInventory().countItem(AllItems.ATTRIBUTE_FILTER.get()), 1,
                        "and the Attribute Filter is still the only one");
            }
        }).thenSucceed();
    }

    /**
     * An empty hand and a wrench keep Create's warmup, which is client-side: on the server the press is cancelled and
     * changes nothing, and what a short click does is decided by the release packet, here called the way
     * {@code ValueSettingsPacket#applySettings} calls it.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void filterClickEmptyHandAndWrenchKeepTheWarmup(GameTestHelper helper) {
        placeLocations(helper);
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            RackBayBlockEntity bay = rackBayAt(helper);
            bay.insert(new ItemStack(Items.COBBLESTONE, STORED), false);
            for (Location location : locations()) {
                StorageFilterBehaviour slot = slotAt(helper, location.pos());
                BlockHitResult hit = slotHit(helper, location.pos());
                ItemStack filter = location.dedication().get().copyWithCount(1);
                BlockState stateBefore = stateAt(helper, location.pos());

                for (ItemStack held : List.of(ItemStack.EMPTY, AllItems.WRENCH.asStack())) {
                    String what = held.isEmpty() ? "an empty hand" : "a wrench";
                    helper.assertTrue(slot.setFilter(filter.copy()), "the " + location.name() + " takes a filter");
                    ItemStack sample = slot.getFilter();
                    helper.assertTrue(StorageFilterClicks.takenBy(withMainHand(player, held), helper.getLevel(),
                            hit.getBlockPos(), InteractionHand.MAIN_HAND, hit) == null,
                            "a click with " + what + " is left to Create's handler on the " + location.name());
                    InteractionResult result = click(helper, player, hit, held.copy(), false);
                    helper.assertTrue(result.consumesAction(),
                            "Create's handler still takes the press with " + what + " for its warmup: " + result);
                    helper.assertTrue(slot.getFilter() == sample,
                            "the press with " + what + " changes nothing on the " + location.name());
                    helper.assertTrue(stateAt(helper, location.pos()) == stateBefore,
                            what + " turned nothing on the " + location.name());
                    helper.assertValueEqual(bay.storedCount(), STORED, "the rack bay gave nothing out to " + what);

                    // The release of a short click, as Create's packet applies it.
                    slot.onShortInteract(player, InteractionHand.MAIN_HAND, AISLE_FACE, hit);
                    if (held.isEmpty())
                        helper.assertTrue(slot.getFilter().isEmpty(), "a short click with an empty hand still clears"
                                + " the " + location.name() + "'s filter on release: " + slot.getFilter());
                    else
                        helper.assertTrue(slot.getFilter() == sample,
                                "a short click with Create's wrench still sets nothing: " + slot.getFilter());
                }

                // An item in the off hand is no filter click: the main hand is the one Create's handler acts for.
                player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.IRON_INGOT));
                helper.assertTrue(StorageFilterClicks.takenBy(withMainHand(player, ItemStack.EMPTY), helper.getLevel(),
                        hit.getBlockPos(), InteractionHand.MAIN_HAND, hit) == null,
                        "an empty main hand with an item in the off hand is still an empty hand");
                helper.assertTrue(StorageFilterClicks.takenBy(withMainHand(player, ItemStack.EMPTY), helper.getLevel(),
                        hit.getBlockPos(), InteractionHand.OFF_HAND, hit) == null,
                        "and the off hand's own click is not taken");
                player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            }
            helper.assertValueEqual(player.getInventory().countItem(Items.COBBLESTONE), 0,
                    "no cobblestone reached the player");
        }).thenSucceed();
    }

    /**
     * Everything Create's handler let through before still goes through, and the two gestures of a rack bay still work:
     * Shift puts a stack in, and a click beside the slot one item.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void filterClickPassesThroughTheOthers(GameTestHelper helper) {
        placeLocations(helper);
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            Player fake = FakePlayerFactory.getMinecraft(helper.getLevel());
            for (Location location : locations()) {
                StorageFilterBehaviour slot = slotAt(helper, location.pos());
                BlockHitResult hit = slotHit(helper, location.pos());
                ItemStack item = location.dedication().get();

                assertPassedOn(helper, player, hit, AllBlocks.MECHANICAL_ARM.asStack(), slot, location,
                        "the Mechanical Arm item");
                fake.setItemInHand(InteractionHand.MAIN_HAND, item.copy());
                assertPassedOn(helper, fake, hit, item.copy(), slot, location, "a fake player");
                player.setGameMode(GameType.ADVENTURE);
                assertPassedOn(helper, player, hit, item.copy(), slot, location, "an adventure-mode player");
                player.setGameMode(GameType.SPECTATOR);
                assertPassedOn(helper, player, hit, item.copy(), slot, location, "a spectating player");
                player.setGameMode(GameType.SURVIVAL);
                player.setShiftKeyDown(true);
                assertPassedOn(helper, player, hit, item.copy(), slot, location, "a sneaking player");
                player.setShiftKeyDown(false);
                assertPassedOn(helper, player, outsideHit(helper, location.pos()), item.copy(), slot, location,
                        "a click beside the slot");

                // The clipboard copies the slot, through Create's own handler.
                helper.assertTrue(slot.setFilter(item.copy()), "the " + location.name() + " takes a filter");
                ItemStack clipboard = AllBlocks.CLIPBOARD.asStack();
                helper.assertTrue(StorageFilterClicks.takenBy(withMainHand(player, clipboard), helper.getLevel(),
                        hit.getBlockPos(), InteractionHand.MAIN_HAND, hit) == null,
                        "a clipboard click is left to Create's clipboard handler on the " + location.name());
                InteractionResult copied = click(helper, player, hit, clipboard, false);
                helper.assertTrue(copied.consumesAction(), "the clipboard click is consumed: " + copied);
                ClipboardContent content = player.getMainHandItem().get(AllDataComponents.CLIPBOARD_CONTENT);
                helper.assertTrue(content != null && content.copiedValues().isPresent()
                                && content.copiedValues().get().contains("Filtering"),
                        "the clipboard copied the " + location.name() + "'s filter slot: " + content);
                helper.assertTrue(ItemStack.isSameItemSameComponents(slot.getFilter(), item),
                        "and the filter is still " + item + ": " + slot.getFilter());
                slot.setFilter(ItemStack.EMPTY);
            }

            // The rack bay's own gestures around the slot, through the real dispatch.
            RackBayBlockEntity bay = rackBayAt(helper);
            StorageFilterBehaviour baySlot = slotAt(helper, RACK_BAY);
            click(helper, player, outsideHit(helper, RACK_BAY), new ItemStack(Items.COBBLESTONE, STACK), false);
            helper.assertValueEqual(bay.storedCount(), 1, "a plain click beside the slot puts one item into the bay");
            click(helper, player, slotHit(helper, RACK_BAY), new ItemStack(Items.COBBLESTONE, STACK), true);
            helper.assertValueEqual(bay.storedCount(), 1 + STACK,
                    "a sneaking click on the slot itself puts the whole stack in, as before");
            helper.assertTrue(player.getMainHandItem().isEmpty(), "and takes it from the hand");
            helper.assertTrue(baySlot.getFilter().isEmpty(), "neither click set the bay's filter: "
                    + baySlot.getFilter());
        }).thenSucceed();
    }

    /**
     * A claim refuses the click before anything is set, also from {@code HIGH} and registered <b>after</b> this mod:
     * at one priority listeners run in registration order, mod construction decides that order, and not every claim
     * mod refuses at {@code HIGHEST}. This mod's own listener at {@code HIGH} therefore cancels nothing; Create's
     * handler at {@code NORMAL} applies the press, and a refused click never reaches it. The mark the press left must
     * end with the refused click too, or the slot would answer the next render, board packet or clipboard as if a
     * press were being dispatched.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void filterClickYieldsToAClaim(GameTestHelper helper) {
        placeLocations(helper);
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            Set<BlockPos> claimed = new HashSet<>();
            for (Location location : locations())
                claimed.add(helper.absolutePos(location.pos()));
            int[] refused = { 0 };
            Consumer<PlayerInteractEvent.RightClickBlock> claim = event -> {
                if (event.getEntity() == player && claimed.contains(event.getPos())) {
                    event.setCanceled(true);
                    event.setCancellationResult(InteractionResult.FAIL);
                    refused[0]++;
                }
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, false, PlayerInteractEvent.RightClickBlock.class, claim);
            try {
                for (Location location : locations()) {
                    StorageFilterBehaviour slot = slotAt(helper, location.pos());
                    BlockHitResult hit = slotHit(helper, location.pos());
                    ItemStack held = location.dedication().get();
                    assertTaken(helper, player, hit, held, location, "a click with " + held + " in a claim");
                    InteractionResult result = click(helper, player, hit, held.copy(), false);
                    helper.assertTrue(result == InteractionResult.FAIL,
                            "the claim's answer reaches the client for the " + location.name() + ": " + result);
                    helper.assertTrue(slot.getFilter().isEmpty(), "a click with " + held + " in a claim set the "
                            + location.name() + "'s filter: " + slot.getFilter());
                    assertNothingMoved(helper, location);
                    helper.assertTrue(slot.acceptsValueSettings(), "the refused press left its mark on the "
                            + location.name() + "'s slot");
                }
            } finally {
                NeoForge.EVENT_BUS.unregister(claim);
            }
            helper.assertValueEqual(refused[0], locations().size(), "the claim saw every click");

            // Without the claim the same click sets it, so the claim is what stopped it.
            for (Location location : locations()) {
                ItemStack held = location.dedication().get();
                click(helper, player, slotHit(helper, location.pos()), held.copy(), false);
                assertFilter(helper, slotAt(helper, location.pos()), held.copyWithCount(1), location,
                        "the same press outside the claim");
            }
        }).thenSucceed();
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private static void placeLocations(GameTestHelper helper) {
        helper.setBlock(INTERFACE.relative(FACING), Blocks.CHEST);
        helper.setBlock(INTERFACE, WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, FACING));
        helper.setBlock(RACK_BAY, WareworksBlocks.RACK_BAY_WOOD.getDefaultState()
                .setValue(RackBayBlock.FACING, FACING));
        helper.setBlock(FLUID_BAY, WareworksBlocks.FLUID_BAY_COPPER.getDefaultState()
                .setValue(FluidBayBlock.FACING, FACING));
    }

    private static BlockState stateAt(GameTestHelper helper, BlockPos pos) {
        return helper.getLevel().getBlockState(helper.absolutePos(pos));
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

    private static RackBayBlockEntity rackBayAt(GameTestHelper helper) {
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(RACK_BAY)) instanceof RackBayBlockEntity bay)) {
            helper.fail("missing rack bay block entity", RACK_BAY);
            throw new IllegalStateException("unreachable");
        }
        return bay;
    }

    /**
     * A hit on the aisle face at the centre of the filter slot, where a player aiming at it clicks: the box centre
     * stands {@link StorageFilterValueBox#FACE_INSET_PIXELS} in front of the face, so the point on the face is half a
     * pixel from it, well inside the 4 px sphere. Asserted, so a moved box cannot turn every case below into a click
     * beside it.
     */
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

    /** A hit on the aisle face 3 px from its left edge and 12 px up, about 8 px from the slot's centre. */
    private static BlockHitResult outsideHit(GameTestHelper helper, BlockPos pos) {
        BlockPos absolute = helper.absolutePos(pos);
        // The south face of a block spans x 0..1 and y 0..1 at z = 1.
        Vec3 onFace = Vec3.atLowerCornerOf(absolute).add(3 * PIXEL, 12 * PIXEL, 1.0);
        helper.assertFalse(slotAt(helper, pos).testHit(onFace),
                "the hit at " + onFace + " lies beside the " + pos + " filter slot");
        return new BlockHitResult(onFace, AISLE_FACE, absolute, false);
    }

    private static Player withMainHand(Player player, ItemStack held) {
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        return player;
    }

    /**
     * One right-click through {@code ServerPlayerGameMode#useItemOn}, the server's whole handling of a press: the
     * interaction event with every listener on it, the sneak guard, the block's own interaction and the item's.
     */
    private static InteractionResult click(GameTestHelper helper, ClickingPlayer player, BlockHitResult hit,
                                           ItemStack held, boolean sneaking) {
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        player.setShiftKeyDown(sneaking);
        InteractionResult result = player.gameMode.useItemOn(player, helper.getLevel(),
                player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, hit);
        player.setShiftKeyDown(false);
        return result;
    }

    private static void assertTaken(GameTestHelper helper, Player player, BlockHitResult hit, ItemStack held,
                                    Location location, String what) {
        ItemStack before = player.getMainHandItem();
        helper.assertTrue(StorageFilterClicks.takenBy(withMainHand(player, held), helper.getLevel(), hit.getBlockPos(),
                InteractionHand.MAIN_HAND, hit) != null, what + " on the " + location.name() + " sets its filter");
        player.setItemInHand(InteractionHand.MAIN_HAND, before);
    }

    private static void assertFilter(GameTestHelper helper, StorageFilterBehaviour slot, ItemStack expected,
                                     Location location, String what) {
        helper.assertTrue(ItemStack.matches(slot.getFilter(), expected),
                what + " set the " + location.name() + "'s filter to " + expected + ": " + slot.getFilter());
    }

    /** The click went to the slot and nowhere else: no item in the bay, no fluid in the tank, nothing in the chest. */
    private static void assertNothingMoved(GameTestHelper helper, Location location) {
        helper.assertBlockPresent(Blocks.AIR, location.pos().relative(AISLE_FACE));
        if (helper.getLevel().getBlockEntity(helper.absolutePos(location.pos())) instanceof RackBayBlockEntity bay)
            helper.assertValueEqual(bay.storedCount(), 0, "the click put nothing into the rack bay");
        if (helper.getLevel().getBlockEntity(helper.absolutePos(location.pos())) instanceof FluidBayBlockEntity tank)
            helper.assertTrue(tank.storedFluid().isEmpty(), "the click poured nothing into the fluid bay");
        if (location.pos().equals(INTERFACE)
                && helper.getLevel().getBlockEntity(helper.absolutePos(INTERFACE.relative(FACING)))
                instanceof ChestBlockEntity chest)
            helper.assertTrue(chest.isEmpty(), "the click put nothing into the chest behind the interface");
    }

    /**
     * {@code player}'s click with {@code held} on the slot is not taken here, the event the server posts for it stays
     * uncancelled — so it goes on to the block and the item exactly as it did before the slot existed — and the filter
     * is left alone. The event is posted on its own rather than through the whole dispatch, because what the block and
     * the item then do (place an arm, a block against the face) is theirs and not this test's.
     */
    private static void assertPassedOn(GameTestHelper helper, Player player, BlockHitResult hit, ItemStack held,
                                       StorageFilterBehaviour slot, Location location, String what) {
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        ItemStack filterBefore = slot.getFilter();
        helper.assertTrue(StorageFilterClicks.takenBy(player, helper.getLevel(), hit.getBlockPos(),
                InteractionHand.MAIN_HAND, hit) == null, what + " is not a filter click on the " + location.name());
        PlayerInteractEvent.RightClickBlock event = CommonHooks.onRightClickBlock(player, InteractionHand.MAIN_HAND,
                hit.getBlockPos(), hit);
        helper.assertFalse(event.isCanceled(), what + " passes the " + location.name() + "'s filter slot on");
        helper.assertTrue(slot.getFilter() == filterBefore, what + " leaves the " + location.name() + "'s filter "
                + "alone: " + slot.getFilter());
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }
}
