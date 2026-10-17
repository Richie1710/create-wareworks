package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardOverrides.ClipboardType;
import com.simibubi.create.content.redstone.displayLink.ClickToLinkBlockItem.ClickToLinkData;
import com.simibubi.create.foundation.utility.BlockHelper;

import dev.wareworks.Wareworks;
import dev.wareworks.content.station.ProductionMenu;
import dev.wareworks.content.station.StockKeeperMenu;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A right-click on the three stations that have a screen — the warehouse terminal, the production station and the stock
 * keeper — <b>through the server's real dispatch</b> (issue #26, ADR-060): a right-click opens the screen whatever is
 * in the hand, a sneaking one places or uses the item, and the items that act on a station keep their click.
 * <ul>
 * <li>{@code stationsopenwhateverisheld} — an empty hand, a block, another warehouse block, a tool and food open each
 * station's screen; the click is consumed, nothing is placed and the stack is untouched;</li>
 * <li>{@code stationssneakclickusestheitem} — sneaking places a block against each station and leaves food to the use
 * that follows; with an empty hand the terminal and the keeper pass as before and the production station resumes, also
 * with a shield in the offhand, but not with an Extendo Grip in the main hand;</li>
 * <li>{@code stationskeeptheirownclickitems} — the wrench turns each station, the Mechanical Arm item selects the
 * terminal and the production station and is placed against the keeper, the clipboard opens its editor and is hung on
 * the face with Shift, the Display Link selects the station as its source and the goggles are put on: none of them
 * opens a screen;</li>
 * <li>{@code stationsopenforawrenchinadventuremode} — where the player may not build, the wrench cannot turn anything,
 * so it opens each station's screen like any other item instead of letting the click run on to the offhand;</li>
 * <li>{@code stationsletdeployersplaceagainstthem} — a Deployer's fake player still places a block against each
 * station, along the path Create's Deployer takes, and an empty-handed one opens nothing.</li>
 * </ul>
 * <p>
 * <b>Why not {@code GameTestHelper#useBlock}.</b> That helper calls the block's {@code useItemOn} and
 * {@code useWithoutItem} itself: it fires no {@code RightClickBlock} event (the Display Link's), skips
 * {@code onItemUseFirst} and skips the sneak guard — and the sneak guard is the whole of "sneak + right-click places
 * the block". Its mock player is no {@code ServerPlayer} either, so no screen could open. These tests call
 * {@code ServerPlayerGameMode#useItemOn}, the method a {@code ServerboundUseItemOnPacket} reaches on a dedicated
 * server, with a real {@link ServerPlayer} ({@link ClickingPlayer}). A client predicts the same click with the same
 * block methods ({@code MultiPlayerGameMode#performUseItemOn}); what it does next is decided by whether the click was
 * consumed, which is what every case asserts.
 * <p>
 * Every case is one click and its answer within the same tick, and the player is never added to the level or the
 * player list, so no other test can see it.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class StationClickGameTests {
    private static final BlockPos TERMINAL = new BlockPos(1, BASE_Y, 3);
    private static final BlockPos PRODUCTION = new BlockPos(3, BASE_Y, 3);
    private static final BlockPos KEEPER = new BlockPos(5, BASE_Y, 3);
    /** Where the player stands: two blocks from the stations, clear of everything placed on top of them. */
    private static final BlockPos STAND = new BlockPos(3, BASE_Y, 1);

    private static final int BLOCKS = 16;
    private static final int BREAD = 4;
    /** Low enough that a survival player may eat. */
    private static final int HUNGRY = 10;

    private StationClickGameTests() {
    }

    /**
     * Whatever is in the hand, a plain right-click opens the station's screen — the vanilla container convention issue
     * #26 asks for — and is <b>consumed</b>, which is what keeps the client from placing the block or starting to eat:
     * {@code MultiPlayerGameMode#performUseItemOn} returns before {@code stack.useOn}, and
     * {@code Minecraft#startUseItem} sends no use packet after a consumed click. Before the fix, every item in this
     * list passed the click on, so a block was placed against the station and food was eaten in front of it.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void stationsOpenWhateverIsHeld(GameTestHelper helper) {
        placeStations(helper);
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            for (Station station : stations()) {
                for (ItemStack held : List.of(ItemStack.EMPTY,
                        Items.COBBLESTONE.getDefaultInstance().copyWithCount(BLOCKS),
                        WareworksBlocks.WAREHOUSE_INPUT.asStack(), Items.IRON_PICKAXE.getDefaultInstance(),
                        Items.BREAD.getDefaultInstance().copyWithCount(BREAD))) {
                    String what = held.isEmpty() ? "an empty hand" : held.getCount() + " " + held.getItem();
                    InteractionResult result = click(helper, player, station.pos(), held.copy(), false);
                    assertOpened(helper, player, station, result, what);
                    helper.assertTrue(ItemStack.matches(player.getMainHandItem(), held),
                            "the " + what + " at the " + station.name() + " is still in the hand, untouched: "
                                    + player.getMainHandItem());
                    assertNothingOnTop(helper, station, what);
                }
            }
        }).thenSucceed();
    }

    /**
     * Sneaking is how an item is used against a station, as against a chest: vanilla's sneak guard keeps the block out
     * of a sneaking click whenever a hand holds something, so a block is placed and food is left to the use that
     * follows. With an <b>empty</b> hand nothing changes: the terminal and the keeper pass such a click on, as they
     * always did, and the production station's resume gesture still answers — with a shield in the offhand too
     * ({@code ProductionStationHooks}), and not with an Extendo Grip in the main hand, the item that bypasses the sneak
     * guard and so reaches the station while sneaking.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void stationsSneakClickUsesTheItem(GameTestHelper helper) {
        placeStations(helper);
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            for (Station station : stations()) {
                InteractionResult placed = click(helper, player, station.pos(),
                        Items.COBBLESTONE.getDefaultInstance().copyWithCount(BLOCKS), true);
                helper.assertTrue(placed.consumesAction(), "a sneaking click places the block: " + placed);
                assertNoScreen(helper, player, station, "a sneaking click with a block");
                helper.assertBlockPresent(Blocks.COBBLESTONE, station.pos().above());
                helper.assertValueEqual(player.getMainHandItem().getCount(), BLOCKS - 1,
                        "a survival player pays one block for it at the " + station.name());
                helper.setBlock(station.pos().above(), Blocks.AIR);

                player.getFoodData().setFoodLevel(HUNGRY);
                InteractionResult eaten = click(helper, player, station.pos(),
                        Items.BREAD.getDefaultInstance().copyWithCount(BREAD), true);
                helper.assertFalse(eaten.consumesAction(),
                        "a sneaking click with food is not the station's, so the client goes on to use it: " + eaten);
                assertNoScreen(helper, player, station, "a sneaking click with food");
                player.gameMode.useItem(player, helper.getLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
                helper.assertTrue(player.isUsingItem(),
                        "and the use the client sends next starts eating in front of the " + station.name());
                player.stopUsingItem();

                player.told.clear();
                InteractionResult empty = click(helper, player, station.pos(), ItemStack.EMPTY, true);
                assertNoScreen(helper, player, station, "a sneaking click with an empty hand");
                if (station.resumes()) {
                    helper.assertTrue(empty.consumesAction(), "the production station's resume answers: " + empty);
                    assertTold(helper, player, station, "the empty-handed sneak-click",
                            WareworksLang.PRODUCTION_NOTHING_STOPPED);
                } else {
                    helper.assertFalse(empty.consumesAction(), "the " + station.name()
                            + " passes an empty-handed sneak-click on, as before: " + empty);
                    assertTold(helper, player, station, "the empty-handed sneak-click");
                }

                player.setItemInHand(InteractionHand.OFF_HAND, Items.SHIELD.getDefaultInstance());
                InteractionResult shielded = click(helper, player, station.pos(), ItemStack.EMPTY, true);
                assertNoScreen(helper, player, station, "a sneaking click with a shield in the offhand");
                if (station.resumes()) {
                    helper.assertTrue(shielded.consumesAction(), "the resume still answers with a full offhand");
                    assertTold(helper, player, station, "the sneak-click with a shield in the offhand",
                            WareworksLang.PRODUCTION_NOTHING_STOPPED);
                } else {
                    helper.assertFalse(shielded.consumesAction(), "a full offhand changes nothing at the "
                            + station.name() + ": " + shielded);
                    assertTold(helper, player, station, "the sneak-click with a shield in the offhand");
                }
                player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);

                if (station.resumes()) {
                    InteractionResult grip = click(helper, player, station.pos(), AllItems.EXTENDO_GRIP.asStack(),
                            true);
                    helper.assertFalse(grip.consumesAction(), "the Extendo Grip reaches the station while sneaking, "
                            + "but the resume belongs to an empty hand: " + grip);
                    assertNoScreen(helper, player, station, "a sneaking click with an Extendo Grip");
                    assertTold(helper, player, station, "the sneak-click with an Extendo Grip");
                }
            }
        }).thenSucceed();
    }

    /**
     * The items whose click is about the station keep it, exactly as before the fix
     * ({@code StationScreenClick#actsOnTheBlock}), and none of them opens a screen:
     * <ul>
     * <li>the <b>wrench</b> turns the terminal's screen and the other two stations' facing;</li>
     * <li>the <b>Mechanical Arm</b> item selects the terminal and the production station, which on the server is
     * {@code ArmItem#useOn} answering without placing anything, and is placed against the keeper, which is no arm
     * target. Create's selection handler runs on the client only, so this server half is the part that would have
     * opened a screen in the middle of a selection;</li>
     * <li>the <b>clipboard</b> opens Create's own editor (the server marks it as being edited) and is hung on the face
     * with Shift;</li>
     * <li>the <b>Display Link</b> selects the station as its source: Create keeps the block out of that click before it
     * is asked;</li>
     * <li>the <b>goggles</b> pass the click on, so the use the client sends next puts them on.</li>
     * </ul>
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void stationsKeepTheirOwnClickItems(GameTestHelper helper) {
        placeStations(helper);
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            for (Station station : stations()) {
                BlockState before = stateAt(helper, station.pos());
                InteractionResult wrenched = click(helper, player, station.pos(), AllItems.WRENCH.asStack(), false);
                helper.assertTrue(wrenched.consumesAction(),
                        "the wrench acts on the " + station.name() + ": " + wrenched);
                assertNoScreen(helper, player, station, "the wrench");
                BlockState after = stateAt(helper, station.pos());
                if (before.hasProperty(WarehouseTerminalBlock.DISPLAY)) {
                    helper.assertValueEqual(after.getValue(WarehouseTerminalBlock.DISPLAY),
                            before.getValue(WarehouseTerminalBlock.DISPLAY).clockwise(), "the wrench turns the screen");
                    helper.assertValueEqual(after.getValue(HorizontalDirectionalBlock.FACING),
                            before.getValue(HorizontalDirectionalBlock.FACING), "and leaves the intake port alone");
                } else {
                    helper.assertValueEqual(after.getValue(HorizontalDirectionalBlock.FACING),
                            before.getValue(HorizontalDirectionalBlock.FACING).getClockWise(),
                            "the wrench turns the " + station.name());
                }

                InteractionResult aimed = click(helper, player, station.pos(), AllBlocks.MECHANICAL_ARM.asStack(),
                        false);
                assertNoScreen(helper, player, station, "the Mechanical Arm item");
                if (station.armTarget()) {
                    helper.assertTrue(aimed.consumesAction(),
                            "the arm item selects the " + station.name() + ": " + aimed);
                    assertNothingOnTop(helper, station, "the arm item");
                    helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "and no arm was used up");
                } else {
                    helper.assertBlockPresent(AllBlocks.MECHANICAL_ARM.get(), station.pos().above());
                    helper.setBlock(station.pos().above(), Blocks.AIR);
                }

                InteractionResult edited = click(helper, player, station.pos(), AllBlocks.CLIPBOARD.asStack(), false);
                helper.assertTrue(edited.consumesAction(), "the clipboard's own click: " + edited);
                assertNoScreen(helper, player, station, "a clipboard");
                assertNothingOnTop(helper, station, "a clipboard");
                helper.assertValueEqual(player.getMainHandItem()
                        .getOrDefault(AllDataComponents.CLIPBOARD_CONTENT, ClipboardContent.EMPTY).type(),
                        ClipboardType.EDITING, "the clipboard opened its own editor at the " + station.name());
                click(helper, player, station.pos(), AllBlocks.CLIPBOARD.asStack(), true);
                assertNoScreen(helper, player, station, "a sneaking click with a clipboard");
                helper.assertBlockPresent(AllBlocks.CLIPBOARD.get(), station.pos().above());
                helper.setBlock(station.pos().above(), Blocks.AIR);

                InteractionResult linked = click(helper, player, station.pos(), AllBlocks.DISPLAY_LINK.asStack(),
                        false);
                helper.assertTrue(linked.consumesAction(), "the Display Link's own click: " + linked);
                assertNoScreen(helper, player, station, "a Display Link");
                assertNothingOnTop(helper, station, "a Display Link");
                ClickToLinkData link = player.getMainHandItem().get(AllDataComponents.CLICK_TO_LINK_DATA);
                helper.assertTrue(link != null && link.selectedPos().equals(helper.absolutePos(station.pos())),
                        "the Display Link selected the " + station.name() + " as its source: " + link);

                InteractionResult goggles = click(helper, player, station.pos(), AllItems.GOGGLES.asStack(), false);
                helper.assertFalse(goggles.consumesAction(), "the goggles pass the click on: " + goggles);
                assertNoScreen(helper, player, station, "the goggles");
                player.gameMode.useItem(player, helper.getLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
                helper.assertTrue(AllItems.GOGGLES.isIn(player.getItemBySlot(EquipmentSlot.HEAD)),
                        "and the use the client sends next puts them on at the " + station.name());
                player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            }
        }).thenSucceed();
    }

    /**
     * A wrench keeps its click only where the player may build ({@code StationScreenClick#actsOnTheBlock}). In
     * adventure mode {@code ItemStack#useOn} answers {@code PASS} before {@code WrenchItem#useOn} turns anything, so a
     * kept click would go nowhere: the client would run on to the offhand, whose item vanilla then uses in front of the
     * station — offhand bread is eaten, the issue #26 symptom. Instead the wrench opens the screen like any other item
     * and the block stays as it was. The click is <b>consumed</b>, which is what keeps the client from going on to the
     * offhand ({@code Minecraft#startUseItem} returns after a consumed click).
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void stationsOpenForAWrenchInAdventureMode(GameTestHelper helper) {
        placeStations(helper);
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            player.setGameMode(GameType.ADVENTURE);
            helper.assertFalse(player.mayBuild(), "an adventure player may not build");
            for (Station station : stations()) {
                BlockState before = stateAt(helper, station.pos());
                InteractionResult result = click(helper, player, station.pos(), AllItems.WRENCH.asStack(), false);
                assertOpened(helper, player, station, result, "a wrench in adventure mode");
                helper.assertValueEqual(stateAt(helper, station.pos()), before,
                        "the " + station.name() + " after a wrench click in adventure mode");
                assertNothingOnTop(helper, station, "a wrench click in adventure mode");
            }
        }).thenSucceed();
    }

    /**
     * A <b>Deployer</b> holding a block still places it against a station, as before the fix: its fake player keeps
     * its item's click ({@code StationScreenClick#keepsItsOwnClick}), so the block's answer is not consumed and
     * Create's {@code DeployerHandler} goes on to {@code stack.useOn}, exactly the two calls asserted here. A screen
     * could not open for it anyway ({@code FakePlayer#openMenu} opens nothing), so consuming its click would only have
     * stopped the machine without a word. An empty-handed one is answered as before, and opens nothing.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void stationsLetDeployersPlaceAgainstThem(GameTestHelper helper) {
        placeStations(helper);
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            FakePlayer deployer = FakePlayerFactory.getMinecraft(helper.getLevel());
            deployer.setShiftKeyDown(false);
            try {
                for (Station station : stations()) {
                    BlockPos absolute = helper.absolutePos(station.pos());
                    BlockHitResult hit = topHit(absolute);
                    BlockState state = stateAt(helper, station.pos());
                    deployer.setItemInHand(InteractionHand.MAIN_HAND,
                            Items.COBBLESTONE.getDefaultInstance().copyWithCount(BLOCKS));
                    InteractionResult used = BlockHelper.invokeUse(state, helper.getLevel(), deployer,
                            InteractionHand.MAIN_HAND, hit);
                    helper.assertFalse(used.consumesAction(),
                            "the " + station.name() + " leaves a Deployer's block to the Deployer: " + used);
                    InteractionResult placed = deployer.getMainHandItem()
                            .useOn(new UseOnContext(deployer, InteractionHand.MAIN_HAND, hit));
                    helper.assertTrue(placed.consumesAction(), "which places it: " + placed);
                    helper.assertBlockPresent(Blocks.COBBLESTONE, station.pos().above());
                    helper.setBlock(station.pos().above(), Blocks.AIR);

                    deployer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    helper.assertTrue(BlockHelper.invokeUse(state, helper.getLevel(), deployer,
                            InteractionHand.MAIN_HAND, hit).consumesAction(),
                            "an empty-handed Deployer's click is the station's, as before");
                    helper.assertTrue(deployer.containerMenu == deployer.inventoryMenu,
                            "and opens nothing: " + deployer.containerMenu);
                }
            } finally {
                deployer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
        }).thenSucceed();
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    /** One of the three stations with a screen, at its place in the template. */
    private record Station(String name, BlockPos pos, BlockState state, Class<? extends AbstractContainerMenu> menu,
            boolean armTarget, boolean resumes) {
    }

    private static List<Station> stations() {
        return List.of(
                new Station("terminal", TERMINAL, WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState()
                        .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH), WarehouseTerminalMenu.class,
                        true, false),
                new Station("production station", PRODUCTION, WareworksBlocks.WAREHOUSE_PRODUCTION.getDefaultState()
                        .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH), ProductionMenu.class,
                        true, true),
                new Station("stock keeper", KEEPER, WareworksBlocks.WAREHOUSE_STOCK_KEEPER.getDefaultState()
                        .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH), StockKeeperMenu.class,
                        false, false));
    }

    private static void placeStations(GameTestHelper helper) {
        for (Station station : stations())
            helper.setBlock(station.pos(), station.state());
    }

    private static BlockState stateAt(GameTestHelper helper, BlockPos pos) {
        return helper.getLevel().getBlockState(helper.absolutePos(pos));
    }

    /** A hit on the top face, off its centre, where a player looking down at the block clicks. */
    private static BlockHitResult topHit(BlockPos absolute) {
        return new BlockHitResult(new Vec3(absolute.getX() + 0.3, absolute.getY() + 1.0, absolute.getZ() + 0.3),
                Direction.UP, absolute, false);
    }

    /**
     * One right-click with {@code held} in the main hand on the top face of the test-relative {@code pos}, sneaking or
     * not, through {@code ServerPlayerGameMode#useItemOn}: the interaction event, {@code onItemUseFirst}, the sneak
     * guard, the block's {@code useItemOn} and {@code useWithoutItem}, and {@code stack.useOn}, in vanilla's order.
     * <p>
     * The item's cooldown is cleared first, because each click here stands for a separate one a player makes at least a
     * moment later: Create's clipboard puts itself on a ten-tick cooldown, and this player is never ticked.
     */
    private static InteractionResult click(GameTestHelper helper, ClickingPlayer player, BlockPos pos, ItemStack held,
            boolean sneaking) {
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        player.setShiftKeyDown(sneaking);
        if (!held.isEmpty())
            player.getCooldowns().removeCooldown(held.getItem());
        BlockPos absolute = helper.absolutePos(pos);
        InteractionResult result = player.gameMode.useItemOn(player, helper.getLevel(),
                player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, topHit(absolute));
        player.setShiftKeyDown(false);
        return result;
    }

    /** The click was consumed and opened exactly this station's screen, which is then closed again. */
    private static void assertOpened(GameTestHelper helper, ClickingPlayer player, Station station,
            InteractionResult result, String what) {
        helper.assertTrue(result.consumesAction(), "a right-click with " + what + " on the " + station.name()
                + " is consumed, so the client neither places nor uses the item: " + result);
        helper.assertTrue(station.menu().isInstance(player.containerMenu), "a right-click with " + what
                + " opens the " + station.name() + "'s screen: " + player.containerMenu);
        player.closeContainer();
    }

    private static void assertNoScreen(GameTestHelper helper, ClickingPlayer player, Station station, String what) {
        AbstractContainerMenu open = player.containerMenu;
        if (open != player.inventoryMenu)
            player.closeContainer();
        helper.assertTrue(open == player.inventoryMenu,
                what + " opens no screen at the " + station.name() + ": " + open);
    }

    private static void assertNothingOnTop(GameTestHelper helper, Station station, String what) {
        helper.assertBlockPresent(Blocks.AIR, station.pos().above());
        helper.assertTrue(stateAt(helper, station.pos()).is(station.state().getBlock()),
                "the " + station.name() + " is still standing after " + what);
    }

    /** The player was told exactly these lines (relative lang keys) since the last check, which this one starts. */
    private static void assertTold(GameTestHelper helper, ClickingPlayer player, Station station, String what,
            String... relativeKeys) {
        List<String> expected = new ArrayList<>(relativeKeys.length);
        for (String key : relativeKeys)
            expected.add(WareworksLang.key(key));
        helper.assertValueEqual(player.toldKeys(), List.copyOf(expected), "what " + what + " at the " + station.name()
                + " told the player");
        player.told.clear();
    }
}
