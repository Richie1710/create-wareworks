package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllBlocks;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.remote.RemoteAccess;
import dev.wareworks.content.remote.RemoteLink;
import dev.wareworks.content.remote.WarehouseRemoteItem;
import dev.wareworks.content.station.TerminalCraftOutcome;
import dev.wareworks.content.station.TerminalCraftResult;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crafting.CraftGrid;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.network.TerminalCraftPayload;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksDataComponents;
import dev.wareworks.registry.WareworksItems;
import dev.wareworks.util.WareworksLang;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of the Warehouse Remote (issue #44, ADR-077): a held item that opens a linked terminal's screen from afar
 * and moves no item itself.
 * <ul>
 * <li>{@code remotelinksandunlinks} — a sneaking click on a terminal links the remote (position, dimension and the
 * terminal's identity), a plain click opens the terminal in person and links nothing, a sneaking click elsewhere
 * passes, linking again replaces the link, a sneaking use at a block, an entity or (in adventure mode) a terminal
 * keeps it, and a sneaking use into the air clears it;</li>
 * <li>{@code remoteopensthelinkedterminalfromafar} — a stocked aisle: the remote opens a remote menu a hundred blocks
 * away, a request from there is an ordinary request to the terminal and the crane delivers into its buffer, never into
 * the player's inventory; the menu stays valid while the hand holds the remote and not once it leaves that hand or the
 * player walks out of range;</li>
 * <li>{@code remoterefusals} — not linked, out of range, a chunk that is not loaded (and stays so), a terminal broken,
 * and another one placed on the same spot: each refused with its own message and no screen;</li>
 * <li>{@code remotescreenmovesnoitems} — buffer and list slots give and take nothing by any click, a shift-click moves
 * nothing, and a craft is {@code OUT_OF_REACH} from afar while the same craft works in person;</li>
 * <li>{@code remotefakeplayersneitherlinknoropen} — a Deployer's fake player links nothing, opens nothing and unlinks
 * nothing;</li>
 * <li>{@code remotelinksurvivesasave} — the terminal's identity is in its full save and not in a schematic's, so a
 * remote still opens a reloaded terminal;</li>
 * <li>{@code terminalisnotusablefromanotherdimension} — a player at the same coordinates of the Nether is not at an
 * Overworld terminal (the reach check of {@code canPlayerUse} now compares dimensions);</li>
 * <li>{@code remoteacrossdimensions} and {@code remoterangefollowstheconfig} ({@link #CONFIG_BATCH}) —
 * {@code remote.crossDimension} off and on with a terminal in the Nether, a link into a dimension the server does not
 * have, and {@code remote.range} at its floor;</li>
 * <li>{@code remotescreenletstheterminalschunkunload} ({@link #UNLOAD_BATCH}) — a screen open from afar closes when the
 * terminal's chunk stops ticking, and the chunk unloads: nothing the screen asks every tick holds it;</li>
 * <li>{@code remoterespectstheworldborder} ({@link #BORDER_BATCH}) — a terminal beyond the world border closes a screen
 * open from afar and refuses a use and a link with its own line.</li>
 * </ul>
 * <p>
 * Every click and use goes through the server's real dispatch ({@code ServerPlayerGameMode#useItemOn} and
 * {@code #useItem}) with a {@link ClickingPlayer}, which keeps what it was told on the action bar. The player is never
 * added to a level, so it can stand anywhere without loading a chunk, and no other test sees it. Distances are measured
 * from the eyes, as {@link RemoteAccess#distance} does, and taken from the config getters rather than the shipped
 * defaults.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class RemoteGameTests {
    /** The tests that override the server config, restored after the batch even when one of them fails. */
    static final String CONFIG_BATCH = "wareworksremoteconfig";
    /** The test that releases a chunk with a terminal in it, cleaned up after the batch even when it fails. */
    static final String UNLOAD_BATCH = "wareworksremoteunload";
    /** The test that moves the world border, which is the whole level's: alone in its batch, restored after it. */
    static final String BORDER_BATCH = "wareworksremoteborder";

    private static final BlockPos TERMINAL = new BlockPos(1, BASE_Y, 3);
    private static final BlockPos OTHER_TERMINAL = new BlockPos(5, BASE_Y, 3);
    /** Where the player stands to click: two blocks from the terminals, within reach of both. */
    private static final BlockPos STAND = new BlockPos(3, BASE_Y, 1);
    /** Between the two terminals, within an entity's reach from {@link #STAND}. */
    private static final BlockPos ARMOR_STAND = new BlockPos(3, BASE_Y, 3);
    /** Pitches straight down at the floor and straight up into the air above the test. */
    private static final float LOOK_DOWN = 90.0F;
    private static final float LOOK_UP = -90.0F;
    /** How far above the test the player stands to look into the air, in blocks: far above its encasing barriers. */
    private static final double SKY = 64.0;

    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final RackPosition TERMINAL_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition STORAGE_RACK = new RackPosition(1, 0, Side.LEFT);

    private static final int TIMEOUT_TICKS = 1200;
    private static final int IRON_IN_STOCK = 32;
    private static final int REQUESTED = 12;
    /** How far from the terminal "from afar" is, in blocks, at most: half the configured range if that is shorter. */
    private static final double AFAR = 100.0;
    /** How far beyond the configured range a player stands to be out of it, in blocks. */
    private static final double BEYOND_RANGE = 44.0;
    /** A position whose chunk no test loads: this far from the test along x and z, in blocks. */
    private static final int UNLOADED_OFFSET = 50_000;
    /** Where the unload test's terminal stands, this far from the test along x and z: away from every other test. */
    private static final int RELEASED_OFFSET = 40_000;
    /** How far east of the terminal the border test moves the world border's centre, and the border's size there. */
    private static final int BORDER_AWAY = 1_000;
    private static final double BORDER_SIZE = 16.0;
    /** A dimension the server does not have. */
    private static final ResourceKey<Level> MISSING_DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(Wareworks.ID, "missing"));
    /** The Nether height the cross-dimension terminal stands at: between the bedrock layers. */
    private static final int NETHER_Y = 64;
    /** Within the range the config floor allows ({@code RemoteLimits#MIN_RANGE} is 16). */
    private static final double INSIDE_FLOOR = 12.0;
    private static final double OUTSIDE_FLOOR = 20.0;
    /** A range below the config floor, which the getter clamps up to it. */
    private static final int BELOW_FLOOR = 5;
    private static final int FLOOR = 16;
    private static final int PLANKS_PER_CHEST = 8;
    /** A hotbar slot that stays empty, for the swap click. */
    private static final int EMPTY_HOTBAR_SLOT = 8;
    /** A main inventory slot the remote is moved into, out of the hand. */
    private static final int POCKET_SLOT = 20;

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey PLANK = ItemKey.of(Items.OAK_PLANKS);
    private static final ItemKey CHEST = ItemKey.of(Items.CHEST);
    private static final CraftGrid<ItemKey> CHEST_GRID = CraftGrid.of(PLANK, PLANK, PLANK, PLANK, null, PLANK, PLANK,
            PLANK, PLANK);

    /**
     * What the tests that reach beyond their own bounds changed (the cross-dimension test's Nether block and forced
     * chunk, the unload test's far terminal and forced chunk, the border test's world border), undone by the
     * {@code @AfterBatch} methods when a test failed before its own cleanup; touched only from the server thread.
     */
    private static final Deque<Runnable> CLEANUPS = new ArrayDeque<>();

    private RemoteGameTests() {
    }

    /**
     * Linking and unlinking. The link click is the remote's own ({@code onItemUseFirst}), and only on a terminal while
     * sneaking: a plain click passes it on, so the terminal opens in person as with any other item, and a sneaking
     * click on anything else links nothing. The link names the terminal's dimension, position and identity, and the
     * tooltip shows where it points.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void remoteLinksAndUnlinks(GameTestHelper helper) {
        placeTerminal(helper, TERMINAL);
        placeTerminal(helper, OTHER_TERMINAL);
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            ItemStack remote = WareworksItems.WAREHOUSE_REMOTE.asStack();
            player.setItemInHand(InteractionHand.MAIN_HAND, remote);
            helper.assertValueEqual(tooltipKeys(helper, remote), List.of(WareworksLang.key(
                    WareworksLang.REMOTE_TOOLTIP_UNLINKED)), "a new remote says it is not linked");

            // A plain click is not the remote's: the terminal opens in person, as for any other item.
            BlockPos terminalPos = helper.absolutePos(TERMINAL);
            helper.assertValueEqual(remote.onItemUseFirst(new UseOnContext(player, InteractionHand.MAIN_HAND,
                    topHit(terminalPos))), InteractionResult.PASS, "the remote passes a plain click on a terminal");
            InteractionResult plain = click(player, helper.getLevel(), terminalPos, false);
            helper.assertTrue(plain.consumesAction(), "a plain click on the terminal is consumed: " + plain);
            if (!(player.containerMenu instanceof WarehouseTerminalMenu inPerson) || inPerson.isRemote()
                    || inPerson.terminal() != terminalAt(helper, TERMINAL))
                throw new GameTestAssertException("a plain click opens the terminal in person: "
                        + player.containerMenu);
            player.closeContainer();
            assertLink(helper, remote, null, "and links nothing");
            assertTold(helper, player, "the plain click");

            // A sneaking click on anything but a terminal passes as well.
            BlockPos floor = helper.absolutePos(STAND.below());
            player.setShiftKeyDown(true);
            helper.assertValueEqual(remote.onItemUseFirst(new UseOnContext(player, InteractionHand.MAIN_HAND,
                    topHit(floor))), InteractionResult.PASS, "a sneaking click on the floor is not a link");
            player.setShiftKeyDown(false);
            click(player, helper.getLevel(), floor, true);
            assertLink(helper, remote, null, "the floor links nothing");
            assertNoScreen(helper, player, "a sneaking click on the floor");
            assertTold(helper, player, "the sneaking click on the floor");

            // The link click.
            InteractionResult linked = click(player, helper.getLevel(), terminalPos, true);
            helper.assertTrue(linked.consumesAction(), "the link click is consumed: " + linked);
            WarehouseTerminalBlockEntity terminal = terminalAt(helper, TERMINAL);
            assertLink(helper, remote, new RemoteLink(GlobalPos.of(Level.OVERWORLD,
                    terminalPos), terminal.terminalId()), "linked to this terminal's place and identity");
            assertNoScreen(helper, player, "the link click");
            assertTold(helper, player, "the link click", WareworksLang.REMOTE_LINKED);
            helper.assertValueEqual(tooltipKeys(helper, remote), List.of(WareworksLang.key(
                    WareworksLang.REMOTE_TOOLTIP_LINKED)), "the tooltip names the linked terminal");

            // Linking again replaces the link.
            BlockPos otherPos = helper.absolutePos(OTHER_TERMINAL);
            helper.assertTrue(click(player, helper.getLevel(), otherPos, true).consumesAction(), "the second link");
            assertLink(helper, remote, new RemoteLink(GlobalPos.of(Level.OVERWORLD,
                    otherPos), terminalAt(helper, OTHER_TERMINAL).terminalId()), "now linked to the other terminal");
            helper.assertFalse(terminalAt(helper, OTHER_TERMINAL).terminalId().equals(terminal.terminalId()),
                    "two terminals, two identities");
            assertTold(helper, player, "the second link", WareworksLang.REMOTE_LINKED);

            // A sneaking use clears the link only into the air. Pointed at a block (the floor: a link click that
            // missed), at a terminal the game did not let the link click through to (adventure mode) or at an entity,
            // it passes and keeps the link, so the other hand can still place its block.
            RemoteLink kept = linkOf(remote);
            player.setXRot(LOOK_DOWN);
            helper.assertValueEqual(use(player, true), InteractionResult.PASS, "a sneaking use at the floor passes");
            player.setGameMode(GameType.ADVENTURE);
            player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(terminalPos));
            helper.assertFalse(click(player, helper.getLevel(), terminalPos, true).consumesAction(),
                    "in adventure mode the link click does not reach the remote");
            helper.assertValueEqual(use(player, true), InteractionResult.PASS,
                    "and the sneaking use at the terminal that follows it passes");
            player.setGameMode(GameType.SURVIVAL);
            ArmorStand stand = helper.spawn(EntityType.ARMOR_STAND, Vec3.atBottomCenterOf(ARMOR_STAND));
            player.lookAt(EntityAnchorArgument.Anchor.EYES, stand.getBoundingBox().getCenter());
            helper.assertValueEqual(use(player, true), InteractionResult.PASS,
                    "a sneaking use at an armor stand passes");
            stand.discard();
            assertLink(helper, remote, kept, "a sneaking use at a block or an entity keeps the link");
            assertTold(helper, player, "the sneaking uses at a block or an entity");

            // A sneaking use into the air clears the link; with no link it passes and says nothing. The air is above
            // the test: the barriers that encase it lie within a player's reach.
            Vec3 feet = player.position();
            player.moveTo(feet.x, feet.y + SKY, feet.z, player.getYRot(), LOOK_UP);
            InteractionResult unlinked = use(player, true);
            helper.assertTrue(unlinked.consumesAction(), "the unlink is consumed: " + unlinked);
            assertLink(helper, remote, null, "the link is cleared");
            helper.assertFalse(remote.has(WareworksDataComponents.REMOTE_LINK.get()), "the component is gone");
            assertTold(helper, player, "the unlink", WareworksLang.REMOTE_UNLINKED);
            helper.assertValueEqual(use(player, true), InteractionResult.PASS, "an unlinked remote passes the unlink");
            assertTold(helper, player, "the unlink of an unlinked remote");
            assertNoScreen(helper, player, "the unlinks");
            helper.assertTrue(player.getMainHandItem() == remote, "the remote stayed in the hand throughout");
        }).thenSucceed();
    }

    /**
     * The whole remote on a stocked aisle: linked at the terminal, used a hundred blocks away it opens a remote menu of
     * that terminal, and a request from there is an ordinary request <b>to the terminal</b>: the crane delivers into
     * its buffer, and nothing arrives in the player's inventory. The menu stays valid exactly while the bound hand
     * holds the remote within range: moved into the inventory, held in the other hand only, or carried out of range, it
     * is not, and a request is then refused like any request from a player out of reach.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void remoteOpensTheLinkedTerminalFromAfar(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK, IRON.toStack(IRON_IN_STOCK));
        aisle.terminal(TERMINAL_RACK);
        BlockPos terminalPos = aisle.absoluteRackPos(TERMINAL_RACK);
        ClickingPlayer player = ClickingPlayer.at(helper, aisle.rackPos(TERMINAL_RACK).above());
        ItemStack remote = WareworksItems.WAREHOUSE_REMOTE.asStack();
        player.setItemInHand(InteractionHand.MAIN_HAND, remote);
        List<Map<ItemKey, Long>> conserved = new ArrayList<>();
        List<WarehouseTerminalMenu> opened = new ArrayList<>();

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    helper.assertTrue(click(player, helper.getLevel(), terminalPos, true).consumesAction(), "linked");
                    assertTold(helper, player, "the link", WareworksLang.REMOTE_LINKED);
                    conserved.add(ItemCensus.take(helper, player));

                    moveEyes(player, terminalPos, afar());
                    helper.assertTrue(use(player, false).consumesAction(), "the use from afar is consumed");
                    WarehouseTerminalMenu menu = remoteMenu(helper, player, aisle.terminalAt(TERMINAL_RACK),
                            "the use from afar");
                    opened.add(menu);
                    assertTold(helper, player, "the use from afar");
                    helper.assertTrue(player.getCooldowns().isOnCooldown(remote.getItem()),
                            "an opened screen puts the remote on its cooldown");
                    helper.assertValueEqual(menu.remote().orElseThrow().hand(), InteractionHand.MAIN_HAND,
                            "bound to the hand it was used from");
                    helper.assertValueEqual(menu.terminalPos(), terminalPos, "the menu knows where the terminal is");
                    helper.assertValueEqual(menu.terminalDimension(), Level.OVERWORLD, "and in which dimension");
                    helper.assertTrue(menu.stillValid(player), "the screen stays open while the remote is held");

                    Optional<RequestResult> result = WarehouseTerminalMenu.submitRequest(player, menu.containerId,
                            IRON, REQUESTED);
                    helper.assertTrue(result.isPresent() && result.get().isAccepted(),
                            "a request from afar is accepted: " + result);
                    RetrievalRequest<ItemKey, BlockPos> request = result.get().request().orElseThrow();
                    helper.assertValueEqual(request.destination(), terminalPos, "and is for the terminal itself");
                    helper.assertValueEqual(aisle.controller().requestsFor(terminalPos).size(), 1,
                            "one request at the terminal");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, IRON),
                        (long) REQUESTED, "the crane delivered into the terminal's buffer"))
                .thenExecute(() -> {
                    ItemCensus.assertEquals(helper, conserved.getFirst(), "after the delivery", player);
                    helper.assertValueEqual(countIn(player, Items.IRON_INGOT), 0, "nothing came into the inventory");
                    WarehouseTerminalMenu menu = opened.getFirst();
                    helper.assertTrue(player.containerMenu == menu && menu.stillValid(player),
                            "the screen is still open from afar");

                    // Out of the hand: into the inventory, then into the other hand only.
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    player.getInventory().setItem(POCKET_SLOT, remote);
                    helper.assertFalse(menu.stillValid(player), "a remote put away closes the screen");
                    helper.assertValueEqual(WarehouseTerminalMenu.submitRequest(player, menu.containerId, IRON, 1)
                            .flatMap(RequestResult::rejection), Optional.of(RequestRejection.OUT_OF_REACH),
                            "and a request without it is out of reach");
                    player.getInventory().setItem(POCKET_SLOT, ItemStack.EMPTY);
                    player.setItemInHand(InteractionHand.OFF_HAND, remote);
                    helper.assertFalse(menu.stillValid(player), "the other hand is not the one bound to the screen");
                    player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                    player.setItemInHand(InteractionHand.MAIN_HAND, remote);
                    helper.assertTrue(menu.stillValid(player), "back in the bound hand, it is valid again");

                    // Out of range.
                    moveEyes(player, terminalPos, WareworksConfig.remoteRange() + BEYOND_RANGE);
                    helper.assertFalse(menu.stillValid(player), "out of range, the screen closes");
                    moveEyes(player, terminalPos, afar());
                    helper.assertTrue(menu.stillValid(player), "and within range again it would not");
                    player.closeContainer();
                    ItemCensus.assertEquals(helper, conserved.getFirst(), "after the screen closed", player);
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * Every refusal of a use, each with its own action bar line and no screen: not linked; out of range; a terminal
     * whose chunk is not loaded (which the use does not load); a terminal broken, which also closes a screen opened
     * from afar; another terminal placed on the same spot, which has another identity. Linking that new terminal makes
     * the remote work again.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void remoteRefusals(GameTestHelper helper) {
        placeTerminal(helper, TERMINAL);
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ServerLevel level = helper.getLevel();
            BlockPos terminalPos = helper.absolutePos(TERMINAL);
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            ItemStack remote = WareworksItems.WAREHOUSE_REMOTE.asStack();
            player.setItemInHand(InteractionHand.MAIN_HAND, remote);

            helper.assertValueEqual(use(player, false), InteractionResult.PASS, "an unlinked remote passes");
            assertNoScreen(helper, player, "an unlinked remote");
            assertTold(helper, player, "an unlinked remote", WareworksLang.REMOTE_NOT_LINKED);

            click(player, level, terminalPos, true);
            assertTold(helper, player, "the link", WareworksLang.REMOTE_LINKED);
            int range = WareworksConfig.remoteRange();
            moveEyes(player, terminalPos, range + BEYOND_RANGE);
            use(player, false);
            assertNoScreen(helper, player, "out of range");
            List<Object> args = toldArgs(player);
            assertTold(helper, player, "out of range", WareworksLang.REMOTE_OUT_OF_RANGE);
            helper.assertTrue(args.size() == 2 && args.get(0) instanceof Long shown && shown > range
                    && Integer.valueOf(range).equals(args.get(1)), "the message names the distance and the range: "
                            + args);
            helper.assertFalse(player.getCooldowns().isOnCooldown(remote.getItem()), "a refusal sets no cooldown");

            // A terminal whose chunk is not loaded: refused before its block entity is read, which would load it.
            BlockPos unloaded = terminalPos.offset(UNLOADED_OFFSET, 0, UNLOADED_OFFSET);
            helper.assertFalse(level.isLoaded(unloaded), "the far chunk is not loaded to begin with");
            ItemStack farRemote = remote.copy();
            farRemote.set(WareworksDataComponents.REMOTE_LINK.get(), new RemoteLink(GlobalPos.of(Level.OVERWORLD,
                    unloaded), linkOf(remote).terminalId()));
            player.setItemInHand(InteractionHand.MAIN_HAND, farRemote);
            moveEyes(player, unloaded, 2.0);
            use(player, false);
            assertNoScreen(helper, player, "an unloaded terminal");
            assertTold(helper, player, "an unloaded terminal", WareworksLang.REMOTE_NOT_LOADED);
            helper.assertFalse(level.isLoaded(unloaded), "and the use did not load its chunk");
            player.setItemInHand(InteractionHand.MAIN_HAND, remote);

            // Broken: an open remote screen closes, and the remote finds nothing.
            moveEyes(player, terminalPos, afar());
            use(player, false);
            WarehouseTerminalMenu menu = remoteMenu(helper, player, terminalAt(helper, TERMINAL), "before the break");
            helper.setBlock(TERMINAL, Blocks.AIR);
            helper.assertFalse(menu.stillValid(player), "a broken terminal closes the screen opened from afar");
            player.closeContainer();
            use(player, false);
            assertNoScreen(helper, player, "a broken terminal");
            assertTold(helper, player, "a broken terminal", WareworksLang.REMOTE_GONE);

            // Another terminal on the same spot is not the linked one.
            placeTerminal(helper, TERMINAL);
            use(player, false);
            assertNoScreen(helper, player, "a replaced terminal");
            assertTold(helper, player, "a replaced terminal", WareworksLang.REMOTE_GONE);
            helper.assertFalse(terminalAt(helper, TERMINAL).terminalId().equals(
                    linkOf(remote).terminalId()), "the new terminal has another identity");

            // Linked to the new one, it works again.
            moveEyes(player, terminalPos, 2.0);
            click(player, level, terminalPos, true);
            assertTold(helper, player, "the new link", WareworksLang.REMOTE_LINKED);
            moveEyes(player, terminalPos, afar());
            helper.assertTrue(use(player, false).consumesAction(), "the use after the new link");
            remoteMenu(helper, player, terminalAt(helper, TERMINAL), "after the new link");
            assertTold(helper, player, "the use after the new link");
            player.closeContainer();
        }).thenSucceed();
    }

    /**
     * A screen opened from afar moves no item between the terminal and the player: no click takes from a buffer slot
     * (pick up, shift-click, swap with the hotbar, throw), the clipboard slot takes no clipboard and gives none that
     * lies in it by any of those clicks, a
     * shift-click from the inventory moves nothing, and a craft is {@link TerminalCraftResult#OUT_OF_REACH} with the
     * buffer untouched. The same player at the terminal in person crafts the same chest at once.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void remoteScreenMovesNoItems(GameTestHelper helper) {
        placeTerminal(helper, TERMINAL);
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ServerLevel level = helper.getLevel();
            BlockPos terminalPos = helper.absolutePos(TERMINAL);
            WarehouseTerminalBlockEntity terminal = terminalAt(helper, TERMINAL);
            helper.assertTrue(terminal.insert(PLANK.toStack(PLANKS_PER_CHEST), false).isEmpty(), "planks seeded");
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            ItemStack remote = WareworksItems.WAREHOUSE_REMOTE.asStack();
            player.setItemInHand(InteractionHand.MAIN_HAND, remote);
            ItemStack clipboard = AllBlocks.CLIPBOARD.asStack();
            player.getInventory().setItem(POCKET_SLOT, clipboard);
            click(player, level, terminalPos, true);
            Map<ItemKey, Long> conserved = ItemCensus.take(helper, player);

            moveEyes(player, terminalPos, afar());
            use(player, false);
            WarehouseTerminalMenu menu = remoteMenu(helper, player, terminal, "the use from afar");
            Slot buffer = menu.getSlot(0);
            Slot list = menu.getSlot(menu.listSlotIndex());
            helper.assertTrue(buffer.hasItem(), "the planks show in the first buffer slot");
            helper.assertFalse(buffer.mayPickup(player), "a buffer slot gives nothing from afar");
            helper.assertFalse(list.mayPlace(clipboard), "the list slot takes no clipboard from afar");

            menu.clicked(0, 0, ClickType.PICKUP, player);
            helper.assertTrue(menu.getCarried().isEmpty(), "a click on the buffer slot picks nothing up");
            helper.assertTrue(menu.quickMoveStack(player, 0).isEmpty(), "a shift-click on it moves nothing");
            menu.clicked(0, 0, ClickType.QUICK_MOVE, player);
            menu.clicked(0, EMPTY_HOTBAR_SLOT, ClickType.SWAP, player);
            menu.clicked(0, 1, ClickType.THROW, player);
            int pocket = menuSlotOf(helper, menu, player, POCKET_SLOT);
            helper.assertTrue(menu.quickMoveStack(player, pocket).isEmpty(),
                    "a shift-click on the clipboard in the inventory moves nothing");
            menu.clicked(pocket, 0, ClickType.QUICK_MOVE, player);
            helper.assertTrue(player.getInventory().getItem(POCKET_SLOT) == clipboard, "the clipboard stayed put");
            helper.assertFalse(list.hasItem(), "and the list slot is still empty");
            helper.assertValueEqual(terminal.bufferedItems().count(PLANK), (long) PLANKS_PER_CHEST,
                    "every plank is still in the buffer");

            // A clipboard lying in the list slot stays there: no click gives it out from afar. In person the slot
            // hands it back (WarehouseTerminalMenuGameTests), so only the remote rule keeps it in.
            ItemStack listed = AllBlocks.CLIPBOARD.asStack();
            terminal.listSlot().setStackInSlot(WarehouseTerminalBlockEntity.LIST_SLOT, listed);
            int listIndex = menu.listSlotIndex();
            helper.assertTrue(list.hasItem(), "a clipboard lies in the list slot");
            helper.assertFalse(list.mayPickup(player), "the list slot gives it nothing from afar");
            menu.clicked(listIndex, 0, ClickType.PICKUP, player);
            helper.assertTrue(menu.getCarried().isEmpty(), "a click on the list slot picks nothing up");
            helper.assertTrue(menu.quickMoveStack(player, listIndex).isEmpty(), "a shift-click on it moves nothing");
            menu.clicked(listIndex, 0, ClickType.QUICK_MOVE, player);
            menu.clicked(listIndex, EMPTY_HOTBAR_SLOT, ClickType.SWAP, player);
            menu.clicked(listIndex, 1, ClickType.THROW, player);
            helper.assertTrue(terminal.listClipboard() == listed && listed.getCount() == 1,
                    "the clipboard is still in the list slot: " + terminal.listClipboard());
            helper.assertValueEqual(countIn(player, AllBlocks.CLIPBOARD.asItem()), 1,
                    "and the player holds only their own clipboard");
            helper.assertTrue(player.getInventory().getItem(EMPTY_HOTBAR_SLOT).isEmpty(), "the swap moved nothing");

            TerminalCraftOutcome refused = WarehouseTerminalMenu.submitCraft(player, menu.containerId,
                    TerminalCraftPayload.Action.CRAFT, CHEST_GRID, Optional.empty()).orElse(null);
            helper.assertTrue(refused != null && refused.result() == TerminalCraftResult.OUT_OF_REACH,
                    "a craft from afar is out of reach: " + refused);
            helper.assertValueEqual(terminal.bufferedItems().count(PLANK), (long) PLANKS_PER_CHEST,
                    "and used no plank");
            helper.assertValueEqual(countIn(player, Items.CHEST), 0, "nor made a chest");
            ItemCensus.assertEquals(helper, conserved, "after every click from afar", player);
            player.closeContainer();

            // In person the same craft works, so the refusal above was the remote's.
            moveEyes(player, terminalPos, 2.0);
            helper.assertTrue(terminal.openScreen(player), "the screen opens in person");
            WarehouseTerminalMenu inPerson = (WarehouseTerminalMenu) player.containerMenu;
            helper.assertFalse(inPerson.isRemote(), "an in-person screen");
            TerminalCraftOutcome crafted = WarehouseTerminalMenu.submitCraft(player, inPerson.containerId,
                    TerminalCraftPayload.Action.CRAFT, CHEST_GRID, Optional.empty()).orElse(null);
            helper.assertTrue(crafted != null && crafted.result() == TerminalCraftResult.CRAFTED,
                    "in person the chest is crafted: " + crafted);
            helper.assertValueEqual(countIn(player, Items.CHEST), 1, "into the player's hands");
            ItemCensus.change(conserved, PLANK, -PLANKS_PER_CHEST);
            ItemCensus.change(conserved, CHEST, 1);
            ItemCensus.assertEquals(helper, conserved, "after the craft in person", player);
            player.closeContainer();
            terminal.listSlot().setStackInSlot(WarehouseTerminalBlockEntity.LIST_SLOT, ItemStack.EMPTY);
        }).thenSucceed();
    }

    /**
     * A Deployer's fake player: no link (also when it sneaks at a terminal), no screen and no unlink. The remote moves
     * no item and needs a player's own hand at a screen, which a Deployer does not have.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void remoteFakePlayersNeitherLinkNorOpen(GameTestHelper helper) {
        placeTerminal(helper, TERMINAL);
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ServerLevel level = helper.getLevel();
            BlockPos terminalPos = helper.absolutePos(TERMINAL);
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            ItemStack linked = WareworksItems.WAREHOUSE_REMOTE.asStack();
            player.setItemInHand(InteractionHand.MAIN_HAND, linked);
            click(player, level, terminalPos, true);
            RemoteLink link = WarehouseRemoteItem.link(linked);
            helper.assertTrue(link != null, "the player's remote is linked");

            FakePlayer deployer = FakePlayerFactory.getMinecraft(level);
            Vec3 feet = Vec3.atBottomCenterOf(helper.absolutePos(STAND));
            deployer.moveTo(feet.x, feet.y, feet.z);
            ItemStack fresh = WareworksItems.WAREHOUSE_REMOTE.asStack();
            try {
                deployer.setItemInHand(InteractionHand.MAIN_HAND, fresh);
                deployer.setShiftKeyDown(true);
                helper.assertValueEqual(fresh.onItemUseFirst(new UseOnContext(deployer, InteractionHand.MAIN_HAND,
                        topHit(terminalPos))), InteractionResult.PASS, "a fake player's sneaking click is no link");
                helper.assertFalse(RemoteAccess.link(deployer, fresh, level, terminalPos),
                        "and the server links nothing for it");
                assertLink(helper, fresh, null, "the fake player's remote is unlinked");

                deployer.setItemInHand(InteractionHand.MAIN_HAND, linked);
                helper.assertValueEqual(linked.use(level, deployer, InteractionHand.MAIN_HAND).getResult(),
                        InteractionResult.PASS, "a fake player's sneaking use passes");
                assertLink(helper, linked, link, "and unlinks nothing");
                deployer.setShiftKeyDown(false);
                helper.assertValueEqual(linked.use(level, deployer, InteractionHand.MAIN_HAND).getResult(),
                        InteractionResult.PASS, "a fake player's use passes");
                helper.assertFalse(RemoteAccess.open(deployer, InteractionHand.MAIN_HAND, linked),
                        "and opens nothing");
                helper.assertTrue(deployer.containerMenu == deployer.inventoryMenu,
                        "no screen: " + deployer.containerMenu);
                helper.assertFalse(deployer.getCooldowns().isOnCooldown(linked.getItem()), "and sets no cooldown");
            } finally {
                deployer.setShiftKeyDown(false);
                deployer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
        }).thenSucceed();
    }

    /**
     * The terminal's identity is part of its full save, so a remote still opens the terminal after a save and reload,
     * but not of what a schematic keeps ({@code writeSafe}), so a printed copy is another terminal.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void remoteLinkSurvivesASave(GameTestHelper helper) {
        placeTerminal(helper, TERMINAL);
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ServerLevel level = helper.getLevel();
            HolderLookup.Provider registries = level.registryAccess();
            BlockPos terminalPos = helper.absolutePos(TERMINAL);
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            ItemStack remote = WareworksItems.WAREHOUSE_REMOTE.asStack();
            player.setItemInHand(InteractionHand.MAIN_HAND, remote);
            click(player, level, terminalPos, true);
            assertTold(helper, player, "the link", WareworksLang.REMOTE_LINKED);
            WarehouseTerminalBlockEntity terminal = terminalAt(helper, TERMINAL);

            CompoundTag full = terminal.saveWithFullMetadata(registries);
            helper.assertValueEqual(full.getUUID(WarehouseTerminalBlockEntity.TERMINAL_ID_TAG), terminal.terminalId(),
                    "the full save keeps the identity");
            CompoundTag safe = new CompoundTag();
            terminal.writeSafe(safe, registries);
            helper.assertFalse(safe.contains(WarehouseTerminalBlockEntity.TERMINAL_ID_TAG),
                    "what a schematic keeps does not");

            BlockEntity loaded = BlockEntity.loadStatic(terminalPos, terminal.getBlockState(), full, registries);
            if (!(loaded instanceof WarehouseTerminalBlockEntity reloaded))
                throw new GameTestAssertException("a saved terminal loads again as one: " + loaded);
            level.setBlockEntity(reloaded);
            helper.assertTrue(terminal.isRemoved() && terminalAt(helper, TERMINAL) == reloaded,
                    "the terminal was replaced by its reloaded copy");
            helper.assertValueEqual(reloaded.terminalId(), linkOf(remote).terminalId(),
                    "with the identity the remote names");

            moveEyes(player, terminalPos, afar());
            helper.assertTrue(use(player, false).consumesAction(), "the use after the reload");
            remoteMenu(helper, player, reloaded, "after the reload");
            assertTold(helper, player, "the use after the reload");
            player.closeContainer();
        }).thenSucceed();
    }

    /**
     * In person means in the terminal's own dimension: a player standing at the terminal's coordinates in the Nether
     * cannot use an Overworld terminal, nor keep its screen open, though the vanilla distance rule alone would let
     * them. Nothing is placed in the Nether.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void terminalIsNotUsableFromAnotherDimension(GameTestHelper helper) {
        placeTerminal(helper, TERMINAL);
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ServerLevel nether = nether(helper);
            WarehouseTerminalBlockEntity terminal = terminalAt(helper, TERMINAL);
            Vec3 feet = Vec3.atBottomCenterOf(helper.absolutePos(STAND));
            ClickingPlayer here = ClickingPlayer.in(helper.getLevel(), feet);
            ClickingPlayer there = ClickingPlayer.in(nether, feet);
            helper.assertTrue(terminal.canPlayerUse(here), "a player beside the terminal may use it");
            helper.assertFalse(terminal.canPlayerUse(there),
                    "a player at the same coordinates in the Nether may not");
            WarehouseTerminalMenu menu = WarehouseTerminalMenu.create(there.containerMenu.containerId + 1,
                    there.getInventory(), terminal);
            there.containerMenu = menu;
            helper.assertFalse(menu.stillValid(there), "nor keep its screen open");
            there.containerMenu = there.inventoryMenu;
        }).thenSucceed();
    }

    /**
     * {@code remote.crossDimension} with a terminal in the Nether and the player in the Overworld, far beyond any
     * Overworld range: off, the use is refused for the dimension; on, the screen opens without a range check and stays
     * open; switched off again, the open screen closes. A link into a dimension the server does not have is refused
     * as gone. The link and its tooltip name the Nether. The terminal's Nether
     * chunk is force-loaded for the test and released afterwards, and the block put back.
     */
    @GameTest(template = EMPTY_7X5X7, batch = CONFIG_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void remoteAcrossDimensions(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        BlockPos testPos = helper.absolutePos(TERMINAL);
        BlockPos netherPos = new BlockPos(testPos.getX(), NETHER_Y, testPos.getZ());
        ChunkPos chunk = new ChunkPos(netherPos);
        if (nether.setChunkForced(chunk.x, chunk.z, true))
            CLEANUPS.push(() -> nether.setChunkForced(chunk.x, chunk.z, false));
        BlockState[] before = new BlockState[1];

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(nether.isLoaded(netherPos)
                        && nether.shouldTickBlocksAt(netherPos), "the Nether chunk is loaded and ticking"))
                .thenExecute(() -> {
                    before[0] = nether.getBlockState(netherPos);
                    nether.setBlockAndUpdate(netherPos, terminalState());
                    CLEANUPS.push(() -> nether.setBlockAndUpdate(netherPos, before[0]));
                })
                .thenIdle(1)
                .thenExecute(() -> {
                    if (!(nether.getBlockEntity(netherPos) instanceof WarehouseTerminalBlockEntity terminal))
                        throw new GameTestAssertException("a terminal stands in the Nether");
                    ClickingPlayer linker = ClickingPlayer.in(nether, Vec3.atBottomCenterOf(netherPos.north(2)));
                    ItemStack remote = WareworksItems.WAREHOUSE_REMOTE.asStack();
                    linker.setItemInHand(InteractionHand.MAIN_HAND, remote);
                    helper.assertTrue(click(linker, nether, netherPos, true).consumesAction(), "linked in the Nether");
                    assertLink(helper, remote, new RemoteLink(GlobalPos.of(Level.NETHER,
                            netherPos), terminal.terminalId()), "the link names the Nether");
                    helper.assertValueEqual(tooltipKeys(helper, remote), List.of(WareworksLang.key(
                            WareworksLang.REMOTE_TOOLTIP_LINKED_IN)), "and so does the tooltip");
                    linker.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

                    ClickingPlayer player = ClickingPlayer.in(helper.getLevel(), Vec3.atBottomCenterOf(testPos));
                    player.setItemInHand(InteractionHand.MAIN_HAND, remote);
                    moveEyes(player, testPos, WareworksConfig.remoteRange() * 4.0 + UNLOADED_OFFSET);

                    ConfigOverrides.set(helper, WareworksConfig.SERVER.remoteCrossDimension, false);
                    use(player, false);
                    assertNoScreen(helper, player, "another dimension, not allowed");
                    assertTold(helper, player, "another dimension, not allowed", WareworksLang.REMOTE_OTHER_DIMENSION);

                    ConfigOverrides.set(helper, WareworksConfig.SERVER.remoteCrossDimension, true);
                    helper.assertTrue(use(player, false).consumesAction(), "another dimension, allowed");
                    WarehouseTerminalMenu menu = remoteMenu(helper, player, terminal, "another dimension, allowed");
                    assertTold(helper, player, "another dimension, allowed");
                    helper.assertValueEqual(menu.terminalDimension(), Level.NETHER, "the menu knows the dimension");
                    helper.assertTrue(menu.stillValid(player), "the screen stays open, unmeasured");

                    ConfigOverrides.set(helper, WareworksConfig.SERVER.remoteCrossDimension, false);
                    helper.assertFalse(menu.stillValid(player), "switched off, the open screen closes");
                    player.closeContainer();

                    // A link into a dimension the server does not have (a removed datapack's or mod's).
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.remoteCrossDimension, true);
                    ItemStack lost = remote.copy();
                    lost.set(WareworksDataComponents.REMOTE_LINK.get(), new RemoteLink(GlobalPos.of(MISSING_DIMENSION,
                            netherPos), terminal.terminalId()));
                    player.setItemInHand(InteractionHand.MAIN_HAND, lost);
                    use(player, false);
                    assertNoScreen(helper, player, "a dimension that is gone");
                    assertTold(helper, player, "a dimension that is gone", WareworksLang.REMOTE_GONE);
                    ConfigOverrides.restoreAll();
                    cleanUp();
                })
                .thenSucceed();
    }

    /**
     * {@code remote.range} at its floor: a player twelve blocks away opens the screen, twenty blocks away is refused
     * and an open screen closes there; a range below the floor counts as the floor ({@code RemoteLimits#clampRange}).
     */
    @GameTest(template = EMPTY_7X5X7, batch = CONFIG_BATCH)
    public static void remoteRangeFollowsTheConfig(GameTestHelper helper) {
        placeTerminal(helper, TERMINAL);
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            BlockPos terminalPos = helper.absolutePos(TERMINAL);
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            ItemStack remote = WareworksItems.WAREHOUSE_REMOTE.asStack();
            player.setItemInHand(InteractionHand.MAIN_HAND, remote);
            click(player, helper.getLevel(), terminalPos, true);
            assertTold(helper, player, "the link", WareworksLang.REMOTE_LINKED);

            ConfigOverrides.set(helper, WareworksConfig.SERVER.remoteRange, FLOOR);
            moveEyes(player, terminalPos, OUTSIDE_FLOOR);
            use(player, false);
            assertNoScreen(helper, player, "beyond the floor range");
            assertTold(helper, player, "beyond the floor range", WareworksLang.REMOTE_OUT_OF_RANGE);

            moveEyes(player, terminalPos, INSIDE_FLOOR);
            helper.assertTrue(use(player, false).consumesAction(), "within the floor range");
            WarehouseTerminalMenu menu = remoteMenu(helper, player, terminalAt(helper, TERMINAL), "within range");
            assertTold(helper, player, "within the floor range");
            moveEyes(player, terminalPos, OUTSIDE_FLOOR);
            helper.assertFalse(menu.stillValid(player), "walking beyond it closes the screen");
            moveEyes(player, terminalPos, INSIDE_FLOOR);
            helper.assertTrue(menu.stillValid(player), "within it again, it would stay open");

            ConfigOverrides.set(helper, WareworksConfig.SERVER.remoteRange, BELOW_FLOOR);
            helper.assertValueEqual(WareworksConfig.remoteRange(), FLOOR, "a range below the floor is clamped");
            helper.assertTrue(menu.stillValid(player), "so twelve blocks still reach");
            player.closeContainer();
            ConfigOverrides.restoreAll();
        }).thenSucceed();
    }

    /**
     * A screen held open from afar keeps no chunk loaded. A terminal far from every test stands in a chunk the test
     * force-loads; its screen is opened from afar, and the chunk is released. Ticked the way the server ticks an open
     * menu ({@link #tickScreen}), the screen closes once the chunk stops ticking, and the chunk then unloads, the
     * terminal's block entity with it. Asking every tick whether the screen may stay open must read no block entity:
     * {@code Level#getBlockEntity} renews a chunk ticket on each call and would hold the chunk for as long as the screen
     * is open. The block is put back afterwards.
     */
    @GameTest(template = EMPTY_7X5X7, batch = UNLOAD_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void remoteScreenLetsTheTerminalsChunkUnload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos farPos = helper.absolutePos(TERMINAL).offset(RELEASED_OFFSET, 0, RELEASED_OFFSET);
        ChunkPos chunk = new ChunkPos(farPos);
        if (level.setChunkForced(chunk.x, chunk.z, true))
            CLEANUPS.push(() -> level.setChunkForced(chunk.x, chunk.z, false));
        ClickingPlayer player = ClickingPlayer.at(helper, STAND);
        player.setItemInHand(InteractionHand.MAIN_HAND, WareworksItems.WAREHOUSE_REMOTE.asStack());
        List<WarehouseTerminalBlockEntity> placed = new ArrayList<>();

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isLoaded(farPos) && level.shouldTickBlocksAt(farPos),
                        "the far chunk is loaded and ticking"))
                .thenExecute(() -> {
                    BlockState before = level.getBlockState(farPos);
                    level.setBlockAndUpdate(farPos, terminalState());
                    CLEANUPS.push(() -> level.setBlockAndUpdate(farPos, before));
                })
                .thenIdle(1)
                .thenExecute(() -> {
                    if (!(level.getBlockEntity(farPos) instanceof WarehouseTerminalBlockEntity terminal))
                        throw new GameTestAssertException("a terminal stands in the far chunk");
                    placed.add(terminal);
                    moveEyes(player, farPos, 2.0);
                    helper.assertTrue(click(player, level, farPos, true).consumesAction(), "linked to the far terminal");
                    assertTold(helper, player, "the link", WareworksLang.REMOTE_LINKED);
                    moveEyes(player, farPos, afar());
                    helper.assertTrue(use(player, false).consumesAction(), "the use from afar");
                    remoteMenu(helper, player, terminal, "the use from afar");
                    assertTold(helper, player, "the use from afar");
                    level.setChunkForced(chunk.x, chunk.z, false);
                })
                .thenWaitUntil(() -> {
                    tickScreen(player);
                    helper.assertTrue(player.containerMenu == player.inventoryMenu,
                            "the screen closes once the terminal's chunk stops ticking");
                })
                .thenWaitUntil(() -> {
                    tickScreen(player);
                    helper.assertTrue(!level.isLoaded(farPos) && placed.getFirst().isRemoved(),
                            "the terminal's chunk unloads, and its block entity with it");
                })
                .thenExecute(RemoteGameTests::cleanUp)
                .thenSucceed();
    }

    /**
     * Where a player may not use a block ({@code Level#mayInteract}: spawn protection, which a GameTest server never
     * applies, and the world border, which it does), the remote does not reach either: with the border moved away from
     * the terminal, a screen already open from afar closes, a use is refused with its own line, and a link click links
     * nothing; with the border back, the remote opens the screen again.
     */
    @GameTest(template = EMPTY_7X5X7, batch = BORDER_BATCH)
    public static void remoteRespectsTheWorldBorder(GameTestHelper helper) {
        placeTerminal(helper, TERMINAL);
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ServerLevel level = helper.getLevel();
            BlockPos terminalPos = helper.absolutePos(TERMINAL);
            WarehouseTerminalBlockEntity terminal = terminalAt(helper, TERMINAL);
            ClickingPlayer player = ClickingPlayer.at(helper, STAND);
            ItemStack remote = WareworksItems.WAREHOUSE_REMOTE.asStack();
            player.setItemInHand(InteractionHand.MAIN_HAND, remote);
            click(player, level, terminalPos, true);
            assertTold(helper, player, "the link", WareworksLang.REMOTE_LINKED);
            moveEyes(player, terminalPos, afar());
            use(player, false);
            WarehouseTerminalMenu menu = remoteMenu(helper, player, terminal, "the use within the border");
            assertTold(helper, player, "the use within the border");

            WorldBorder border = level.getWorldBorder();
            double centerX = border.getCenterX();
            double centerZ = border.getCenterZ();
            double size = border.getSize();
            CLEANUPS.push(() -> {
                border.setCenter(centerX, centerZ);
                border.setSize(size);
            });
            border.setCenter(terminalPos.getX() + BORDER_AWAY, terminalPos.getZ());
            border.setSize(BORDER_SIZE);
            helper.assertFalse(level.mayInteract(player, terminalPos), "the terminal lies beyond the border");
            helper.assertFalse(menu.stillValid(player), "the screen open from afar closes");
            player.closeContainer();
            use(player, false);
            assertNoScreen(helper, player, "beyond the border");
            assertTold(helper, player, "beyond the border", WareworksLang.REMOTE_NOT_ALLOWED);
            ItemStack fresh = WareworksItems.WAREHOUSE_REMOTE.asStack();
            player.setItemInHand(InteractionHand.MAIN_HAND, fresh);
            moveEyes(player, terminalPos, 2.0);
            click(player, level, terminalPos, true);
            assertLink(helper, fresh, null, "a link click beyond the border links nothing");
            assertTold(helper, player, "the link click beyond the border", WareworksLang.REMOTE_NOT_ALLOWED);

            cleanUp();
            player.setItemInHand(InteractionHand.MAIN_HAND, remote);
            moveEyes(player, terminalPos, afar());
            helper.assertTrue(use(player, false).consumesAction(), "the use with the border back");
            remoteMenu(helper, player, terminal, "the use with the border back");
            assertTold(helper, player, "the use with the border back");
            player.closeContainer();
        }).thenSucceed();
    }

    /** Restores the config and the Nether even when a test of {@link #CONFIG_BATCH} fails before its own cleanup. */
    @AfterBatch(batch = CONFIG_BATCH)
    public static void restoreConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
        cleanUp();
    }

    /** Puts the far terminal's block back and releases its chunk even when the unload test fails. */
    @AfterBatch(batch = UNLOAD_BATCH)
    public static void restoreFarChunk(ServerLevel level) {
        cleanUp();
    }

    /** Puts the world border back even when the border test fails. */
    @AfterBatch(batch = BORDER_BATCH)
    public static void restoreBorder(ServerLevel level) {
        cleanUp();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private static void cleanUp() {
        while (!CLEANUPS.isEmpty())
            CLEANUPS.pop().run();
    }

    /**
     * One server tick of {@code player}'s open menu as {@code ServerPlayer#tick} runs it: the push, then
     * {@code stillValid}, closing the screen when that fails. A {@link ClickingPlayer} is never ticked by the server.
     */
    private static void tickScreen(Player player) {
        player.containerMenu.broadcastChanges();
        if (!player.containerMenu.stillValid(player))
            player.closeContainer();
    }

    private static ServerLevel nether(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        if (nether == null)
            throw new GameTestAssertException("the GameTest server has a Nether");
        return nether;
    }

    private static BlockState terminalState() {
        return WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState().setValue(HorizontalDirectionalBlock.FACING,
                Direction.NORTH);
    }

    private static void placeTerminal(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, terminalState());
    }

    private static WarehouseTerminalBlockEntity terminalAt(GameTestHelper helper, BlockPos pos) {
        if (!(helper.getBlockEntity(pos) instanceof WarehouseTerminalBlockEntity terminal))
            throw new GameTestAssertException("no terminal at " + pos);
        return terminal;
    }

    /** A hit on the top face, off its centre, where a player looking down at the block clicks. */
    private static BlockHitResult topHit(BlockPos absolute) {
        return new BlockHitResult(new Vec3(absolute.getX() + 0.3, absolute.getY() + 1.0, absolute.getZ() + 0.3),
                Direction.UP, absolute, false);
    }

    /**
     * One right-click with the main hand on the top face of the absolute {@code pos} of {@code level}, sneaking or not,
     * through {@code ServerPlayerGameMode#useItemOn} (the interaction event, {@code onItemUseFirst}, the sneak guard,
     * the block and {@code stack.useOn}, in vanilla's order).
     */
    private static InteractionResult click(ClickingPlayer player, ServerLevel level, BlockPos pos, boolean sneaking) {
        player.setShiftKeyDown(sneaking);
        InteractionResult result = player.gameMode.useItemOn(player, level, player.getMainHandItem(),
                InteractionHand.MAIN_HAND, topHit(pos));
        player.setShiftKeyDown(false);
        return result;
    }

    /**
     * One right-click into the air with the main hand, sneaking or not, through {@code ServerPlayerGameMode#useItem}.
     * The remote's cooldown is cleared first: each use here stands for one a player makes at least a moment later, and
     * this player is never ticked.
     */
    private static InteractionResult use(ClickingPlayer player, boolean sneaking) {
        ItemStack held = player.getMainHandItem();
        if (!held.isEmpty())
            player.getCooldowns().removeCooldown(held.getItem());
        player.setShiftKeyDown(sneaking);
        InteractionResult result = player.gameMode.useItem(player, player.serverLevel(), held,
                InteractionHand.MAIN_HAND);
        player.setShiftKeyDown(false);
        return result;
    }

    /** "From afar": {@link #AFAR} blocks, or half the configured range where that is shorter, so always within it. */
    private static double afar() {
        return Math.min(AFAR, WareworksConfig.remoteRange() / 2.0);
    }

    /** Puts {@code player}'s eyes {@code blocks} east of the centre of the absolute {@code pos}, at its height. */
    private static void moveEyes(Player player, BlockPos pos, double blocks) {
        Vec3 center = Vec3.atCenterOf(pos);
        player.moveTo(center.x + blocks, center.y - player.getEyeHeight(), center.z);
    }

    /**
     * {@code remote} is linked to exactly {@code expected}, or not linked for {@code null}. Compared here rather than
     * by {@code assertValueEqual}, which cannot take a {@code null} it is handed as the actual value.
     */
    private static void assertLink(GameTestHelper helper, ItemStack remote, @Nullable RemoteLink expected,
            String what) {
        RemoteLink actual = WarehouseRemoteItem.link(remote);
        helper.assertTrue(Objects.equals(actual, expected), what + ": expected " + expected + ", the remote has "
                + actual);
    }

    /** The link of {@code remote}, which the test made before; fails the test, not the server, without one. */
    private static RemoteLink linkOf(ItemStack remote) {
        RemoteLink link = WarehouseRemoteItem.link(remote);
        if (link == null)
            throw new GameTestAssertException("the remote is not linked");
        return link;
    }

    /** The screen open for {@code player}: a remote menu of exactly {@code terminal}, or the test fails. */
    private static WarehouseTerminalMenu remoteMenu(GameTestHelper helper, Player player,
            WarehouseTerminalBlockEntity terminal, String what) {
        if (!(player.containerMenu instanceof WarehouseTerminalMenu menu) || !menu.isRemote()
                || menu.remote().isEmpty() || menu.terminal() != terminal)
            throw new GameTestAssertException(what + " opens the terminal's screen from afar: " + player.containerMenu);
        return menu;
    }

    private static void assertNoScreen(GameTestHelper helper, Player player, String what) {
        if (player.containerMenu != player.inventoryMenu) {
            String open = String.valueOf(player.containerMenu);
            player.closeContainer();
            throw new GameTestAssertException(what + " opens no screen: " + open);
        }
    }

    /** The player was told exactly these lines (relative lang keys) since the last check, which this one starts. */
    private static void assertTold(GameTestHelper helper, ClickingPlayer player, String what, String... relativeKeys) {
        List<String> expected = new ArrayList<>(relativeKeys.length);
        for (String key : relativeKeys)
            expected.add(WareworksLang.key(key));
        helper.assertValueEqual(player.toldKeys(), List.copyOf(expected), "what " + what + " told the player");
        for (ClientboundSystemChatPacket line : player.told)
            helper.assertTrue(line.overlay(), what + ": told on the action bar, not in chat");
        player.told.clear();
    }

    /** The arguments of the last line {@code player} was told, or an empty list. */
    private static List<Object> toldArgs(ClickingPlayer player) {
        if (player.told.isEmpty()
                || !(player.told.getLast().content().getContents() instanceof TranslatableContents translatable))
            return List.of();
        return List.of(translatable.getArgs());
    }

    /** The translation keys of the remote's own tooltip lines. */
    private static List<String> tooltipKeys(GameTestHelper helper, ItemStack remote) {
        List<Component> lines = new ArrayList<>();
        remote.getItem().appendHoverText(remote, Item.TooltipContext.of(helper.getLevel()), lines, TooltipFlag.NORMAL);
        List<String> keys = new ArrayList<>(lines.size());
        for (Component line : lines)
            keys.add(line.getContents() instanceof TranslatableContents translatable ? translatable.getKey()
                    : "<literal> " + line.getString());
        return keys;
    }

    /** The menu slot of {@code player}'s inventory slot {@code inventorySlot}. */
    private static int menuSlotOf(GameTestHelper helper, WarehouseTerminalMenu menu, Player player,
            int inventorySlot) {
        for (int index = menu.firstPlayerSlot(); index < menu.slots.size(); index++) {
            Slot slot = menu.getSlot(index);
            if (slot.container == player.getInventory() && slot.getContainerSlot() == inventorySlot)
                return index;
        }
        throw new GameTestAssertException("no menu slot for inventory slot " + inventorySlot);
    }

    private static int countIn(Player player, Item item) {
        int total = 0;
        for (ItemStack stack : player.getInventory().items)
            if (stack.is(item))
                total += stack.getCount();
        return total;
    }
}
