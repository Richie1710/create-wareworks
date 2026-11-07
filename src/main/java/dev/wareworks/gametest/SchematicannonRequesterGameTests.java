package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.schematics.cannon.LaunchedItem;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.remote.RemoteLink;
import dev.wareworks.content.requester.CannonView;
import dev.wareworks.content.requester.GraceBehaviour;
import dev.wareworks.content.requester.NoTicket;
import dev.wareworks.content.requester.SchematicannonRequesterBlock;
import dev.wareworks.content.requester.SchematicannonRequesterBlockEntity;
import dev.wareworks.content.station.ProductionPatterns;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.requester.RequesterOrder;
import dev.wareworks.core.requester.RequesterStatus;
import dev.wareworks.gametest.CannonFixture.Site;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksDataComponents;
import dev.wareworks.registry.WareworksItems;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Clearable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * GameTests of the Schematicannon Requester (issue #45, ADR-080): a block set against a Create Schematicannon that
 * orders what the cannon is missing at the terminal its Warehouse Remote is linked to, from stock only, a stack at a
 * time and never twice, and moves no item itself.
 * <p>
 * Every test prints with a <b>real</b> cannon and a real schematic ({@link CannonFixture}) beside a real aisle: the
 * cannon walks the schematic, pauses on "Missing Item" when its chest runs dry, and the requester sees exactly that.
 * The remote goes in with a real click through the server's dispatch ({@link ClickingPlayer}); its link is written
 * through the item component, as a sneaking click at the terminal writes it. The player's funnel and belt from the
 * terminal's buffer to the cannon's chest are the {@link Belt}: the test moves the items through the buffer's own
 * extract-only handler into a barrel and on into the chest, so every item on its way stays countable.
 * <p>
 * <b>The books are kept in every test</b> ({@link Scene#assertKept}): the item census of the whole test, plus the
 * remotes in the requesters' cradles (which no capability shows), plus every block the cannon printed or has in the
 * air counted back as the items Create's own requirement took for it, must equal what the test started with.
 * <ul>
 * <li>{@code requesterordersthemissingitemintothebuffer} — the missing stone is ordered at the terminal for exactly what
 * the schematic needs, the crane delivers it into the buffer, and nothing more is ordered while it lies there;</li>
 * <li>{@code requesterloopbuildstheschematic} — stone and cobblestone ordered one after the other, carried over the belt
 * and printed: the cannon finishes, the stock fell by exactly the schematic, and nothing is left anywhere;</li>
 * <li>{@code onestackatmostthentheremainder} — 80 stone are ordered as 64, then 16;</li>
 * <li>{@code orderfollowswhattheliststillneeds} — with 5 of 12 already in the chest, 7 are ordered;</li>
 * <li>{@code roominthechestcapstheorder} and {@code fullchestordersnothing} — the room in the cannon's chest caps an
 * order (63 double-slab halves beside one in a full chest), and a full chest orders nothing until a slot is free;</li>
 * <li>{@code fullbufferordersnothing} — nor does a full terminal buffer;</li>
 * <li>{@code nosecondorderwhilerunningorinbuffer} and {@code bufferheldbyanotherorderblocks} — the duplicate lock
 * (an open order of the item) and the buffer lock (a matching stack in the buffer, also a player's);</li>
 * <li>{@code gracefollowstheblockvalue} — the grace after the buffer: 10 s outlast a belt of 6 s, 2 s do not and the
 * item is ordered again while it is still on the belt;</li>
 * <li>{@code mergedplayerclickiscreditedfirst} — merged into a player's earlier click, the requester's share counts as
 * delivered only after the player's items came, and nothing is ordered again before;</li>
 * <li>{@code deadmangivesupandordersagain} ({@link #CONFIG_BATCH}) — the dead-man switch at its floor gives back only
 * its own share, a player's click merged ahead of it keeps its amount, and the order is placed again;</li>
 * <li>{@code deadmanwaitswhiletheterminalisunloaded} ({@link #FAR_BATCH}) — a warehouse in a chunk that unloads ages no
 * order; the switch fires only after the live time, once the chunk is back;</li>
 * <li>{@code stalecounterrefreshesthechecklistonce} — a player empties the chest mid-run: the requester has Create
 * remake the material list once and orders what is left;</li>
 * <li>{@code neverstartsproduction} — producible only is out of stock, partly in stock is the stock, and no production
 * order is ever made, neither through the requester nor through {@code requestStockOnly} itself;</li>
 * <li>{@code reserveholdsitback} — a Stock Keeper's reserve holds the stock back, and a lowered reserve lets part
 * through;</li>
 * <li>{@code strictrequirementordersexactcomponents} and {@code plainrequirementprefersnocomponents} — a patterned
 * banner is ordered with exactly its patterns and never as a plain one, and plain stone before named stone;</li>
 * <li>{@code skipmissingcannonshowsthehint} and {@code creativecrateneedsnothing} — the cannon's own settings;</li>
 * <li>{@code insertrefusals} ({@link #BORDER_BATCH}) — unlinked, a Deployer, out of range, another dimension, a
 * terminal that is gone, beyond the world border, and a slot already taken;</li>
 * <li>{@code requesterletstheterminalschunkunload} ({@link #UNLOAD_BATCH}) — polling a terminal keeps no chunk
 * loaded and loads none;</li>
 * <li>{@code noticketreadsholdnochunk} ({@link #NO_TICKET_BATCH}) — a read through {@link NoTicket} adds no chunk
 * ticket, measured against a {@code Level#getBlockEntity} read that does;</li>
 * <li>{@code secondrequesterononecannonstaysidle}, {@code lowerrequesterwaitsfortheothersorder} and
 * {@code tworequestersononeterminalcreditapart} — one requester per cannon, also while the other one's order runs, but
 * several per terminal, each credited with its own share;</li>
 * <li>{@code removalandbreakinggivebackshares}, {@code replacingtheblockgivesbackshares} and
 * {@code givesbackwhentheprintstops} — the open share is given back, also when a command replaces the block, and a
 * player's click merged with it keeps its amount;</li>
 * <li>{@code requestersurvivesasaveroundtrip} — remote, owner, orders and counter come back from a save, and the order
 * resumes by its request id.</li>
 * </ul>
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class SchematicannonRequesterGameTests {
    /** The test that lowers the dead-man switch, restored after the batch even when it fails. */
    static final String CONFIG_BATCH = "wareworksrequesterconfig";
    /** The test that releases a chunk with a terminal in it and raises the remote's range to reach it. */
    static final String UNLOAD_BATCH = "wareworksrequesterunload";
    /** The test that force-loads and releases two far chunks and times when they drop out: alone in its batch. */
    static final String NO_TICKET_BATCH = "wareworksrequesternoticket";
    /** The test that releases the chunks of a whole warehouse, raises the range and lowers the dead-man switch. */
    static final String FAR_BATCH = "wareworksrequesterfar";
    /** The test that moves the world border, which is the whole level's: alone in its batch, restored after it. */
    static final String BORDER_BATCH = "wareworksrequesterborder";

    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final RackPosition TERMINAL_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition OTHER_TERMINAL_RACK = new RackPosition(1, 0, Side.RIGHT);
    private static final RackPosition STORAGE_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition PRODUCTION_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition KEEPER_RACK = new RackPosition(3, 0, Side.RIGHT);

    /** The cannon beyond the rail end, its chest behind it and the requester in front of it, facing it. */
    private static final BlockPos CANNON = new BlockPos(13, BASE_Y, AISLE_Z);
    private static final BlockPos CHEST = new BlockPos(14, BASE_Y, AISLE_Z);
    private static final BlockPos REQUESTER = new BlockPos(12, BASE_Y, AISLE_Z);
    /** A second requester on top of the cannon, facing down at it. */
    private static final BlockPos ON_THE_CANNON = CANNON.above();
    private static final BlockPos CRATE = CANNON.south();
    /** The barrel that stands for the belt between the terminal's buffer and the cannon's chest. */
    private static final BlockPos BELT = new BlockPos(11, BASE_Y, 6);
    /** Where the player stands to click the requester. */
    private static final BlockPos STAND = new BlockPos(12, BASE_Y, AISLE_Z - 2);
    /**
     * The schematic: 4 x 4 x 5 spots between the rail end and the cannon, at least two blocks from it as Create
     * requires. {@link #spot} numbers them in the order the cannon prints them.
     */
    private static final Site SITE = new Site(new BlockPos(7, BASE_Y, 1), new Vec3i(4, 4, 5), CANNON, CHEST);
    private static final int SITE_LENGTH = 4;
    private static final int SITE_WIDTH = 5;

    /** Two cannons on one terminal: the first prints 64 spots, the second, beside the aisle's edge, four. */
    private static final Site SITE_A = new Site(new BlockPos(7, BASE_Y, 2), new Vec3i(4, 4, 4), CANNON, CHEST);
    private static final BlockPos CANNON_B = new BlockPos(13, BASE_Y, 0);
    private static final BlockPos CHEST_B = new BlockPos(14, BASE_Y, 0);
    private static final BlockPos REQUESTER_B = new BlockPos(12, BASE_Y, 0);
    private static final Site SITE_B = new Site(new BlockPos(7, BASE_Y, 0), new Vec3i(4, 1, 1), CANNON_B, CHEST_B);

    /** The refusal test: a lone terminal and a requester without a cannon, on the small template. */
    private static final BlockPos LONE_TERMINAL = new BlockPos(1, BASE_Y, 3);
    private static final BlockPos LONE_REQUESTER = new BlockPos(5, BASE_Y, 3);
    private static final BlockPos LONE_STAND = new BlockPos(3, BASE_Y, 1);

    /** Far from every test along x and z, in blocks: the unload test's terminal and the far test's warehouse. */
    private static final int UNLOAD_OFFSET = 42_000;
    private static final int FAR_OFFSET = 46_000;
    /** How far the no-ticket test's control chunk lies from its other chunk: well outside each other's forced area. */
    private static final int CONTROL_CHUNKS_AWAY = 8;
    /** Ticks after a placement until every short-lived ticket it added has run out (they live two ticks). */
    private static final int TICKET_SETTLE_TICKS = 10;
    private static final int FAR_RAILS = 2;
    /** A remote range that reaches the far chunks ({@code RemoteLimits#MAX_RANGE} allows it). */
    private static final int FAR_RANGE = 100_000;
    /** How far beyond the default range the out-of-range link points, in blocks. */
    private static final int BEYOND_RANGE = 300;
    private static final int BORDER_AWAY = 1_000;
    private static final double BORDER_SIZE = 16.0;
    private static final int NETHER_Y = 64;

    private static final int TIMEOUT_TICKS = 2400;
    private static final int LONG_TIMEOUT_TICKS = 4000;
    /** The dead-man switch at its floor ({@code RequesterLimits#MIN_TIMEOUT_TICKS}). */
    private static final int DEAD_MAN_TICKS = 600;
    /** How long the far test keeps its warehouse unloaded: longer than the whole dead-man switch. */
    private static final int UNLOADED_TICKS = 700;
    /** How long the belt in the grace test takes from the buffer to the chest: 6 s. */
    private static final int SLOW_BELT_TICKS = 120;
    private static final int LONG_GRACE_SECONDS = 10;
    private static final int SHORT_GRACE_SECONDS = 2;
    private static final int SAVED_GRACE_SECONDS = 7;
    private static final int PLAYER_SHARE = 10;
    /** A player's click that takes the crane three trips, so the requester's share behind it arrives last. */
    private static final int BIG_PLAYER_SHARE = 130;
    private static final int CHEST_SLOTS = 27;

    private static final ItemKey STONE = ItemKey.of(Items.STONE);
    private static final ItemKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final ItemKey DIRT = ItemKey.of(Items.DIRT);
    private static final ItemKey SLAB = ItemKey.of(Items.STONE_SLAB);
    private static final ItemKey PLANK = ItemKey.of(Items.OAK_PLANKS);
    private static final ItemKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final ItemKey PLAIN_BANNER = ItemKey.of(Items.WHITE_BANNER);

    /**
     * What the tests that reach beyond their own bounds changed (forced chunks, far blocks, the world border), undone by
     * the {@code @AfterBatch} methods also when a test failed before its own cleanup; touched only from the server
     * thread.
     */
    private static final Deque<Runnable> CLEANUPS = new ArrayDeque<>();

    private SchematicannonRequesterGameTests() {
    }

    // --- ordering --------------------------------------------------------------------------------------------------

    /**
     * The first order: the cannon waits for stone, and the requester orders it at the linked terminal like a stock-list
     * click, for exactly what the schematic needs, ahead of nobody. The lamp lights. The crane delivers into the
     * terminal's buffer, never towards the cannon, and while the stone lies there nothing more is ordered.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void requesterOrdersTheMissingItemIntoTheBuffer(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, true, STONE.toStack(64));
        Scene scene = new Scene(helper, aisle);
        scene.print(SITE, repeat(Blocks.STONE.defaultBlockState(), 5));
        scene.place(REQUESTER, Direction.EAST);
        scene.begin(scene.remoteFor(TERMINAL_RACK));

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> scene.insert(REQUESTER, 0))
                .thenWaitUntil(() -> helper.assertValueEqual(scene.requests(TERMINAL_RACK).size(), 1,
                        "an order at the terminal"))
                .thenExecute(() -> {
                    RetrievalRequest<ItemKey, BlockPos> request = scene.requests(TERMINAL_RACK).getFirst();
                    helper.assertValueEqual(request.key(), STONE, "the missing item is ordered");
                    helper.assertValueEqual(request.requested(), 5, "exactly what the schematic needs");
                    RequesterOrder<ItemKey> order = onlyOrder(helper, scene.requester(REQUESTER));
                    helper.assertValueEqual(order.requestId(), request.id(), "the order follows that request");
                    helper.assertValueEqual(order.share(), 5, "its share");
                    helper.assertValueEqual(order.ahead(), 0, "ahead of nobody");
                    helper.assertValueEqual(order.phase(), RequesterOrder.Phase.RUNNING, "running");
                    helper.assertValueEqual(scene.requester(REQUESTER).orderedSinceList(Items.STONE), 5,
                            "counted against the material list");
                    assertShows(helper, REQUESTER, RequesterStatus.ORDERED);
                    scene.assertKept("once the order is placed");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, STONE), 5L, "delivered into the buffer");
                    assertShows(helper, REQUESTER, RequesterStatus.IN_BUFFER);
                })
                .thenIdle(3 * poll())
                .thenExecute(() -> {
                    helper.assertTrue(scene.requests(TERMINAL_RACK).isEmpty(), "nothing more is ordered");
                    helper.assertValueEqual(onlyOrder(helper, scene.requester(REQUESTER)).phase(),
                            RequesterOrder.Phase.IN_BUFFER, "the order waits while its stone lies in the buffer");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, STONE), 5L, "still in the buffer");
                    helper.assertValueEqual(aisle.inventoryCount(CHEST, STONE), 0L,
                            "the requester moved nothing into the chest");
                    assertShows(helper, REQUESTER, RequesterStatus.IN_BUFFER);
                    scene.assertKept("with the stone in the buffer");
                })
                .thenSucceed();
    }

    /**
     * The whole loop with the belt: seven stone and three cobblestone are ordered one after the other, carried from the
     * buffer to the chest and printed. The cannon finishes, the stock fell by exactly the schematic, the chest and the
     * buffer are empty, two orders were placed in all, and the requester gives up nothing it does not have.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void requesterLoopBuildsTheSchematic(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, true, STONE.toStack(64), COBBLE.toStack(64));
        Scene scene = new Scene(helper, aisle);
        List<BlockState> blocks = new ArrayList<>(repeat(Blocks.STONE.defaultBlockState(), 7));
        blocks.addAll(repeat(Blocks.COBBLESTONE.defaultBlockState(), 3));
        SchematicannonBlockEntity cannon = scene.print(SITE, blocks);
        scene.place(REQUESTER, Direction.EAST);
        Belt.start(helper, aisle, TERMINAL_RACK, CHEST, 0);
        scene.begin(scene.remoteFor(TERMINAL_RACK));
        OrderLog log = OrderLog.watch(helper, REQUESTER);

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> scene.insert(REQUESTER, 0))
                .thenWaitUntil(() -> assertFinished(helper, cannon))
                .thenWaitUntil(() -> {
                    helper.assertTrue(scene.requester(REQUESTER).orders().isEmpty(), "every order is through");
                    assertShows(helper, REQUESTER, RequesterStatus.NOT_PRINTING);
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(log.shares(Items.STONE), List.of(7), "one order of stone");
                    helper.assertValueEqual(log.shares(Items.COBBLESTONE), List.of(3), "one order of cobblestone");
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.countOf(STONE), 57L, "the stock fell by the stone printed");
                    helper.assertValueEqual(controller.countOf(COBBLE), 61L, "and by the cobblestone");
                    helper.assertValueEqual(aisle.inventoryCount(CHEST, STONE) + aisle.inventoryCount(CHEST, COBBLE),
                            0L, "the chest is empty");
                    helper.assertFalse(aisle.terminalAt(TERMINAL_RACK).hasBufferedItems(), "so is the buffer");
                    scene.assertKept("after the print");
                })
                .thenSucceed();
    }

    /** Eighty stone: one stack at most, then the remainder of the schematic, and nothing more. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void oneStackAtMostThenTheRemainder(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, true, STONE.toStack(64), STONE.toStack(36));
        Scene scene = new Scene(helper, aisle);
        SchematicannonBlockEntity cannon = scene.print(SITE, repeat(Blocks.STONE.defaultBlockState(), 80));
        scene.place(REQUESTER, Direction.EAST);
        Belt.start(helper, aisle, TERMINAL_RACK, CHEST, 0);
        scene.begin(scene.remoteFor(TERMINAL_RACK));
        OrderLog log = OrderLog.watch(helper, REQUESTER);

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> scene.insert(REQUESTER, 0))
                .thenWaitUntil(() -> helper.assertValueEqual(log.shares(Items.STONE), List.of(64),
                        "the first order is one stack"))
                .thenExecute(() -> scene.assertKept("after the first order"))
                .thenWaitUntil(() -> assertFinished(helper, cannon))
                .thenExecute(() -> {
                    helper.assertValueEqual(log.shares(Items.STONE), List.of(64, 16), "then the remainder");
                    helper.assertValueEqual(aisle.controller().countOf(STONE), 20L, "eighty taken from stock");
                    helper.assertValueEqual(aisle.inventoryCount(CHEST, STONE), 0L, "and all of them printed");
                    scene.assertKept("after the print");
                })
                .thenSucceed();
    }

    /**
     * Five of twelve stone lie in the chest when the cannon makes its material list: it prints them, and the requester
     * orders the seven the list still needs.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void orderFollowsWhatTheListStillNeeds(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, true, STONE.toStack(64));
        Scene scene = new Scene(helper, aisle);
        SchematicannonBlockEntity cannon = scene.print(SITE, repeat(Blocks.STONE.defaultBlockState(), 12));
        aisle.insertAll(aisle.handlerAt(CHEST), STONE.toStack(5));
        scene.place(REQUESTER, Direction.EAST);
        Belt.start(helper, aisle, TERMINAL_RACK, CHEST, 0);
        scene.begin(scene.remoteFor(TERMINAL_RACK));
        OrderLog log = OrderLog.watch(helper, REQUESTER);

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> scene.insert(REQUESTER, 0))
                .thenWaitUntil(() -> assertFinished(helper, cannon))
                .thenExecute(() -> {
                    helper.assertValueEqual(log.shares(Items.STONE), List.of(7), "only what the list still needs");
                    helper.assertValueEqual(aisle.controller().countOf(STONE), 57L, "seven taken from stock");
                    helper.assertValueEqual(aisle.inventoryCount(CHEST, STONE), 0L, "and nothing left over");
                    scene.assertKept("after the print");
                })
                .thenSucceed();
    }

    /**
     * The room in the cannon's inventories caps an order below a stack: double slabs, with the chest full of dirt but
     * for one slot holding one slab, which a double slab cannot use. Sixty-three more fit, and sixty-three are ordered.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void roomInTheChestCapsTheOrder(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, true, SLAB.toStack(64), SLAB.toStack(36));
        Scene scene = new Scene(helper, aisle);
        BlockState doubleSlab = Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE);
        scene.print(SITE, repeat(doubleSlab, 40));
        IItemHandler chest = aisle.handlerAt(CHEST);
        for (int slot = 0; slot < CHEST_SLOTS - 1; slot++)
            aisle.insertAll(chest, DIRT.toStack(64));
        aisle.insertAll(chest, SLAB.toStack(1));
        scene.place(REQUESTER, Direction.EAST);
        scene.begin(scene.remoteFor(TERMINAL_RACK));

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> scene.insert(REQUESTER, 0))
                .thenWaitUntil(() -> helper.assertValueEqual(scene.requests(TERMINAL_RACK).size(), 1, "an order"))
                .thenExecute(() -> {
                    helper.assertValueEqual(scene.requests(TERMINAL_RACK).getFirst().requested(), 63,
                            "capped by the room in the chest");
                    helper.assertValueEqual(onlyOrder(helper, scene.requester(REQUESTER)).share(), 63, "its share");
                    scene.assertKept("after the order");
                })
                .thenSucceed();
    }

    /**
     * A chest without room orders nothing (owner rule 6): the lamp is red and the line says why. Once the player takes
     * a stack of dirt out, the stone is ordered.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fullChestOrdersNothing(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, true, STONE.toStack(64));
        Scene scene = new Scene(helper, aisle);
        scene.print(SITE, repeat(Blocks.STONE.defaultBlockState(), 5));
        IItemHandler chest = aisle.handlerAt(CHEST);
        for (int slot = 0; slot < CHEST_SLOTS; slot++)
            aisle.insertAll(chest, DIRT.toStack(64));
        scene.place(REQUESTER, Direction.EAST);
        scene.begin(scene.remoteFor(TERMINAL_RACK));

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> scene.insert(REQUESTER, 0))
                .thenWaitUntil(() -> assertShows(helper, REQUESTER, RequesterStatus.CANNON_FULL))
                .thenIdle(3 * poll())
                .thenExecute(() -> {
                    assertShows(helper, REQUESTER, RequesterStatus.CANNON_FULL);
                    helper.assertTrue(scene.requests(TERMINAL_RACK).isEmpty(), "nothing is ordered into a full chest");
                    helper.assertTrue(scene.requester(REQUESTER).orders().isEmpty(), "and no order is open");
                    scene.assertKept("with the chest full");
                    scene.take(chest, DIRT, 64);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(scene.requests(TERMINAL_RACK).size(), 1,
                        "once a slot is free, the stone is ordered"))
                .thenExecute(() -> {
                    helper.assertValueEqual(scene.requests(TERMINAL_RACK).getFirst().requested(), 5, "all five");
                    scene.assertKept("after the order");
                })
                .thenSucceed();
    }

    /**
     * A buffer without room orders nothing either: the planner would skip such a request, and it would only hang until
     * the dead-man switch. Once a slot is free, the stone is ordered.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void fullBufferOrdersNothing(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, true, STONE.toStack(64));
        WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
        for (int slot = 0; slot < terminal.bufferSlots(); slot++)
            helper.assertTrue(terminal.insert(DIRT.toStack(64), false).isEmpty(), "the buffer takes the dirt");
        Scene scene = new Scene(helper, aisle);
        scene.print(SITE, repeat(Blocks.STONE.defaultBlockState(), 5));
        scene.place(REQUESTER, Direction.EAST);
        scene.begin(scene.remoteFor(TERMINAL_RACK));

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> scene.insert(REQUESTER, 0))
                .thenWaitUntil(() -> assertShows(helper, REQUESTER, RequesterStatus.BUFFER_FULL))
                .thenIdle(3 * poll())
                .thenExecute(() -> {
                    assertShows(helper, REQUESTER, RequesterStatus.BUFFER_FULL);
                    helper.assertTrue(scene.requests(TERMINAL_RACK).isEmpty(), "nothing is ordered into a full buffer");
                    scene.assertKept("with the buffer full");
                    scene.take(aisle.terminalAt(TERMINAL_RACK).externalHandler(), DIRT, 64);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(scene.requests(TERMINAL_RACK).size(), 1,
                        "once a slot is free, the stone is ordered"))
                .thenExecute(() -> scene.assertKept("after the order"))
                .thenSucceed();
    }

    // --- no duplicates ---------------------------------------------------------------------------------------------

    /**
     * The duplicate lock: while the order runs (the crane has no rotation yet) and while its stone lies in the buffer,
     * the cannon still misses stone at every poll and nothing more is ordered — no second order and no merge into the
     * open request.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void noSecondOrderWhileRunningOrInBuffer(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, false, STONE.toStack(64));
        Scene scene = new Scene(helper, aisle);
        scene.print(SITE, repeat(Blocks.STONE.defaultBlockState(), 5));
        scene.place(REQUESTER, Direction.EAST);
        scene.begin(scene.remoteFor(TERMINAL_RACK));

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> scene.insert(REQUESTER, 0))
                .thenWaitUntil(() -> assertShows(helper, REQUESTER, RequesterStatus.ORDERED))
                .thenIdle(5 * poll())
                .thenExecute(() -> {
                    List<RetrievalRequest<ItemKey, BlockPos>> open = scene.requests(TERMINAL_RACK);
                    helper.assertValueEqual(open.size(), 1, "one request");
                    helper.assertValueEqual(open.getFirst().requested(), 5, "never merged into while it runs");
                    RequesterOrder<ItemKey> order = onlyOrder(helper, scene.requester(REQUESTER));
                    helper.assertValueEqual(order.phase(), RequesterOrder.Phase.RUNNING, "still running");
                    helper.assertValueEqual(scene.requester(REQUESTER).cannonPhase(), CannonView.Phase.MISSING,
                            "while the cannon still misses stone");
                    assertShows(helper, REQUESTER, RequesterStatus.ORDERED);
                    scene.assertKept("while the order runs");
                    aisle.placeMotor();
                })
                .thenWaitUntil(() -> helper.assertValueEqual(onlyOrder(helper, scene.requester(REQUESTER)).phase(),
                        RequesterOrder.Phase.IN_BUFFER, "delivered into the buffer"))
                .thenIdle(5 * poll())
                .thenExecute(() -> {
                    helper.assertTrue(scene.requests(TERMINAL_RACK).isEmpty(), "nothing ordered again");
                    helper.assertValueEqual(onlyOrder(helper, scene.requester(REQUESTER)).phase(),
                            RequesterOrder.Phase.IN_BUFFER, "the order waits for its stone to leave the buffer");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, STONE), 5L, "which lies there");
                    assertShows(helper, REQUESTER, RequesterStatus.IN_BUFFER);
                    scene.assertKept("with the stone in the buffer");
                })
                .thenSucceed();
    }

    /**
     * The grace after the buffer follows the block's value box. Stone with 10 s: the stone leaves the buffer and spends
     * 6 s on the belt, and nothing is ordered again meanwhile. Cobblestone with 2 s: the grace runs out while the
     * cobblestone is still on the belt, and it is ordered once more — not before the 2 s are over.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void graceFollowsTheBlockValue(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, true, STONE.toStack(64), COBBLE.toStack(64));
        Scene scene = new Scene(helper, aisle);
        List<BlockState> blocks = new ArrayList<>(repeat(Blocks.STONE.defaultBlockState(), 3));
        blocks.addAll(repeat(Blocks.COBBLESTONE.defaultBlockState(), 3));
        SchematicannonBlockEntity cannon = scene.print(SITE, blocks);
        SchematicannonRequesterBlockEntity requester = scene.place(REQUESTER, Direction.EAST);
        requester.setGraceSeconds(LONG_GRACE_SECONDS);
        Belt belt = Belt.start(helper, aisle, TERMINAL_RACK, CHEST, SLOW_BELT_TICKS);
        scene.begin(scene.remoteFor(TERMINAL_RACK));
        OrderLog log = OrderLog.watch(helper, REQUESTER);

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> scene.insert(REQUESTER, 0))
                .thenWaitUntil(() -> helper.assertTrue(belt.takenAt(Items.STONE) >= 0, "the stone is on the belt"))
                .thenWaitUntil(() -> assertShows(helper, REQUESTER, RequesterStatus.GRACE))
                .thenExecute(() -> scene.requester(REQUESTER).setGraceSeconds(SHORT_GRACE_SECONDS))
                .thenWaitUntil(() -> helper.assertTrue(belt.takenAt(Items.COBBLESTONE) >= 0,
                        "the cobblestone is on the belt"))
                .thenWaitUntil(() -> helper.assertValueEqual(log.shares(Items.COBBLESTONE).size(), 2,
                        "with the short grace, the cobblestone is ordered again"))
                .thenExecute(() -> {
                    helper.assertValueEqual(log.shares(Items.STONE), List.of(3),
                            "the long grace outlasted the belt: the stone was ordered once");
                    long reordered = log.placedAt(Items.COBBLESTONE, 1);
                    long taken = belt.takenAt(Items.COBBLESTONE);
                    helper.assertTrue(reordered - taken >= SHORT_GRACE_SECONDS * 20L,
                            "not before the grace was over: taken at " + taken + ", ordered again at " + reordered);
                    helper.assertTrue(reordered - taken < SLOW_BELT_TICKS,
                            "but while the first cobblestone was still on the belt: taken at " + taken
                                    + ", ordered again at " + reordered);
                    scene.assertKept("after the second order");
                })
                .thenWaitUntil(() -> assertFinished(helper, cannon))
                .thenExecute(() -> scene.assertKept("after the print"))
                .thenSucceed();
    }

    /**
     * First in, first served: a player clicked 130 stone and the requester's five merged behind them into the same
     * request. After the crane's first and second trips (128 delivered) the requester's share still runs, and nothing
     * is ordered again even though the player took the stone away from the buffer; the share counts as delivered only
     * with the third trip ({@link #watchFirstInFirstServed}, every tick).
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void mergedPlayerClickIsCreditedFirst(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, false, STONE.toStack(64), STONE.toStack(64), STONE.toStack(64));
        Scene scene = new Scene(helper, aisle);
        scene.print(SITE, repeat(Blocks.STONE.defaultBlockState(), 5));
        scene.place(REQUESTER, Direction.EAST).setGraceSeconds(0);
        // The player takes what arrives: into the barrel, never on to the cannon.
        Belt.start(helper, aisle, TERMINAL_RACK, null, 0);
        scene.begin(scene.remoteFor(TERMINAL_RACK));
        OrderLog log = OrderLog.watch(helper, REQUESTER);
        UUID[] merged = new UUID[1];
        watchFirstInFirstServed(helper, aisle, TERMINAL_RACK, REQUESTER);

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> {
                    RequestResult click = aisle.terminalAt(TERMINAL_RACK).requestFromTerminal(
                            playerAtTerminal(helper, aisle), STONE, BIG_PLAYER_SHARE);
                    helper.assertTrue(click.isAccepted(), "the player's click: " + click);
                    scene.insert(REQUESTER, 0);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(log.shares(Items.STONE), List.of(5),
                        "the requester ordered"))
                .thenExecute(() -> {
                    RetrievalRequest<ItemKey, BlockPos> request = onlyRequest(helper, scene, TERMINAL_RACK);
                    RequesterOrder<ItemKey> order = onlyOrder(helper, scene.requester(REQUESTER));
                    helper.assertValueEqual(order.requestId(), request.id(), "merged into the player's request");
                    helper.assertValueEqual(request.requested(), BIG_PLAYER_SHARE + 5, "the merged amount");
                    helper.assertValueEqual(order.ahead(), BIG_PLAYER_SHARE, "behind the player's click");
                    merged[0] = request.id();
                    scene.assertKept("before the crane moves");
                    aisle.placeMotor();
                })
                .thenWaitUntil(() -> helper.assertTrue(onlyRequest(helper, scene, TERMINAL_RACK).delivered() >= 128,
                        "two trips delivered"))
                .thenExecute(() -> {
                    helper.assertTrue(onlyRequest(helper, scene, TERMINAL_RACK).delivered() < BIG_PLAYER_SHARE + 5,
                            "the third trip is still to come");
                    helper.assertValueEqual(onlyOrder(helper, scene.requester(REQUESTER)).phase(),
                            RequesterOrder.Phase.RUNNING, "so the requester's share still runs");
                    helper.assertValueEqual(log.shares(Items.STONE), List.of(5), "and nothing was ordered again");
                    helper.assertValueEqual(onlyRequest(helper, scene, TERMINAL_RACK).requested(),
                            BIG_PLAYER_SHARE + 5, "nor merged");
                    scene.assertKept("after two trips");
                })
                .thenWaitUntil(() -> helper.assertFalse(aisle.controller().hasOpenRequest(merged[0]),
                        "the third trip completes the request"))
                .thenWaitUntil(() -> helper.assertFalse(scene.requester(REQUESTER).orders().stream()
                        .anyMatch(order -> order.requestId().equals(merged[0])), "and the share is through"))
                .thenExecute(() -> scene.assertKept("after the request"))
                .thenSucceed();
    }

    /**
     * The buffer lock: stone a player ordered lies in the buffer, and it goes to the cannon all the same, so the
     * requester orders none while it lies there, and says so although no order of its own does: an empty-hand click
     * (and the goggles, which read the same line) names the stone in the buffer. Once the player takes it away, the
     * requester orders.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void bufferHeldByAnotherOrderBlocks(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, true, STONE.toStack(64));
        Scene scene = new Scene(helper, aisle);
        scene.print(SITE, repeat(Blocks.STONE.defaultBlockState(), 5));
        scene.place(REQUESTER, Direction.EAST);
        scene.begin(scene.remoteFor(TERMINAL_RACK));

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> helper.assertTrue(aisle.terminalAt(TERMINAL_RACK).requestFromTerminal(
                        playerAtTerminal(helper, aisle), STONE, 3).isAccepted(), "the player's click"))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, STONE), 3L,
                        "the player's stone lies in the buffer"))
                .thenExecute(() -> scene.insert(REQUESTER, 0))
                .thenWaitUntil(() -> assertShows(helper, REQUESTER, RequesterStatus.IN_BUFFER))
                .thenIdle(3 * poll())
                .thenExecute(() -> {
                    assertShows(helper, REQUESTER, RequesterStatus.IN_BUFFER);
                    helper.assertTrue(scene.requests(TERMINAL_RACK).isEmpty(), "nothing is ordered");
                    helper.assertTrue(scene.requester(REQUESTER).orders().isEmpty(), "and no order is open");
                    scene.player.told.clear();
                    helper.assertTrue(scene.player.getMainHandItem().isEmpty(), "an empty hand");
                    click(scene.player, helper.absolutePos(REQUESTER), Direction.UP, false);
                    assertTold(helper, scene.player, "the empty-hand click",
                            WareworksLang.requesterStatusKey(RequesterStatus.IN_BUFFER));
                    scene.assertKept("with the player's stone in the buffer");
                    scene.take(aisle.terminalAt(TERMINAL_RACK).externalHandler(), STONE, 3);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(scene.requests(TERMINAL_RACK).size(), 1,
                        "once the buffer is empty, the requester orders"))
                .thenExecute(() -> scene.assertKept("after the order"))
                .thenSucceed();
    }

    // --- the dead-man switch ---------------------------------------------------------------------------------------

    /**
     * The dead-man switch at its floor (600 live ticks), the crane without rotation so nothing arrives. A player's
     * click of ten lies ahead in the merged request. The switch gives back the requester's own five only, never the
     * request: the player's ten stay, and the five are ordered again into the same request, behind them again.
     */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_BATCH, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void deadManGivesUpAndOrdersAgain(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.requesterOrderTimeoutTicks, DEAD_MAN_TICKS);
        AisleFixture aisle = warehouse(helper, false, STONE.toStack(64));
        Scene scene = new Scene(helper, aisle);
        scene.print(SITE, repeat(Blocks.STONE.defaultBlockState(), 5));
        scene.place(REQUESTER, Direction.EAST);
        scene.begin(scene.remoteFor(TERMINAL_RACK));
        long[] placed = { -1L };

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> {
                    helper.assertTrue(aisle.terminalAt(TERMINAL_RACK).requestFromTerminal(
                            playerAtTerminal(helper, aisle), STONE, PLAYER_SHARE).isAccepted(), "the player's click");
                    scene.insert(REQUESTER, 0);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(scene.requester(REQUESTER).orders().size(), 1,
                        "the requester ordered"))
                .thenExecute(() -> {
                    placed[0] = helper.getTick();
                    RetrievalRequest<ItemKey, BlockPos> request = onlyRequest(helper, scene, TERMINAL_RACK);
                    helper.assertValueEqual(request.requested(), PLAYER_SHARE + 5, "merged behind the click");
                    helper.assertValueEqual(onlyOrder(helper, scene.requester(REQUESTER)).ahead(), PLAYER_SHARE,
                            "behind the player's ten");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(scene.requester(REQUESTER).timeouts(), 1,
                        "the switch fired"))
                .thenExecute(() -> {
                    helper.assertTrue(helper.getTick() - placed[0] >= DEAD_MAN_TICKS - poll(),
                            "not before the switch's time: " + (helper.getTick() - placed[0]) + " ticks");
                    RetrievalRequest<ItemKey, BlockPos> request = onlyRequest(helper, scene, TERMINAL_RACK);
                    helper.assertValueEqual(request.requested(), PLAYER_SHARE + 5,
                            "the player's ten kept, and the five ordered again into the same request");
                    RequesterOrder<ItemKey> again = onlyOrder(helper, scene.requester(REQUESTER));
                    helper.assertValueEqual(again.requestId(), request.id(), "the same request");
                    helper.assertValueEqual(again.ahead(), PLAYER_SHARE, "behind the player's ten again");
                    helper.assertValueEqual(again.share(), 5, "the same share");
                    helper.assertTrue(again.liveAge() < DEAD_MAN_TICKS / 2, "a fresh order: " + again);
                    helper.assertValueEqual(scene.requester(REQUESTER).orderedSinceList(Items.STONE), 5,
                            "the counter lowered by the five given up and raised by the five ordered again");
                    scene.assertKept("after the switch");
                    ConfigOverrides.restoreAll();
                })
                .thenSucceed();
    }

    /**
     * Live age: the warehouse stands in chunks far away that the test releases while the order runs. The chunks
     * unload — the requester polling the terminal every second keeps none of them — and for longer than the whole
     * switch the order ages not at all and nothing is given up. Loaded again, the order resumes by its id and the
     * switch fires once the live time is reached; then the stone is ordered again.
     */
    @GameTest(template = AISLE_16X10X7, batch = FAR_BATCH, timeoutTicks = 6000)
    public static void deadManWaitsWhileTheTerminalIsUnloaded(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.remoteRange, FAR_RANGE);
        ConfigOverrides.set(helper, WareworksConfig.SERVER.requesterOrderTimeoutTicks, DEAD_MAN_TICKS);
        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos(FAR_OFFSET, 0, FAR_OFFSET + AISLE_Z);
        AisleFixture far = new AisleFixture(helper, origin, FAR_RAILS);
        BlockPos min = helper.absolutePos(far.controllerPos().offset(-1, -1, -3));
        BlockPos max = helper.absolutePos(far.controllerPos().offset(FAR_RAILS + 3, 1, 3));
        List<ChunkPos> chunks = chunksBetween(min, max);
        force(level, chunks);
        Scene scene = new Scene(helper, null);
        scene.print(SITE, repeat(Blocks.STONE.defaultBlockState(), 5));
        scene.place(REQUESTER, Direction.EAST);
        long[] frozen = { -1L };
        long[] back = { -1L };
        UUID[] first = new UUID[1];

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(chunks.stream().allMatch(chunk -> level.shouldTickBlocksAt(
                        chunk.getWorldPosition())), "the far chunks are loaded and ticking"))
                .thenExecute(() -> {
                    remember(level, min, max);
                    far.build(false);
                    far.storage(STORAGE_RACK, STONE.toStack(64));
                    far.terminal(TERMINAL_RACK);
                })
                .thenWaitUntil(() -> assertWarehouseReady(helper, far))
                .thenExecute(() -> {
                    scene.begin(scene.remoteFor(far.terminalAt(TERMINAL_RACK)));
                    scene.insert(REQUESTER, 0);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(scene.requester(REQUESTER).orders().size(), 1,
                        "the requester ordered at the far terminal"))
                .thenWaitUntil(() -> helper.assertTrue(onlyOrder(helper, scene.requester(REQUESTER)).liveAge() >= 100,
                        "the order aged while the warehouse was there"))
                .thenExecute(() -> {
                    first[0] = onlyOrder(helper, scene.requester(REQUESTER)).requestId();
                    for (ChunkPos chunk : chunks)
                        level.setChunkForced(chunk.x, chunk.z, false);
                })
                .thenWaitUntil(() -> helper.assertFalse(level.isLoaded(far.absoluteRackPos(TERMINAL_RACK)),
                        "the far terminal's chunk unloads"))
                .thenWaitUntil(() -> assertShows(helper, REQUESTER, RequesterStatus.LINK_NOT_LOADED))
                .thenExecute(() -> frozen[0] = onlyOrder(helper, scene.requester(REQUESTER)).liveAge())
                .thenExecuteFor(UNLOADED_TICKS, () -> {
                    SchematicannonRequesterBlockEntity requester = scene.requester(REQUESTER);
                    helper.assertValueEqual(requester.timeouts(), 0, "nothing is given up while unloaded");
                    helper.assertValueEqual(onlyOrder(helper, requester).liveAge(), (int) frozen[0],
                            "the order does not age while its warehouse is unloaded");
                    helper.assertFalse(level.isLoaded(far.absoluteRackPos(TERMINAL_RACK)),
                            "and the polls load nothing");
                })
                .thenExecute(() -> {
                    helper.assertTrue(frozen[0] < DEAD_MAN_TICKS, "frozen below the switch: " + frozen[0]);
                    scene.assertKept("while the warehouse is unloaded");
                    force(level, chunks);
                    back[0] = helper.getTick();
                })
                .thenWaitUntil(() -> helper.assertValueEqual(scene.requester(REQUESTER).timeouts(), 1,
                        "once the warehouse is back, the switch fires"))
                .thenExecute(() -> {
                    long waited = helper.getTick() - back[0];
                    long left = DEAD_MAN_TICKS - frozen[0];
                    // The first poll back may count up to two poll intervals at once (RequesterLimits#liveStep).
                    helper.assertTrue(waited >= left - 3L * poll(), "the switch fired after only " + waited
                            + " ticks back, with " + left + " live ticks left");
                    RequesterOrder<ItemKey> again = onlyOrder(helper, scene.requester(REQUESTER));
                    helper.assertFalse(again.requestId().equals(first[0]), "ordered again as a new request");
                    RetrievalRequest<ItemKey, BlockPos> request = onlyRequest(helper, far, TERMINAL_RACK);
                    helper.assertValueEqual(request.id(), again.requestId(), "at the far terminal");
                    helper.assertValueEqual(request.requested(), 5, "for the five");
                    helper.assertValueEqual(far.storedAt(STORAGE_RACK, STONE), 64L, "nothing left the far storage");
                    scene.assertKept("after the switch");
                    cleanUp();
                    ConfigOverrides.restoreAll();
                })
                .thenSucceed();
    }

    // --- what the schematic needs ----------------------------------------------------------------------------------

    /**
     * A stale counter: the requester ordered all twenty stone, and a player empties the cannon's chest mid-run. The
     * counter says nothing is left, but the cannon still misses stone, so the requester has Create remake the material
     * list — once — and orders exactly what that list still needs, rather than the one stone the cannon reports. The
     * print finishes with no third order and no second refresh.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void staleCounterRefreshesTheChecklistOnce(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, true, STONE.toStack(64));
        Scene scene = new Scene(helper, aisle);
        SchematicannonBlockEntity cannon = scene.print(SITE, repeat(Blocks.STONE.defaultBlockState(), 20));
        scene.place(REQUESTER, Direction.EAST).setGraceSeconds(0);
        Belt.start(helper, aisle, TERMINAL_RACK, CHEST, 0);
        scene.begin(scene.remoteFor(TERMINAL_RACK));
        OrderLog log = OrderLog.watch(helper, REQUESTER);
        List<Integer> lists = new ArrayList<>();
        helper.onEachTick(() -> {
            // Every material list the cannon makes, by what it requires of stone.
            int required = cannon.checklist.required.getInt(Items.STONE);
            if (required > 0 && (lists.isEmpty() || lists.getLast() != required))
                lists.add(required);
        });
        int[] taken = { -1 };

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> scene.insert(REQUESTER, 0))
                .thenWaitUntil(() -> helper.assertTrue(scene.printed(SITE, Blocks.STONE) >= 3
                        && aisle.inventoryCount(CHEST, STONE) > 0, "the cannon prints from the chest"))
                .thenExecute(() -> {
                    helper.assertValueEqual(log.shares(Items.STONE), List.of(20), "all twenty were ordered at once");
                    taken[0] = scene.printed(SITE, Blocks.STONE);
                    scene.take(aisle.handlerAt(CHEST), STONE, 64);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(log.shares(Items.STONE).size(), 2, "ordered again"))
                .thenExecute(() -> {
                    int again = log.shares(Items.STONE).get(1);
                    helper.assertValueEqual(lists.size(), 2, "the material list was made again, once: " + lists);
                    helper.assertValueEqual(again, lists.getLast(), "the order is what the new list needs");
                    helper.assertTrue(again > 1 && again <= 20 - taken[0], "neither the cannon's one nor the stale "
                            + "counter's none: " + again + " with " + taken[0] + " printed before");
                    scene.assertKept("after the second order");
                })
                .thenWaitUntil(() -> assertFinished(helper, cannon))
                .thenExecute(() -> {
                    helper.assertValueEqual(log.shares(Items.STONE).size(), 2, "no third order");
                    helper.assertValueEqual(lists.size(), 2, "and no second refresh: " + lists);
                    scene.assertKept("after the print");
                })
                .thenSucceed();
    }

    /**
     * Never production (owner rule 2), with oak planks that a pattern makes from the logs in stock. Straight at the
     * terminal, a stock-only request for sixteen planks with five in stock grants five and plans nothing. The requester
     * orders those five; once they are printed it shows "out of stock" for the rest, and no production order is ever
     * made, nor by a stock-only request with none in stock.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void neverStartsProduction(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, true, PLANK.toStack(5), LOG.toStack(16));
        aisle.production(PRODUCTION_RACK);
        Scene scene = new Scene(helper, aisle);
        scene.print(SITE, repeat(Blocks.OAK_PLANKS.defaultBlockState(), 16));
        scene.place(REQUESTER, Direction.EAST).setGraceSeconds(0);
        Belt.start(helper, aisle, TERMINAL_RACK, CHEST, 0);
        scene.begin(scene.remoteFor(TERMINAL_RACK));
        OrderLog log = OrderLog.watch(helper, REQUESTER);
        helper.onEachTick(() -> aisle.controllerIfPresent().ifPresent(controller -> helper.assertTrue(
                controller.productionOrders().isEmpty(), "no production order, ever: "
                        + controller.productionOrders())));

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> {
                    WarehouseProductionBlockEntity station = aisle.productionAt(PRODUCTION_RACK);
                    helper.assertTrue(station.setPatternEntry(0, 0, LOG, 1), "ingredient set");
                    helper.assertTrue(station.setPatternEntry(0, ProductionPatterns.RESULT_ENTRY, PLANK, 4),
                            "result set");
                })
                .thenWaitUntil(() -> helper.assertTrue(aisle.terminalAt(TERMINAL_RACK).producibleKeys()
                        .contains(PLANK), "the planks are producible"))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    RequestResult direct = terminal.requestStockOnly(PLANK, 16);
                    helper.assertTrue(direct.isAccepted(), "a stock-only request is accepted: " + direct);
                    helper.assertValueEqual(direct.granted(), 5, "for the stock");
                    helper.assertValueEqual(direct.producing(), 0, "and nothing made");
                    helper.assertValueEqual(direct.request().orElseThrow().requested(), 5, "the request is the stock");
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertTrue(controller.productionOrders().isEmpty(), "no production order");
                    controller.reduceRequestShare(direct.request().orElseThrow().id(), direct.granted());
                    helper.assertFalse(controller.hasOpenRequest(direct.request().orElseThrow().id()),
                            "given back again");
                    scene.insert(REQUESTER, 0);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(log.shares(Items.OAK_PLANKS), List.of(5),
                        "the requester orders the five in stock"))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(scene.printed(SITE, Blocks.OAK_PLANKS), 5, "and they are printed");
                    assertShows(helper, REQUESTER, RequesterStatus.OUT_OF_STOCK);
                })
                .thenIdle(3 * poll())
                .thenExecute(() -> {
                    assertShows(helper, REQUESTER, RequesterStatus.OUT_OF_STOCK);
                    helper.assertValueEqual(log.shares(Items.OAK_PLANKS), List.of(5), "nothing more is ordered");
                    helper.assertTrue(scene.requests(TERMINAL_RACK).isEmpty(), "no request is open");
                    RequestResult none = aisle.terminalAt(TERMINAL_RACK).requestStockOnly(PLANK, 16);
                    helper.assertValueEqual(none.rejection(), Optional.of(RequestRejection.NOT_IN_STOCK),
                            "a stock-only request with none in stock is refused");
                    helper.assertValueEqual(aisle.controller().countOf(LOG), 16L, "and every log stays in stock");
                    scene.assertKept("with the rest out of stock");
                })
                .thenSucceed();
    }

    /**
     * A Stock Keeper's reserve holds stone back from the requester, as from any automation: with all twenty reserved
     * the lamp is red and nothing is ordered; with fifteen reserved, five are.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void reserveHoldsItBack(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, true, STONE.toStack(20));
        aisle.stockKeeper(KEEPER_RACK);
        Scene scene = new Scene(helper, aisle);
        scene.print(SITE, repeat(Blocks.STONE.defaultBlockState(), 10));
        scene.place(REQUESTER, Direction.EAST);
        scene.begin(scene.remoteFor(TERMINAL_RACK));

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> {
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, STONE, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_RESERVE, null, 20L);
                    scene.insert(REQUESTER, 0);
                })
                .thenWaitUntil(() -> assertShows(helper, REQUESTER, RequesterStatus.RESERVED))
                .thenIdle(3 * poll())
                .thenExecute(() -> {
                    assertShows(helper, REQUESTER, RequesterStatus.RESERVED);
                    helper.assertTrue(scene.requests(TERMINAL_RACK).isEmpty(), "the reserve is not ordered");
                    aisle.stockKeeperAt(KEEPER_RACK).editRule(0, StockKeeperRules.FIELD_RESERVE, null, 15L);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(scene.requests(TERMINAL_RACK).size(), 1,
                        "a lower reserve lets some through"))
                .thenExecute(() -> {
                    helper.assertValueEqual(onlyRequest(helper, scene, TERMINAL_RACK).requested(), 5,
                            "exactly what the reserve leaves");
                    scene.assertKept("after the order");
                })
                .thenSucceed();
    }

    /**
     * A patterned banner is a strict requirement: it is ordered with exactly its patterns, never as one of the plain
     * banners beside it in stock, and once the one patterned banner in stock is printed the second shows "out of
     * stock" while the plain ones stay where they are.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void strictRequirementOrdersExactComponents(GameTestHelper helper) {
        ItemStack patterned = new ItemStack(Items.WHITE_BANNER);
        patterned.set(DataComponents.BANNER_PATTERNS, new BannerPatternLayers.Builder().addIfRegistered(
                helper.getLevel().registryAccess().lookupOrThrow(Registries.BANNER_PATTERN),
                BannerPatterns.STRIPE_BOTTOM, DyeColor.RED).build());
        for (int i = 0; i < 2; i++) {
            helper.setBlock(spot(SITE, i), Blocks.WHITE_BANNER);
            ((BannerBlockEntity) helper.getBlockEntity(spot(SITE, i))).fromItem(patterned, DyeColor.WHITE);
        }
        ItemKey strict = ItemKey.of(((BannerBlockEntity) helper.getBlockEntity(spot(SITE, 0))).getItem());
        helper.assertFalse(strict.equals(PLAIN_BANNER), "the printed banner carries its patterns");
        AisleFixture aisle = warehouse(helper, true, PLAIN_BANNER.toStack(3), strict.toStack(1));
        Scene scene = new Scene(helper, aisle);
        scene.print(SITE, List.of());
        scene.place(REQUESTER, Direction.EAST).setGraceSeconds(0);
        Belt.start(helper, aisle, TERMINAL_RACK, CHEST, 0);
        scene.begin(scene.remoteFor(TERMINAL_RACK));
        OrderLog log = OrderLog.watch(helper, REQUESTER);

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> scene.insert(REQUESTER, 0))
                .thenWaitUntil(() -> helper.assertValueEqual(log.placed().size(), 1, "an order"))
                .thenExecute(() -> {
                    RequesterOrder<ItemKey> order = log.placed().getFirst();
                    helper.assertValueEqual(order.key(), strict, "with exactly the banner's patterns");
                    helper.assertTrue(order.strict(), "as a strict requirement");
                    helper.assertValueEqual(order.share(), 1, "the one in stock");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(scene.printed(SITE, Blocks.WHITE_BANNER), 1, "the first banner stands");
                    assertShows(helper, REQUESTER, RequesterStatus.OUT_OF_STOCK);
                })
                .thenIdle(3 * poll())
                .thenExecute(() -> {
                    assertShows(helper, REQUESTER, RequesterStatus.OUT_OF_STOCK);
                    helper.assertValueEqual(log.placed().size(), 1, "no plain banner is ordered for it");
                    helper.assertValueEqual(aisle.controller().countOf(PLAIN_BANNER), 3L, "they stay in stock");
                    scene.assertKept("with the second banner out of stock");
                })
                .thenSucceed();
    }

    /**
     * A plain requirement takes any stone, and the requester orders the stone without components first: the two plain
     * ones, then — once they are gone — one of the twenty named ones, which stay where they are until then.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void plainRequirementPrefersNoComponents(GameTestHelper helper) {
        ItemStack namedStack = STONE.toStack(20);
        namedStack.set(DataComponents.CUSTOM_NAME, Component.literal("Quarry Stone"));
        ItemKey named = ItemKey.of(namedStack);
        AisleFixture aisle = warehouse(helper, true, namedStack, STONE.toStack(2));
        Scene scene = new Scene(helper, aisle);
        SchematicannonBlockEntity cannon = scene.print(SITE, repeat(Blocks.STONE.defaultBlockState(), 3));
        scene.place(REQUESTER, Direction.EAST).setGraceSeconds(0);
        Belt.start(helper, aisle, TERMINAL_RACK, CHEST, 0);
        scene.begin(scene.remoteFor(TERMINAL_RACK));
        OrderLog log = OrderLog.watch(helper, REQUESTER);

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> scene.insert(REQUESTER, 0))
                .thenWaitUntil(() -> assertFinished(helper, cannon))
                .thenExecute(() -> {
                    List<RequesterOrder<ItemKey>> placed = log.placed();
                    helper.assertValueEqual(placed.size(), 2, "two orders");
                    helper.assertValueEqual(placed.get(0).key(), STONE, "plain stone first");
                    helper.assertValueEqual(placed.get(0).share(), 2, "both of it");
                    helper.assertValueEqual(placed.get(1).key(), named, "then the named stone");
                    helper.assertValueEqual(placed.get(1).share(), 1, "one of it");
                    helper.assertValueEqual(aisle.controller().countOf(named), 19L, "nineteen named stay in stock");
                    scene.assertKeptByItem("after the print, named or not");
                })
                .thenSucceed();
    }

    // --- the cannon's own settings ---------------------------------------------------------------------------------

    /**
     * A cannon that skips missing blocks never asks for anything (a cannon placed from its item starts that way): at
     * insertion the player is told so once in chat, besides the link line on the action bar, the lamp is red and
     * nothing is ordered. With the option off and the print started, the stone is ordered.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void skipMissingCannonShowsTheHint(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, true, STONE.toStack(64));
        Scene scene = new Scene(helper, aisle);
        // Not started yet: a cannon that skips missing blocks would skip the whole schematic at once.
        scene.playing = false;
        SchematicannonBlockEntity cannon = scene.print(SITE, repeat(Blocks.STONE.defaultBlockState(), 5));
        cannon.skipMissing = true;
        scene.place(REQUESTER, Direction.EAST);
        scene.begin(scene.remoteFor(TERMINAL_RACK));

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> {
                    List<ClientboundSystemChatPacket> told = scene.insertTelling(REQUESTER, 0);
                    helper.assertValueEqual(told.size(), 2, "two lines: " + scene.player.toldKeys());
                    helper.assertValueEqual(scene.player.toldKeys(), List.of(
                            WareworksLang.key(WareworksLang.REQUESTER_LINKED),
                            WareworksLang.key(WareworksLang.REQUESTER_SKIP_MISSING_WARNING)), "what the player was told");
                    helper.assertTrue(told.get(0).overlay(), "the link on the action bar");
                    helper.assertFalse(told.get(1).overlay(), "the hint in chat");
                    scene.player.told.clear();
                })
                .thenWaitUntil(() -> assertShows(helper, REQUESTER, RequesterStatus.SKIP_MISSING))
                .thenIdle(3 * poll())
                .thenExecute(() -> {
                    helper.assertTrue(scene.requests(TERMINAL_RACK).isEmpty(), "nothing is ordered");
                    scene.assertKept("while the cannon skips");
                    cannon.skipMissing = false;
                    scene.playing = true;
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(scene.requests(TERMINAL_RACK).size(), 1, "the stone is ordered ("
                            + state(helper, cannon, REQUESTER) + ")");
                    assertShows(helper, REQUESTER, RequesterStatus.ORDERED);
                })
                .thenExecute(() -> scene.assertKept("after the order"))
                .thenSucceed();
    }

    /**
     * A creative crate feeds the cannon: it prints everything from nothing, and the requester never orders and never
     * lights or reddens its lamp.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void creativeCrateNeedsNothing(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, true, STONE.toStack(64));
        Scene scene = new Scene(helper, aisle);
        SchematicannonBlockEntity cannon = scene.print(SITE, repeat(Blocks.STONE.defaultBlockState(), 8));
        helper.setBlock(CRATE, AllBlocks.CREATIVE_CRATE.getDefaultState());
        scene.place(REQUESTER, Direction.EAST);
        scene.printedFromStock = false;
        scene.begin(scene.remoteFor(TERMINAL_RACK));
        boolean[] creative = { false };
        helper.onEachTick(() -> aisle.controllerIfPresent().ifPresent(controller -> {
            BlockState state = helper.getBlockState(REQUESTER);
            helper.assertFalse(state.getValue(SchematicannonRequesterBlock.LIT)
                    || state.getValue(SchematicannonRequesterBlock.REFUSED), "the lamp stays dark");
            helper.assertTrue(controller.requestsFor(aisle.absoluteRackPos(TERMINAL_RACK)).isEmpty(),
                    "nothing is ordered");
            if (requesterIfPresent(helper, REQUESTER) instanceof SchematicannonRequesterBlockEntity requester
                    && requester.hasRemote() && requester.status() == RequesterStatus.CREATIVE)
                creative[0] = true;
        }));

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> scene.insert(REQUESTER, 0))
                .thenWaitUntil(() -> assertFinished(helper, cannon))
                .thenWaitUntil(() -> helper.assertTrue(creative[0], "the requester says the crate feeds the cannon"))
                .thenExecute(() -> {
                    helper.assertTrue(scene.requester(REQUESTER).orders().isEmpty(), "and has no order");
                    helper.assertValueEqual(aisle.controller().countOf(STONE), 64L, "the stock is untouched");
                    scene.assertKept("after the print");
                })
                .thenSucceed();
    }

    // --- the remote slot -------------------------------------------------------------------------------------------

    /**
     * Every refusal of a remote, each with the remote's own line on the action bar and the remote staying in the hand:
     * not linked; a Deployer's fake player (silently); a terminal out of range, in another dimension, or gone (another
     * identity on its spot); beyond the world border; and a slot that already holds a remote.
     */
    @GameTest(template = EMPTY_7X5X7, batch = BORDER_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void insertRefusals(GameTestHelper helper) {
        helper.setBlock(LONE_TERMINAL, terminalState());
        helper.setBlock(LONE_REQUESTER, requesterState(Direction.EAST));
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            ServerLevel level = helper.getLevel();
            WarehouseTerminalBlockEntity terminal = (WarehouseTerminalBlockEntity) helper.getBlockEntity(LONE_TERMINAL);
            BlockPos terminalPos = terminal.getBlockPos();
            BlockPos requesterPos = helper.absolutePos(LONE_REQUESTER);
            ClickingPlayer player = ClickingPlayer.at(helper, LONE_STAND);
            FakePlayer deployer = FakePlayerFactory.getMinecraft(level);
            ItemStack unlinked = WareworksItems.WAREHOUSE_REMOTE.asStack();
            ItemStack linked = remoteFor(level, terminal);
            ItemKey linkedKey = ItemKey.of(linked);
            ItemStack spare = remoteFor(level, terminal);
            ItemStack farAway = withLink(linked, new RemoteLink(GlobalPos.of(level.dimension(),
                    terminalPos.offset(WareworksConfig.remoteRange() + BEYOND_RANGE, 0, 0)), terminal.terminalId()));
            ItemStack nether = withLink(linked, new RemoteLink(GlobalPos.of(Level.NETHER,
                    terminalPos.atY(NETHER_Y)), terminal.terminalId()));
            ItemStack gone = withLink(linked, new RemoteLink(GlobalPos.of(level.dimension(), terminalPos),
                    UUID.nameUUIDFromBytes("another terminal".getBytes(StandardCharsets.UTF_8))));
            Map<ItemKey, Long> start;
            try {
                deployer.setItemInHand(InteractionHand.MAIN_HAND, spare);
                player.getInventory().setItem(0, unlinked);
                player.getInventory().setItem(1, linked);
                player.getInventory().setItem(2, farAway);
                player.getInventory().setItem(3, nether);
                player.getInventory().setItem(4, gone);
                start = ItemCensus.take(helper, player, deployer);
                SchematicannonRequesterBlockEntity requester = requesterAt(helper, LONE_REQUESTER);

                refused(helper, player, requester, 0, "an unlinked remote", WareworksLang.REMOTE_NOT_LINKED);
                Vec3 feet = Vec3.atBottomCenterOf(helper.absolutePos(LONE_STAND));
                deployer.moveTo(feet.x, feet.y, feet.z);
                helper.assertFalse(click(deployer, requesterPos, Direction.UP, false).consumesAction(),
                        "a Deployer's click is not taken");
                helper.assertFalse(requester.hasRemote(), "and puts nothing in");
                helper.assertTrue(deployer.getMainHandItem() == spare && spare.getCount() == 1,
                        "the Deployer keeps its remote");
                refused(helper, player, requester, 2, "a terminal out of range", WareworksLang.REMOTE_OUT_OF_RANGE);
                refused(helper, player, requester, 3, "a terminal in another dimension",
                        WareworksLang.REMOTE_OTHER_DIMENSION);
                refused(helper, player, requester, 4, "a terminal that is gone", WareworksLang.REMOTE_GONE);

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
                refused(helper, player, requester, 1, "a terminal beyond the world border",
                        WareworksLang.REMOTE_NOT_ALLOWED);
                cleanUp();

                player.getInventory().selected = 1;
                helper.assertTrue(click(player, requesterPos, Direction.UP, false).consumesAction(), "the insert");
                assertTold(helper, player, "the insert", WareworksLang.REQUESTER_LINKED);
                helper.assertTrue(requester.hasRemote() && player.getMainHandItem().isEmpty(), "the remote went in");
                helper.assertValueEqual(requester.owner(), Optional.of(player.getUUID()), "the owner");

                player.getInventory().setItem(1, deployer.getMainHandItem());
                deployer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                refused(helper, player, requester, 1, "a second remote", WareworksLang.REQUESTER_OCCUPIED);
                helper.assertValueEqual(ItemKey.of(requester.remote()), linkedKey,
                        "the first remote stays in: no swap");

                Map<ItemKey, Long> now = ItemCensus.take(helper, player, deployer);
                ItemCensus.change(now, ItemKey.of(requester.remote()), 1);
                helper.assertValueEqual(now, start, "every remote is accounted for");
            } finally {
                deployer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                cleanUp();
            }
        }).thenSucceed();
    }

    /**
     * A requester polling a terminal far away (the remote's range raised to reach it) keeps no chunk loaded: with the
     * cannon missing stone, it looks at the terminal every second, the released chunk unloads all the same, the
     * terminal's block entity with it, and the requester then says so; its polls never load the unloaded chunk again
     * (the {@code isLoaded} guard in front of every read). That the reads add no ticket while the chunk is loaded is
     * {@link #noTicketReadsHoldNoChunk}'s part.
     */
    @GameTest(template = AISLE_16X10X7, batch = UNLOAD_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void requesterLetsTheTerminalsChunkUnload(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.remoteRange, FAR_RANGE);
        ServerLevel level = helper.getLevel();
        BlockPos farPos = helper.absolutePos(REQUESTER).offset(UNLOAD_OFFSET, 0, UNLOAD_OFFSET);
        List<ChunkPos> chunks = List.of(new ChunkPos(farPos));
        force(level, chunks);
        Scene scene = new Scene(helper, null);
        scene.print(SITE, repeat(Blocks.STONE.defaultBlockState(), 5));
        scene.place(REQUESTER, Direction.EAST);
        List<WarehouseTerminalBlockEntity> placed = new ArrayList<>();

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isLoaded(farPos) && level.shouldTickBlocksAt(farPos),
                        "the far chunk is loaded and ticking"))
                .thenExecute(() -> {
                    remember(level, farPos, farPos);
                    level.setBlockAndUpdate(farPos, terminalState());
                })
                .thenIdle(1)
                .thenExecute(() -> {
                    if (!(level.getBlockEntity(farPos) instanceof WarehouseTerminalBlockEntity terminal))
                        throw new GameTestAssertException("a terminal stands in the far chunk");
                    placed.add(terminal);
                    scene.begin(scene.remoteFor(terminal));
                    scene.insert(REQUESTER, 0);
                })
                .thenWaitUntil(() -> assertShows(helper, REQUESTER, RequesterStatus.NO_CONTROLLER))
                .thenExecute(() -> level.setChunkForced(chunks.getFirst().x, chunks.getFirst().z, false))
                .thenWaitUntil(() -> helper.assertTrue(!level.isLoaded(farPos) && placed.getFirst().isRemoved(),
                        "the terminal's chunk unloads, and its block entity with it"))
                .thenWaitUntil(() -> assertShows(helper, REQUESTER, RequesterStatus.LINK_NOT_LOADED))
                .thenIdle(3 * poll())
                .thenExecute(() -> {
                    helper.assertFalse(level.isLoaded(farPos), "and the polls since loaded nothing");
                    assertShows(helper, REQUESTER, RequesterStatus.LINK_NOT_LOADED);
                    scene.assertKept("after the unload");
                    cleanUp();
                    ConfigOverrides.restoreAll();
                })
                .thenSucceed();
    }

    /**
     * {@link NoTicket}, which every read of the requester and the remote away from their own position goes through,
     * adds no chunk ticket: two chunks far away are force-loaded, each with a barrel, and released in the same tick.
     * One barrel is read through {@code NoTicket} right after the release, the other through
     * {@code Level#getBlockEntity} as a control. The first chunk drops out at the very next tick; the control's chunk
     * is held longer by the {@code TicketType.UNKNOWN} ticket the read added
     * ({@code ServerChunkCache#getChunkFutureMainThread}), which shows the test can tell the two apart. Such a ticket
     * lives two ticks, so a read once a second could not hold a chunk for good with it, but a poller has no business
     * renewing one at all.
     */
    @GameTest(template = EMPTY_7X5X7, batch = NO_TICKET_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void noTicketReadsHoldNoChunk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos quiet = helper.absolutePos(BlockPos.ZERO.above()).offset(-UNLOAD_OFFSET, 0, UNLOAD_OFFSET);
        BlockPos control = quiet.offset(CONTROL_CHUNKS_AWAY * 16, 0, 0);
        force(level, List.of(new ChunkPos(quiet), new ChunkPos(control)));
        long[] released = {-1L};
        long[] quietGone = {-1L};
        long[] controlGone = {-1L};

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isLoaded(quiet) && level.isLoaded(control),
                        "both far chunks are loaded"))
                .thenExecute(() -> {
                    for (BlockPos pos : List.of(quiet, control)) {
                        remember(level, pos, pos);
                        level.setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState());
                    }
                })
                // Long enough for every ticket the placement itself added to run out.
                .thenIdle(TICKET_SETTLE_TICKS)
                .thenExecute(() -> {
                    level.setChunkForced(quiet.getX() >> 4, quiet.getZ() >> 4, false);
                    level.setChunkForced(control.getX() >> 4, control.getZ() >> 4, false);
                    released[0] = level.getGameTime();
                    helper.assertTrue(NoTicket.blockEntity(level, quiet, BarrelBlockEntity.class) != null,
                            "NoTicket reads the barrel while the chunk is still loaded");
                    helper.assertTrue(NoTicket.blockState(level, quiet) != null, "and its state");
                    helper.assertTrue(level.getBlockEntity(control) instanceof BarrelBlockEntity,
                            "the control reads its barrel the way NoTicket must not");
                })
                .thenWaitUntil(() -> {
                    long now = level.getGameTime();
                    if (quietGone[0] < 0 && !level.isLoaded(quiet))
                        quietGone[0] = now;
                    if (controlGone[0] < 0 && !level.isLoaded(control))
                        controlGone[0] = now;
                    helper.assertTrue(quietGone[0] >= 0 && controlGone[0] >= 0, "both released chunks drop out");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(quietGone[0] - released[0], 1L,
                            "the chunk read through NoTicket drops out at the next tick");
                    helper.assertTrue(controlGone[0] > quietGone[0], "the control's ticket held its chunk longer ("
                            + (controlGone[0] - released[0]) + " ticks): the test tells the two reads apart");
                    cleanUp();
                })
                .thenSucceed();
    }

    // --- several requesters ----------------------------------------------------------------------------------------

    /**
     * One requester per cannon: two of them face it, linked to two terminals, and only the one at the lower position
     * orders; the other stays idle with a red lamp. Once the first one's remote is taken out — its order given back —
     * the second takes over.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void secondRequesterOnOneCannonStaysIdle(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, false, STONE.toStack(64));
        aisle.terminal(OTHER_TERMINAL_RACK);
        Scene scene = new Scene(helper, aisle);
        scene.print(SITE, repeat(Blocks.STONE.defaultBlockState(), 5));
        scene.place(REQUESTER, Direction.EAST);
        scene.place(ON_THE_CANNON, Direction.DOWN);
        boolean sideFirst = helper.absolutePos(REQUESTER).asLong() < helper.absolutePos(ON_THE_CANNON).asLong();
        BlockPos first = sideFirst ? REQUESTER : ON_THE_CANNON;
        BlockPos second = sideFirst ? ON_THE_CANNON : REQUESTER;
        RackPosition firstTerminal = TERMINAL_RACK;
        RackPosition secondTerminal = OTHER_TERMINAL_RACK;
        scene.begin(scene.remoteFor(firstTerminal), scene.remoteFor(secondTerminal));

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> {
                    scene.insert(first, 0);
                    scene.insert(second, 1);
                })
                .thenWaitUntil(() -> {
                    assertShows(helper, first, RequesterStatus.ORDERED);
                    assertShows(helper, second, RequesterStatus.SECOND_REQUESTER);
                })
                .thenIdle(3 * poll())
                .thenExecute(() -> {
                    assertShows(helper, second, RequesterStatus.SECOND_REQUESTER);
                    helper.assertValueEqual(scene.requests(firstTerminal).size(), 1, "the first one ordered");
                    helper.assertTrue(scene.requests(secondTerminal).isEmpty(), "the second one did not");
                    helper.assertTrue(scene.requester(second).orders().isEmpty(), "and has no order");
                    scene.assertKept("with two requesters on one cannon");
                    scene.player.getInventory().selected = 2;
                    helper.assertTrue(click(scene.player, helper.absolutePos(first), faceToClick(helper, first), true)
                            .consumesAction(), "the first remote is taken out");
                    helper.assertTrue(scene.requests(firstTerminal).isEmpty(), "and its order given back");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(scene.requests(secondTerminal).size(), 1, "the second one takes over");
                    assertShows(helper, second, RequesterStatus.ORDERED);
                })
                .thenExecute(() -> scene.assertKept("after the hand-over"))
                .thenSucceed();
    }

    /**
     * One requester per cannon, the other way round: the requester at the higher position has an order running when
     * the one at the lower position gets its remote. The lower one waits (red) instead of ordering the same stone a
     * second time at its own terminal, the running order is still followed, and once it is given back the lower one
     * takes over.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void lowerRequesterWaitsForTheOthersOrder(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, false, STONE.toStack(64));
        aisle.terminal(OTHER_TERMINAL_RACK);
        Scene scene = new Scene(helper, aisle);
        scene.print(SITE, repeat(Blocks.STONE.defaultBlockState(), 5));
        scene.place(REQUESTER, Direction.EAST);
        scene.place(ON_THE_CANNON, Direction.DOWN);
        boolean sideFirst = helper.absolutePos(REQUESTER).asLong() < helper.absolutePos(ON_THE_CANNON).asLong();
        BlockPos lower = sideFirst ? REQUESTER : ON_THE_CANNON;
        BlockPos higher = sideFirst ? ON_THE_CANNON : REQUESTER;
        RackPosition higherTerminal = TERMINAL_RACK;
        RackPosition lowerTerminal = OTHER_TERMINAL_RACK;
        scene.begin(scene.remoteFor(higherTerminal), scene.remoteFor(lowerTerminal));

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> scene.insert(higher, 0))
                .thenWaitUntil(() -> {
                    assertShows(helper, higher, RequesterStatus.ORDERED);
                    helper.assertValueEqual(scene.requests(higherTerminal).size(), 1, "the higher one ordered");
                })
                .thenExecute(() -> scene.insert(lower, 1))
                .thenWaitUntil(() -> assertShows(helper, lower, RequesterStatus.SECOND_REQUESTER))
                .thenIdle(3 * poll())
                .thenExecute(() -> {
                    assertShows(helper, lower, RequesterStatus.SECOND_REQUESTER);
                    helper.assertTrue(scene.requests(lowerTerminal).isEmpty(),
                            "the lower one orders no second stack while the other one's runs");
                    helper.assertTrue(scene.requester(lower).orders().isEmpty(), "and has no order");
                    helper.assertValueEqual(onlyRequest(helper, scene, higherTerminal).requested(), 5,
                            "the running order is untouched");
                    helper.assertTrue(onlyOrder(helper, scene.requester(higher)).isRunning(), "and still followed");
                    scene.assertKept("with an order running on the other requester");
                    scene.player.getInventory().selected = 2;
                    helper.assertTrue(click(scene.player, helper.absolutePos(higher), faceToClick(helper, higher),
                            true).consumesAction(), "the higher one's remote is taken out");
                    helper.assertTrue(scene.requests(higherTerminal).isEmpty(), "and its order given back");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(scene.requests(lowerTerminal).size(), 1, "the lower one takes over");
                    assertShows(helper, lower, RequesterStatus.ORDERED);
                })
                .thenExecute(() -> scene.assertKept("after the hand-over"))
                .thenSucceed();
    }

    /**
     * Two cannons, two requesters, one terminal: the stone they order merges into one request — the second cannon's
     * four first, then the first cannon's 64 behind them — and each is credited with its own share, first in, first
     * served ({@link #watchFirstInFirstServed}, every tick). After the crane's first trip the four are through and the
     * 64 still run; after the second both are, and with the stone lying in the buffer neither orders again.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void twoRequestersOnOneTerminalCreditApart(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, false, STONE.toStack(64), STONE.toStack(36));
        Scene scene = new Scene(helper, aisle);
        scene.print(SITE_A, repeat(Blocks.STONE.defaultBlockState(), 64));
        scene.print(SITE_B, repeat(Blocks.STONE.defaultBlockState(), 4));
        scene.place(REQUESTER, Direction.EAST);
        scene.place(REQUESTER_B, Direction.EAST);
        scene.begin(scene.remoteFor(TERMINAL_RACK), scene.remoteFor(TERMINAL_RACK));
        OrderLog logA = OrderLog.watch(helper, REQUESTER);
        OrderLog logB = OrderLog.watch(helper, REQUESTER_B);
        watchFirstInFirstServed(helper, aisle, TERMINAL_RACK, REQUESTER, REQUESTER_B);

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> scene.insert(REQUESTER_B, 1))
                .thenWaitUntil(() -> helper.assertValueEqual(logB.shares(Items.STONE), List.of(4),
                        "the second cannon's requester orders first"))
                .thenExecute(() -> scene.insert(REQUESTER, 0))
                .thenWaitUntil(() -> helper.assertValueEqual(logA.shares(Items.STONE), List.of(64),
                        "then the first cannon's"))
                .thenExecute(() -> {
                    RetrievalRequest<ItemKey, BlockPos> request = onlyRequest(helper, scene, TERMINAL_RACK);
                    helper.assertValueEqual(request.requested(), 68, "merged into one request");
                    RequesterOrder<ItemKey> a = onlyOrder(helper, scene.requester(REQUESTER));
                    RequesterOrder<ItemKey> b = onlyOrder(helper, scene.requester(REQUESTER_B));
                    helper.assertTrue(a.requestId().equals(request.id()) && b.requestId().equals(request.id()),
                            "both follow it");
                    helper.assertValueEqual(b.ahead(), 0, "the four ahead of nobody");
                    helper.assertValueEqual(a.ahead(), 4, "the 64 behind the four");
                    scene.assertKept("before the crane moves");
                    aisle.placeMotor();
                })
                .thenWaitUntil(() -> {
                    helper.assertFalse(onlyOrder(helper, scene.requester(REQUESTER_B)).isRunning(),
                            "the four are through");
                    helper.assertTrue(onlyOrder(helper, scene.requester(REQUESTER)).isRunning(),
                            "while the 64 behind them still run");
                    helper.assertTrue(aisle.stationCount(TERMINAL_RACK, STONE) < 68L, "with the request open");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, STONE), 68L, "the second trip");
                    helper.assertFalse(onlyOrder(helper, scene.requester(REQUESTER)).isRunning(), "the 64 are through");
                })
                .thenIdle(3 * poll())
                .thenExecute(() -> {
                    helper.assertValueEqual(logA.placed().size() + logB.placed().size(), 2,
                            "neither orders again while the stone lies in the buffer");
                    helper.assertTrue(scene.requests(TERMINAL_RACK).isEmpty(), "no request is open");
                    scene.assertKept("after both trips");
                })
                .thenSucceed();
    }

    // --- giving back -----------------------------------------------------------------------------------------------

    /**
     * Taking the remote out (sneaking with an empty hand) gives back the requester's five and nothing of the player's
     * ten merged ahead of them; the remote comes back into the hand. Put in again it orders the five again, and
     * breaking the block gives them back once more and drops the remote where the block stood.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void removalAndBreakingGiveBackShares(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, false, STONE.toStack(64));
        Scene scene = new Scene(helper, aisle);
        scene.print(SITE, repeat(Blocks.STONE.defaultBlockState(), 5));
        scene.place(REQUESTER, Direction.EAST);
        scene.begin(scene.remoteFor(TERMINAL_RACK));

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> {
                    helper.assertTrue(aisle.terminalAt(TERMINAL_RACK).requestFromTerminal(
                            playerAtTerminal(helper, aisle), STONE, PLAYER_SHARE).isAccepted(), "the player's click");
                    scene.insert(REQUESTER, 0);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(onlyRequest(helper, scene, TERMINAL_RACK).requested(),
                        PLAYER_SHARE + 5, "the requester's five merged"))
                .thenExecute(() -> {
                    ClickingPlayer player = scene.player;
                    helper.assertTrue(player.getMainHandItem().isEmpty(), "an empty hand");
                    helper.assertTrue(click(player, helper.absolutePos(REQUESTER), Direction.UP, true).consumesAction(),
                            "the sneaking click");
                    assertTold(helper, player, "the removal", WareworksLang.REQUESTER_REMOVED);
                    helper.assertValueEqual(ItemKey.of(player.getMainHandItem()), scene.remotes.getFirst(),
                            "the remote in the hand");
                    SchematicannonRequesterBlockEntity requester = scene.requester(REQUESTER);
                    helper.assertFalse(requester.hasRemote(), "the slot is empty");
                    helper.assertTrue(requester.orders().isEmpty() && requester.owner().isEmpty(),
                            "the orders and the owner are forgotten");
                    helper.assertValueEqual(onlyRequest(helper, scene, TERMINAL_RACK).requested(), PLAYER_SHARE,
                            "the five given back, the player's ten kept");
                    scene.assertKept("after the removal");
                    scene.insert(REQUESTER, player.getInventory().selected);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(onlyRequest(helper, scene, TERMINAL_RACK).requested(),
                        PLAYER_SHARE + 5, "put in again, it orders the five again"))
                .thenExecute(() -> {
                    aisle.breakBlock(REQUESTER);
                    helper.assertValueEqual(onlyRequest(helper, scene, TERMINAL_RACK).requested(), PLAYER_SHARE,
                            "breaking the block gives them back as well");
                    List<ItemEntity> dropped = helper.getEntities(EntityType.ITEM);
                    helper.assertTrue(dropped.size() == 1 && ItemKey.of(dropped.getFirst().getItem()).equals(scene.remotes.getFirst()),
                            "and drops the remote: " + dropped);
                    scene.assertKept("after the break");
                })
                .thenSucceed();
    }

    /**
     * A command or a structure that replaces the block ({@code /setblock}, {@code /fill}: {@code Clearable#tryClear},
     * then the new block) gives the requester's five back just as breaking it does, and the player's ten merged ahead
     * of them stay. The remote is cleared with the block, as every container's contents are, and nothing drops.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void replacingTheBlockGivesBackShares(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, false, STONE.toStack(64));
        Scene scene = new Scene(helper, aisle);
        scene.print(SITE, repeat(Blocks.STONE.defaultBlockState(), 5));
        scene.place(REQUESTER, Direction.EAST);
        scene.begin(scene.remoteFor(TERMINAL_RACK));

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> {
                    helper.assertTrue(aisle.terminalAt(TERMINAL_RACK).requestFromTerminal(
                            playerAtTerminal(helper, aisle), STONE, PLAYER_SHARE).isAccepted(), "the player's click");
                    scene.insert(REQUESTER, 0);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(onlyRequest(helper, scene, TERMINAL_RACK).requested(),
                        PLAYER_SHARE + 5, "the requester's five merged"))
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    BlockPos pos = helper.absolutePos(REQUESTER);
                    ItemKey remote = ItemKey.of(scene.requester(REQUESTER).remote());
                    // What SetBlockCommand does in its default mode: clear the block entity, then place the new block.
                    Clearable.tryClear(level.getBlockEntity(pos));
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    helper.assertValueEqual(onlyRequest(helper, scene, TERMINAL_RACK).requested(), PLAYER_SHARE,
                            "the five given back, the player's ten kept");
                    helper.assertTrue(helper.getEntities(EntityType.ITEM).isEmpty(),
                            "nothing drops: the command cleared the remote");
                    Map<ItemKey, Long> now = scene.census();
                    ItemCensus.change(now, remote, 1);
                    helper.assertValueEqual(now, scene.start, "everything but the cleared remote is accounted for");
                })
                .thenSucceed();
    }

    /**
     * A print that stops strands nothing: two polls after the stop button — after which Create loads the schematic
     * again at once and waits paused on "ready" — the requester gives back its open share, the player's ten merged
     * with it stay, and it has no order left.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void givesBackWhenThePrintStops(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, false, STONE.toStack(64));
        Scene scene = new Scene(helper, aisle);
        SchematicannonBlockEntity cannon = scene.print(SITE, repeat(Blocks.STONE.defaultBlockState(), 5));
        scene.place(REQUESTER, Direction.EAST);
        scene.begin(scene.remoteFor(TERMINAL_RACK));

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> {
                    helper.assertTrue(aisle.terminalAt(TERMINAL_RACK).requestFromTerminal(
                            playerAtTerminal(helper, aisle), STONE, PLAYER_SHARE).isAccepted(), "the player's click");
                    scene.insert(REQUESTER, 0);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(onlyRequest(helper, scene, TERMINAL_RACK).requested(),
                        PLAYER_SHARE + 5, "the requester's five merged"))
                .thenExecute(() -> {
                    // The stop button, as ConfigureSchematicannonPacket presses it; nobody presses play again.
                    scene.playing = false;
                    cannon.state = SchematicannonBlockEntity.State.STOPPED;
                    cannon.statusMsg = "stopped";
                })
                .thenWaitUntil(() -> helper.assertTrue(scene.requester(REQUESTER).orders().isEmpty(),
                        "the order is given back (" + state(helper, cannon, REQUESTER) + ")"))
                .thenExecute(() -> {
                    helper.assertValueEqual(onlyRequest(helper, scene, TERMINAL_RACK).requested(), PLAYER_SHARE,
                            "only the requester's own five");
                    helper.assertValueEqual(scene.requester(REQUESTER).orderedSinceList(Items.STONE), 0,
                            "and the counter lowered by them");
                    assertShows(helper, REQUESTER, RequesterStatus.NOT_PRINTING);
                    scene.assertKept("after the give-back");
                })
                .thenSucceed();
    }

    // --- persistence -----------------------------------------------------------------------------------------------

    /**
     * A full save keeps the remote, the owner, the running order (id, share, place, phase, live age), the counter and
     * the grace; a reloaded requester resumes the order by its request id once the crane moves. What a schematic keeps
     * is the grace only.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void requesterSurvivesASaveRoundTrip(GameTestHelper helper) {
        AisleFixture aisle = warehouse(helper, false, STONE.toStack(64));
        Scene scene = new Scene(helper, aisle);
        scene.print(SITE, repeat(Blocks.STONE.defaultBlockState(), 5));
        scene.place(REQUESTER, Direction.EAST).setGraceSeconds(SAVED_GRACE_SECONDS);
        scene.begin(scene.remoteFor(TERMINAL_RACK));

        helper.startSequence()
                .thenWaitUntil(() -> assertWarehouseReady(helper, aisle))
                .thenExecute(() -> scene.insert(REQUESTER, 0))
                .thenWaitUntil(() -> helper.assertTrue(onlyOrder(helper, scene.requester(REQUESTER)).liveAge() > 0,
                        "the order runs"))
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    HolderLookup.Provider registries = level.registryAccess();
                    SchematicannonRequesterBlockEntity requester = scene.requester(REQUESTER);
                    CompoundTag full = requester.saveWithFullMetadata(registries);
                    CompoundTag safe = new CompoundTag();
                    requester.writeSafe(safe, registries);
                    helper.assertValueEqual(Set.copyOf(safe.getAllKeys()), Set.of(GraceBehaviour.GRACE_TAG),
                            "a schematic keeps the grace and nothing else");

                    BlockEntity loaded = BlockEntity.loadStatic(requester.getBlockPos(), requester.getBlockState(),
                            full, registries);
                    if (!(loaded instanceof SchematicannonRequesterBlockEntity reloaded))
                        throw new GameTestAssertException("a saved requester loads again as one: " + loaded);
                    level.setBlockEntity(reloaded);
                    helper.assertTrue(requester.isRemoved() && scene.requester(REQUESTER) == reloaded,
                            "the requester was replaced by its reloaded copy");
                    helper.assertTrue(ItemStack.matches(reloaded.remote(), requester.remote()), "the remote");
                    helper.assertValueEqual(reloaded.owner(), requester.owner(), "the owner");
                    helper.assertValueEqual(reloaded.orders(), requester.orders(), "the orders, as they were");
                    helper.assertValueEqual(reloaded.orderedSinceList(Items.STONE), 5, "the counter");
                    helper.assertValueEqual(reloaded.graceSeconds(), SAVED_GRACE_SECONDS, "the grace");
                    scene.assertKept("after the reload");
                    aisle.placeMotor();
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, STONE), 5L, "delivered");
                    helper.assertValueEqual(onlyOrder(helper, scene.requester(REQUESTER)).phase(),
                            RequesterOrder.Phase.IN_BUFFER, "and the reloaded requester saw its order through");
                })
                .thenExecute(() -> scene.assertKept("after the delivery"))
                .thenSucceed();
    }

    // --- batches ---------------------------------------------------------------------------------------------------

    /** Restores the dead-man switch even when the test of {@link #CONFIG_BATCH} fails. */
    @AfterBatch(batch = CONFIG_BATCH)
    public static void restoreConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    /** Puts the far barrels back and releases their chunks even when the no-ticket test fails. */
    @AfterBatch(batch = NO_TICKET_BATCH)
    public static void restoreNoTicket(ServerLevel level) {
        cleanUp();
    }

    /** Puts the far terminal back, releases its chunk and restores the range even when the unload test fails. */
    @AfterBatch(batch = UNLOAD_BATCH)
    public static void restoreUnload(ServerLevel level) {
        cleanUp();
        ConfigOverrides.restoreAll();
    }

    /** Takes the far warehouse down, releases its chunks and restores the config even when the far test fails. */
    @AfterBatch(batch = FAR_BATCH)
    public static void restoreFar(ServerLevel level) {
        cleanUp();
        ConfigOverrides.restoreAll();
    }

    /** Puts the world border back even when the refusal test fails. */
    @AfterBatch(batch = BORDER_BATCH)
    public static void restoreBorder(ServerLevel level) {
        cleanUp();
    }

    // --- the scene -------------------------------------------------------------------------------------------------

    /**
     * One test's cannons, requesters and player, and its books: what the test started with ({@link #begin}) and the
     * census to hold it against ({@link #census}).
     */
    private static final class Scene {
        private final GameTestHelper helper;
        @Nullable
        private final AisleFixture aisle;
        private final ClickingPlayer player;
        private final List<Site> sites = new ArrayList<>();
        private final List<SchematicannonBlockEntity> cannons = new ArrayList<>();
        private final List<BlockPos> requesters = new ArrayList<>();
        /** The remotes the player was handed, by their keys. */
        private final List<ItemKey> remotes = new ArrayList<>();
        /** Whether the player presses play on a cannon paused on "ready" ({@link CannonFixture#cannonFor}). */
        private boolean playing = true;
        /** Whether the printed blocks came out of the chests; not with a creative crate. */
        private boolean printedFromStock = true;
        @Nullable
        private Map<ItemKey, Long> start;

        private Scene(GameTestHelper helper, @Nullable AisleFixture aisle) {
            this.helper = helper;
            this.aisle = aisle;
            this.player = ClickingPlayer.at(helper, STAND);
        }

        /** Builds {@code blocks} on the site's spots in print order and hands them to a running cannon. */
        SchematicannonBlockEntity print(Site site, List<BlockState> blocks) {
            for (int i = 0; i < blocks.size(); i++)
                helper.setBlock(spot(site, i), blocks.get(i));
            SchematicannonBlockEntity cannon = CannonFixture.cannonFor(helper, site, false, () -> playing);
            sites.add(site);
            cannons.add(cannon);
            return cannon;
        }

        /** A requester at {@code pos}, facing {@code facing}: at its cannon, unless the test wants otherwise. */
        SchematicannonRequesterBlockEntity place(BlockPos pos, Direction facing) {
            helper.setBlock(pos, requesterState(facing));
            requesters.add(pos);
            return requester(pos);
        }

        SchematicannonRequesterBlockEntity requester(BlockPos pos) {
            return requesterAt(helper, pos);
        }

        ItemStack remoteFor(RackPosition terminal) {
            return remoteFor(Objects.requireNonNull(aisle, "aisle").terminalAt(terminal));
        }

        ItemStack remoteFor(WarehouseTerminalBlockEntity terminal) {
            return SchematicannonRequesterGameTests.remoteFor(helper.getLevel(), terminal);
        }

        /** Hands the player {@code remotes} (hotbar slots 0, 1, ...) and takes the census everything is held to. */
        void begin(ItemStack... remotes) {
            for (int slot = 0; slot < remotes.length; slot++) {
                player.getInventory().setItem(slot, remotes[slot]);
                this.remotes.add(ItemKey.of(remotes[slot]));
            }
            start = census();
        }

        /** Puts the remote of hotbar {@code slot} into the requester at {@code pos} with a real click. */
        void insert(BlockPos pos, int slot) {
            insertTelling(pos, slot);
            assertTold(helper, player, "the insert", WareworksLang.REQUESTER_LINKED);
        }

        /** {@link #insert}, answering what the player was told; the caller checks and clears it. */
        List<ClientboundSystemChatPacket> insertTelling(BlockPos pos, int slot) {
            player.getInventory().selected = slot;
            helper.assertTrue(player.getMainHandItem().getItem() == WareworksItems.WAREHOUSE_REMOTE.get(),
                    "a remote in the hand");
            InteractionResult result = click(player, helper.absolutePos(pos), faceToClick(helper, pos), false);
            helper.assertTrue(result.consumesAction(), "the insert is taken: " + result);
            SchematicannonRequesterBlockEntity requester = requester(pos);
            helper.assertTrue(requester.hasRemote(), "the remote went in");
            helper.assertTrue(player.getMainHandItem().isEmpty(), "out of the hand");
            helper.assertValueEqual(requester.owner(), Optional.of(player.getUUID()),
                    "the player owns the orders");
            return List.copyOf(player.told);
        }

        /** The open requests at the terminal at {@code rack}. */
        List<RetrievalRequest<ItemKey, BlockPos>> requests(RackPosition rack) {
            AisleFixture fixture = Objects.requireNonNull(aisle, "aisle");
            return fixture.controller().requestsFor(fixture.absoluteRackPos(rack));
        }

        /** The player takes up to {@code amount} of {@code key} out of {@code handler} into their inventory. */
        void take(IItemHandler handler, ItemKey key, int amount) {
            int left = amount;
            for (int slot = 0; slot < handler.getSlots() && left > 0; slot++) {
                if (!key.matches(handler.getStackInSlot(slot)))
                    continue;
                ItemStack out = handler.extractItem(slot, left, false);
                left -= out.getCount();
                helper.assertTrue(player.getInventory().add(out), "the player has room for " + out);
            }
            helper.assertTrue(left < amount, "the player found " + key + " to take");
        }

        /** How many blocks of {@code block} the cannon has printed on {@code site}. */
        int printed(Site site, Block block) {
            int[] count = { 0 };
            CannonFixture.forEachSpot(site, pos -> {
                if (helper.getBlockState(pos).is(block))
                    count[0]++;
            });
            return count[0];
        }

        /**
         * The census: every item in the test's bounds and in the player's inventory, the remotes in the cradles, and
         * every printed or flying block as the items its requirement took.
         */
        Map<ItemKey, Long> census() {
            Map<ItemKey, Long> counts = ItemCensus.take(helper, player);
            for (BlockPos pos : requesters) {
                if (requesterIfPresent(helper, pos) instanceof SchematicannonRequesterBlockEntity requester
                        && requester.hasRemote())
                    ItemCensus.change(counts, ItemKey.of(requester.remote()), 1);
            }
            if (!printedFromStock)
                return counts;
            for (Site site : sites) {
                CannonFixture.forEachSpot(site, pos -> {
                    BlockState state = helper.getBlockState(pos);
                    if (state.isAir())
                        return;
                    for (ItemRequirement.StackRequirement required : ItemRequirement.of(state,
                            CannonFixture.blockEntityAt(helper, pos)).getRequiredItems()) {
                        if (required.usage == ItemRequirement.ItemUseType.CONSUME)
                            ItemCensus.change(counts, ItemKey.of(required.stack), required.stack.getCount());
                    }
                });
            }
            for (SchematicannonBlockEntity cannon : cannons) {
                for (LaunchedItem flying : cannon.flyingBlocks)
                    ItemCensus.change(counts, ItemKey.of(flying.stack), flying.stack.getCount());
            }
            return counts;
        }

        /** Nothing was made and nothing lost since {@link #begin}: the census equals the start exactly. */
        void assertKept(String what) {
            Map<ItemKey, Long> now = census();
            if (!now.equals(start))
                helper.fail("item conservation violated (" + what + "): expected " + ItemCensus.describe(start)
                        + " but found " + ItemCensus.describe(now));
        }

        /**
         * {@link #assertKept} by item: a plain requirement takes a named stack and the block it prints asks for a plain
         * one, so only the sum over an item's variants is conserved.
         */
        void assertKeptByItem(String what) {
            Map<Item, Long> expected = byItem(Objects.requireNonNull(start, "start"));
            Map<Item, Long> now = byItem(census());
            if (!now.equals(expected))
                helper.fail("item conservation violated (" + what + "): expected " + expected + " but found " + now);
        }

        private static Map<Item, Long> byItem(Map<ItemKey, Long> counts) {
            Map<Item, Long> items = new HashMap<>();
            counts.forEach((key, count) -> items.merge(key.getItem(), count, Long::sum));
            return items;
        }
    }

    /**
     * The player's funnel and belt from the terminal's buffer to the cannon's chest: every tick it takes everything out
     * of the buffer through the buffer's own extract-only handler into a barrel, and moves what has spent
     * {@code delay} ticks there on into the chest. Without a chest the barrel is where a player puts what they take.
     */
    private static final class Belt {
        private final GameTestHelper helper;
        private final AisleFixture aisle;
        private final RackPosition terminal;
        @Nullable
        private final BlockPos chest;
        private final int delay;
        private final Map<Item, Long> taken = new HashMap<>();
        private long loadedAt = -1L;

        private Belt(GameTestHelper helper, AisleFixture aisle, RackPosition terminal, @Nullable BlockPos chest,
                int delay) {
            this.helper = helper;
            this.aisle = aisle;
            this.terminal = terminal;
            this.chest = chest;
            this.delay = delay;
        }

        static Belt start(GameTestHelper helper, AisleFixture aisle, RackPosition terminal, @Nullable BlockPos chest,
                int delay) {
            helper.setBlock(BELT, Blocks.BARREL);
            Belt belt = new Belt(helper, aisle, terminal, chest, delay);
            helper.onEachTick(belt::tick);
            return belt;
        }

        /** The tick the belt last took {@code item} out of the buffer, or -1. */
        long takenAt(Item item) {
            return taken.getOrDefault(item, -1L);
        }

        private void tick() {
            if (aisle.controllerIfPresent().isEmpty())
                return;
            IItemHandler buffer = aisle.terminalAt(terminal).externalHandler();
            IItemHandler barrel = aisle.handlerAt(BELT);
            long now = helper.getTick();
            for (int slot = 0; slot < buffer.getSlots(); slot++) {
                ItemStack offered = buffer.extractItem(slot, 64, true);
                if (offered.isEmpty())
                    continue;
                int fits = offered.getCount() - ItemHandlerHelper.insertItemStacked(barrel, offered, true).getCount();
                if (fits <= 0)
                    continue;
                ItemHandlerHelper.insertItemStacked(barrel, buffer.extractItem(slot, fits, false), false);
                taken.put(offered.getItem(), now);
                if (loadedAt < 0)
                    loadedAt = now;
            }
            if (chest == null || loadedAt < 0 || now - loadedAt < delay)
                return;
            IItemHandler target = aisle.handlerAt(chest);
            boolean empty = true;
            for (int slot = 0; slot < barrel.getSlots(); slot++) {
                ItemStack stack = barrel.extractItem(slot, 64, false);
                ItemStack rest = ItemHandlerHelper.insertItemStacked(target, stack, false);
                if (!rest.isEmpty()) {
                    barrel.insertItem(slot, rest, false);
                    empty = false;
                }
            }
            loadedAt = empty ? -1L : now;
        }
    }

    /**
     * Every order a requester placed, in order: a running order of live age 0 not seen before. A placement keeps that
     * form until the next poll, so it is seen; an order placed again into the same request after the dead-man switch
     * looks the same and is not, which the dead-man tests check on their own.
     */
    private static final class OrderLog {
        private final List<RequesterOrder<ItemKey>> placed = new ArrayList<>();
        private final List<Long> placedAt = new ArrayList<>();
        private final Set<RequesterOrder<ItemKey>> seen = new LinkedHashSet<>();

        static OrderLog watch(GameTestHelper helper, BlockPos pos) {
            OrderLog log = new OrderLog();
            helper.onEachTick(() -> {
                if (!(requesterIfPresent(helper, pos) instanceof SchematicannonRequesterBlockEntity requester))
                    return;
                for (RequesterOrder<ItemKey> order : requester.orders()) {
                    if (order.isRunning() && order.liveAge() == 0 && log.seen.add(order)) {
                        log.placed.add(order);
                        log.placedAt.add(helper.getTick());
                    }
                }
            });
            return log;
        }

        List<RequesterOrder<ItemKey>> placed() {
            return List.copyOf(placed);
        }

        /** The shares of the orders of {@code item}, in order. */
        List<Integer> shares(Item item) {
            List<Integer> shares = new ArrayList<>();
            for (RequesterOrder<ItemKey> order : placed)
                if (order.key().getItem() == item)
                    shares.add(order.share());
            return shares;
        }

        /** When the {@code index}th order of {@code item} was placed. */
        long placedAt(Item item, int index) {
            int seenOfItem = 0;
            for (int i = 0; i < placed.size(); i++) {
                if (placed.get(i).key().getItem() != item)
                    continue;
                if (seenOfItem++ == index)
                    return placedAt.get(i);
            }
            throw new GameTestAssertException("no order " + index + " of " + item);
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** A warehouse on the aisle template: the aisle, a chest of {@code stock} and the terminal. */
    private static AisleFixture warehouse(GameTestHelper helper, boolean motor, ItemStack... stock) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(motor);
        aisle.storage(STORAGE_RACK, stock);
        aisle.terminal(TERMINAL_RACK);
        return aisle;
    }

    /** The controller has read its aisle and its storage and linked the dock, whatever stations stand in it. */
    private static void assertWarehouseReady(GameTestHelper helper, AisleFixture aisle) {
        WarehouseControllerBlockEntity controller = aisle.controller();
        helper.assertValueEqual(controller.status(), ControllerStatus.READY, "controller status");
        helper.assertFalse(controller.isMembershipDirty(), "membership processed");
        helper.assertValueEqual(controller.pendingSnapshotCount(), 0, "storage locations read");
        helper.assertTrue(aisle.dock().isControllerLinked(), "dock linked");
    }

    /** The cannon has printed its whole schematic. */
    private static void assertFinished(GameTestHelper helper, SchematicannonBlockEntity cannon) {
        helper.assertTrue("finished".equals(cannon.statusMsg), "the cannon has not finished; it says '"
                + cannon.statusMsg + "'" + (cannon.missingItem == null ? "" : ", missing " + cannon.missingItem));
    }

    /**
     * First in, first served, checked every tick: an order of any of {@code requesters} that is no longer running while
     * its request is still open at the terminal has had its own items delivered — everything ahead of it and its share.
     */
    private static void watchFirstInFirstServed(GameTestHelper helper, AisleFixture aisle, RackPosition terminal,
            BlockPos... requesters) {
        helper.onEachTick(() -> {
            if (aisle.controllerIfPresent().isEmpty())
                return;
            Map<UUID, RetrievalRequest<ItemKey, BlockPos>> open = new HashMap<>();
            for (RetrievalRequest<ItemKey, BlockPos> request : aisle.controller().requestsFor(
                    aisle.absoluteRackPos(terminal)))
                open.put(request.id(), request);
            for (BlockPos pos : requesters) {
                if (!(requesterIfPresent(helper, pos) instanceof SchematicannonRequesterBlockEntity requester))
                    continue;
                for (RequesterOrder<ItemKey> order : requester.orders()) {
                    RetrievalRequest<ItemKey, BlockPos> request = open.get(order.requestId());
                    if (!order.isRunning() && request != null)
                        helper.assertTrue(request.delivered() >= order.ahead() + order.share(), "an order left "
                                + "RUNNING with " + request.delivered() + " of its request delivered: " + order);
                }
            }
        });
    }

    /** What the cannon and the requester at {@code pos} are doing, for a failure message. */
    private static String state(GameTestHelper helper, SchematicannonBlockEntity cannon, BlockPos pos) {
        SchematicannonRequesterBlockEntity requester = requesterAt(helper, pos);
        return "cannon " + cannon.state + " '" + cannon.statusMsg + "' missing " + cannon.missingItem + ", removed "
                + cannon.isRemoved() + ", in the world " + (CannonFixture.blockEntityAt(helper, CANNON) == cannon)
                + "; requester " + requester.status() + ", sees " + requester.cannonPhase() + ", orders "
                + requester.orders();
    }

    /** The requester at {@code pos} shows {@code status}, and its lamps say the same. */
    private static void assertShows(GameTestHelper helper, BlockPos pos, RequesterStatus status) {
        helper.assertValueEqual(requesterAt(helper, pos).status(), status, "the requester's status");
        BlockState state = helper.getBlockState(pos);
        helper.assertValueEqual(state.getValue(SchematicannonRequesterBlock.LIT), status.isLit(), "the lit lamp");
        helper.assertValueEqual(state.getValue(SchematicannonRequesterBlock.REFUSED), status.isRefused(),
                "the red lamp");
    }

    private static RequesterOrder<ItemKey> onlyOrder(GameTestHelper helper,
            SchematicannonRequesterBlockEntity requester) {
        List<RequesterOrder<ItemKey>> orders = requester.orders();
        helper.assertValueEqual(orders.size(), 1, "open orders of the requester");
        return orders.getFirst();
    }

    private static RetrievalRequest<ItemKey, BlockPos> onlyRequest(GameTestHelper helper, Scene scene,
            RackPosition rack) {
        return onlyRequest(helper, Objects.requireNonNull(scene.aisle, "aisle"), rack);
    }

    private static RetrievalRequest<ItemKey, BlockPos> onlyRequest(GameTestHelper helper, AisleFixture aisle,
            RackPosition rack) {
        List<RetrievalRequest<ItemKey, BlockPos>> open = aisle.controller().requestsFor(aisle.absoluteRackPos(rack));
        helper.assertValueEqual(open.size(), 1, "open requests at the terminal");
        return open.getFirst();
    }

    private static SchematicannonRequesterBlockEntity requesterAt(GameTestHelper helper, BlockPos pos) {
        SchematicannonRequesterBlockEntity be = requesterIfPresent(helper, pos);
        if (be == null)
            helper.fail("missing schematicannon requester block entity", pos);
        return be;
    }

    @Nullable
    private static SchematicannonRequesterBlockEntity requesterIfPresent(GameTestHelper helper, BlockPos pos) {
        return WareworksBlockEntityTypes.SCHEMATICANNON_REQUESTER.getNullable(helper.getLevel(),
                helper.absolutePos(pos));
    }

    private static BlockState requesterState(Direction facing) {
        return WareworksBlocks.SCHEMATICANNON_REQUESTER.getDefaultState().setValue(SchematicannonRequesterBlock.FACING,
                facing);
    }

    private static BlockState terminalState() {
        return WareworksBlocks.WAREHOUSE_TERMINAL.getDefaultState().setValue(HorizontalDirectionalBlock.FACING,
                Direction.NORTH);
    }

    /** A Warehouse Remote linked to {@code terminal}, as a sneaking click on it links one. */
    private static ItemStack remoteFor(ServerLevel level, WarehouseTerminalBlockEntity terminal) {
        return withLink(WareworksItems.WAREHOUSE_REMOTE.asStack(), new RemoteLink(GlobalPos.of(level.dimension(),
                terminal.getBlockPos()), terminal.terminalId()));
    }

    private static ItemStack withLink(ItemStack remote, RemoteLink link) {
        ItemStack linked = remote.copyWithCount(1);
        linked.set(WareworksDataComponents.REMOTE_LINK.get(), link);
        return linked;
    }

    /** {@code count} copies of {@code state}. */
    private static List<BlockState> repeat(BlockState state, int count) {
        return Collections.nCopies(count, state);
    }

    /** The {@code index}th spot of {@code site} in the order the cannon prints: x first, then z, then up. */
    private static BlockPos spot(Site site, int index) {
        int perLayer = site.size().getX() * site.size().getZ();
        return site.min().offset(index % site.size().getX(), index / perLayer,
                (index % perLayer) / site.size().getX());
    }

    /** A face of the requester at {@code pos} to click: not its front, and not the back with the grace box. */
    private static Direction faceToClick(GameTestHelper helper, BlockPos pos) {
        Direction facing = helper.getBlockState(pos).getValue(SchematicannonRequesterBlock.FACING);
        return facing.getAxis() == Direction.Axis.Y ? Direction.NORTH : Direction.UP;
    }

    /** A player beside the terminal of the aisle, close enough to use it. */
    private static Player playerAtTerminal(GameTestHelper helper, AisleFixture aisle) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 center = Vec3.atCenterOf(aisle.absoluteRackPos(TERMINAL_RACK));
        player.moveTo(center.x, center.y, center.z);
        return player;
    }

    /**
     * One right-click with the main hand on {@code face} of the absolute {@code pos}, sneaking or not, through
     * {@code ServerPlayerGameMode#useItemOn} (the interaction event, {@code onItemUseFirst}, the sneak guard, the block
     * and {@code stack.useOn}, in vanilla's order).
     */
    private static InteractionResult click(ServerPlayer player, BlockPos pos,
            Direction face, boolean sneaking) {
        Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        player.setShiftKeyDown(sneaking);
        InteractionResult result = player.gameMode.useItemOn(player, player.serverLevel(), player.getMainHandItem(),
                InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
        player.setShiftKeyDown(false);
        return result;
    }

    /** The remote of hotbar {@code slot} is refused with {@code key} on the action bar and stays in the hand. */
    private static void refused(GameTestHelper helper, ClickingPlayer player,
            SchematicannonRequesterBlockEntity requester, int slot, String what, String key) {
        player.getInventory().selected = slot;
        ItemStack held = player.getMainHandItem();
        click(player, requester.getBlockPos(), Direction.UP, false);
        assertTold(helper, player, what, key);
        helper.assertTrue(player.getMainHandItem() == held && held.getCount() == 1, what + ": the remote stays");
    }

    /** The player was told exactly these lines on the action bar since the last check, which this one starts. */
    private static void assertTold(GameTestHelper helper, ClickingPlayer player, String what, String... relativeKeys) {
        List<String> expected = new ArrayList<>(relativeKeys.length);
        for (String key : relativeKeys)
            expected.add(WareworksLang.key(key));
        helper.assertValueEqual(player.toldKeys(), List.copyOf(expected), "what " + what + " told the player");
        for (ClientboundSystemChatPacket line : player.told)
            helper.assertTrue(line.overlay(), what + ": told on the action bar, not in chat");
        player.told.clear();
    }

    private static int poll() {
        return WareworksConfig.requesterPollIntervalTicks();
    }

    // --- far chunks ------------------------------------------------------------------------------------------------

    /** Every chunk that holds a block of the absolute box {@code min}..{@code max}. */
    private static List<ChunkPos> chunksBetween(BlockPos min, BlockPos max) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (int x = min.getX() >> 4; x <= max.getX() >> 4; x++)
            for (int z = min.getZ() >> 4; z <= max.getZ() >> 4; z++)
                chunks.add(new ChunkPos(x, z));
        return chunks;
    }

    /** Force-loads {@code chunks}; each is released again by {@link #cleanUp} if this forced it. */
    private static void force(ServerLevel level, List<ChunkPos> chunks) {
        for (ChunkPos chunk : chunks) {
            if (level.setChunkForced(chunk.x, chunk.z, true))
                CLEANUPS.push(() -> level.setChunkForced(chunk.x, chunk.z, false));
        }
    }

    /** Remembers every block of the absolute box, for {@link #cleanUp} to put back, emptied first so nothing drops. */
    private static void remember(ServerLevel level, BlockPos min, BlockPos max) {
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockPos at = pos.immutable();
            BlockState before = level.getBlockState(at);
            CLEANUPS.push(() -> {
                Clearable.tryClear(level.getBlockEntity(at));
                level.setBlock(at, before, Block.UPDATE_ALL);
            });
        }
    }

    private static void cleanUp() {
        while (!CLEANUPS.isEmpty())
            CLEANUPS.pop().run();
    }
}
