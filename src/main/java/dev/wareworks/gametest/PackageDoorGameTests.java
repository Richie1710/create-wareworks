package dev.wareworks.gametest;

import static dev.wareworks.gametest.PackageDoorFixture.contentsOf;
import static dev.wareworks.gametest.PackageDoorFixture.loadCopy;
import static dev.wareworks.gametest.PackageDoorFixture.packageOf;
import static dev.wareworks.gametest.PackageDoorFixture.packagerAt;
import static dev.wareworks.gametest.PackageDoorFixture.placePackager;
import static dev.wareworks.gametest.PackageDoorFixture.writeSign;
import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.AISLE_PAIR_16X10X13;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.mojang.authlib.GameProfile;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.DoorDirectory;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.PackageDoors;
import dev.wareworks.content.station.PackageHandover;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.TerminalCraftOutcome;
import dev.wareworks.content.station.TerminalCraftResult;
import dev.wareworks.content.station.TerminalDoorRow;
import dev.wareworks.content.station.TerminalDoors;
import dev.wareworks.content.station.TerminalPreferences;
import dev.wareworks.content.station.TerminalRequestOutcome;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crafting.CraftGrid;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.job.RequestQueue;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.port.BoxWait;
import dev.wareworks.core.port.DoorState;
import dev.wareworks.core.port.PackagerSignAddress;
import dev.wareworks.core.port.PortDirection;
import dev.wareworks.core.port.PortRedstone;
import dev.wareworks.core.port.PortSettings;
import dev.wareworks.core.terminal.RequestAcknowledgement;
import dev.wareworks.core.terminal.RequestConfirmation;
import dev.wareworks.core.terminal.RequestScope;
import dev.wareworks.network.TerminalConfirmPayload;
import dev.wareworks.network.TerminalCraftOrderPayload;
import dev.wareworks.network.TerminalRequestAnswers;
import dev.wareworks.network.TerminalRequestPayload;
import dev.wareworks.network.TerminalResultPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.network.connection.ConnectionType;

/**
 * GameTests of <b>ordering goods to a package door from the warehouse terminal</b> (M27, issue #18, second half,
 * ADR-065, {@code docs/warehouse-system.md} §3.4.6): a click on a stock cell names a requesting warehouse port with a
 * plain Create Packager at its back, the crane carries the goods into the port, and the player's own redstone has the
 * Packager box them with the sign's address.
 * <p>
 * What is proved here is the server's half of the feature, against a real Create Packager:
 * <ul>
 * <li>{@link #doorOrderFromTheTerminal}: the click through the real payload codec, the echo of the door, the crane into the port and never into the terminal, the credit at the port's drop, and a fresh box
 * that carries the sign's address and no order data of any kind;</li>
 * <li>{@link #storedPackageLeavesADoorAsItself}: a stored Create package with order data is forwarded by the Packager as
 * itself, apart from the address — Wareworks adds nothing;</li>
 * <li>{@link #doorOrderWithoutAnAddressLeavesUnaddressed}: a door without a sign may be ordered to, and its box leaves
 * with no address at all;</li>
 * <li>{@link #aDoorAtTheTerminalsOwnPositionIsHere}: a click that names the terminal itself delivers here;</li>
 * <li>the refusals, each <b>answered and never remembered</b>: a position that is no door
 * ({@link #doorOrderRefusedForWhatIsNotADoor}), an accepting port ({@link #doorOrderRefusedForAnAcceptingPort}), a
 * Packager with a Stock Link ({@link #doorOrderRefusedWhenTheDoorIgnoresRedstone}), another warehouse's port
 * ({@link #doorOrderRefusedForAnotherWarehousesPort}) and one item type more than a door waits for, which the
 * controller refuses ({@link #doorWaitsForAtMostFourItemTypes});</li>
 * <li>{@link #confirmedDoorRequestGoesToTheEchoedDoor}: a question names the door it was measured for, and a Yes goes
 * only where the request it carries says;</li>
 * <li>a door turned or broken while the crane carries its goods ({@link #doorTurnedMidOrderSendsTheCarryBack},
 * {@link #doorBrokenMidOrderSendsTheCarryBack}), and a save in the middle of a carry
 * ({@link #doorOrderSurvivesASave});</li>
 * <li>{@link #twoPlayersOrderToTwoDoors}: the server holds no chosen door, so two players at one terminal order to two
 * doors and here without touching the terminal's saved state, and both screens are told the same rows;</li>
 * <li>{@link #doorRowsAreBuiltOnlyWhileAScreenIsOpen}: the rows are pulled, bounded and shared, never scanned for;</li>
 * <li>{@link #doorRowsNameTheirState}: a full port and a missing sign are read from real blocks and named in the rows a
 * screen is told, both doors stay selectable, and a port that is emptied is ready again;</li>
 * <li>a box nothing takes away at the Packager is reported in the rows and on the port's goggles, never early
 * ({@link #doorRowSaysNothingTakesTheBox}), and a box taken away clears it, while a door whose taker works is never
 * reported ({@link #doorRowClearsOnceTheBoxIsTaken});</li>
 * <li>{@link #fetchNeverGoesToADoor}: the crafting panel's Fetch always comes to the terminal, even while a door click
 * is open.</li>
 * </ul>
 * <b>Conservation.</b> Every test that moves an item asserts {@link ItemCensus} on <b>every tick</b>; the census counts a
 * package as its contents (M26), so iron that is boxed up is still iron. The terminal's own buffer is asserted empty on
 * every tick of a door order, which is the whole difference between a door and here.
 * <p>
 * <b>Players.</b> A click from a mock player goes through {@link WarehouseTerminalMenu#submitRequest} with the menu
 * set as the player's open one, as the payload handler calls it. Pushes need a server player, so the two tests about
 * them use {@link FakePlayerFactory} players whose menus are pushed every tick, as {@code ServerPlayer#tick} would; their
 * connection sends nothing, so a test reads what the screen was told from {@link WarehouseTerminalMenu#lastDoorRows}.
 * Never {@code makeMockServerPlayerInLevel}, which joins a player to the server.
 * <p>
 * Layout: the {@link AisleFixture} aisle on {@code aisle_16x10x7}; doors on the right rack plane with their Packager one
 * block behind, where a storage location's inventory would stand, a sign east of it and the redstone block further
 * out; the terminal and the chest on the left plane. The second warehouse of
 * {@link #doorOrderRefusedForAnotherWarehousesPort} is the second aisle of {@code aisle_pair_16x10x13}.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class PackageDoorGameTests {
    private static final int AISLE_Z = 3;
    private static final int SECOND_AISLE_Z = 9;
    private static final int RAILS = 5;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 1200;
    private static final int LONG_TIMEOUT_TICKS = 2400;
    /** Long enough for several dispatch intervals and a Packager cycle: proof that nothing happens any more. */
    private static final int SETTLE_TICKS = 60;
    /** How long the build counter is watched per stage. */
    private static final int WATCH_TICKS = 200;
    /** Pushes every 10 ticks over 200 ticks, one build per 20: the first build and one per maximum age after it. */
    private static final int MAX_BUILDS_PER_WATCH = WATCH_TICKS / DoorDirectory.MAX_AGE_TICKS + 1;
    /** Enough ticks for every open menu to have pushed at least once. */
    private static final int PUSH_TICKS = WarehouseTerminalMenu.REFRESH_INTERVAL_TICKS + 2;
    /** No box rests in the tray of the door's Packager: none is held, or the tray still moves. */
    private static final long NOT_RESTING = Long.MIN_VALUE;

    private static final RackPosition STORAGE_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition TERMINAL_RACK = new RackPosition(3, 0, Side.LEFT);
    private static final RackPosition KEEPER_RACK = new RackPosition(4, 0, Side.LEFT);
    private static final RackPosition SECOND_TERMINAL_RACK = new RackPosition(4, 0, Side.LEFT);
    private static final RackPosition DOOR_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition SECOND_DOOR_RACK = new RackPosition(3, 0, Side.RIGHT);
    /** A third door, and a plain port beside the doors, for the rows of {@link #doorRowsNameTheirState}. */
    private static final RackPosition THIRD_DOOR_RACK = new RackPosition(4, 0, Side.RIGHT);
    private static final RackPosition PLAIN_PORT_RACK = new RackPosition(2, 0, Side.RIGHT);
    /** The door of the second warehouse in {@link #doorOrderRefusedForAnotherWarehousesPort}. */
    private static final RackPosition FOREIGN_DOOR_RACK = new RackPosition(0, 0, Side.LEFT);
    private static final RackPosition FOREIGN_STORAGE_RACK = new RackPosition(1, 0, Side.RIGHT);

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey PLANK = ItemKey.of(Items.OAK_PLANKS);
    /** Item types beside the ones above, so a door can be asked for one type more than it waits for. */
    private static final List<ItemKey> MORE_TYPES = List.of(ItemKey.of(Items.COPPER_INGOT), ItemKey.of(Items.COAL),
            ItemKey.of(Items.REDSTONE), ItemKey.of(Items.LAPIS_LAZULI));
    /** An item the warehouse never holds. */
    private static final ItemKey EMERALD = ItemKey.of(Items.EMERALD);
    private static final ItemKey COBBLESTONE = ItemKey.of(Items.COBBLESTONE);

    private static final String ADDRESS = "Base North";
    private static final String SECOND_ADDRESS = "Base South";
    /** A sign line as long as a sign packet allows (ServerboundSignUpdatePacket), which a row cuts. */
    private static final String LONG_ADDRESS = "Outpost " + "x".repeat(376);
    /** Where the stored package of {@link #storedPackageLeavesADoorAsItself} was addressed before it was stored. */
    private static final String STORED_ADDRESS = "Outpost";

    private static final int IRON_IN_STOCK = 100;
    /** More than one trip of one stack, so the credit at the first drop can be seen before the second. */
    private static final int ORDERED_IRON = 80;
    private static final int STACK = 64;
    private static final int CARRIED = 32;
    private static final int SMALL_ORDER = 8;
    private static final int GOLD_IN_STOCK = 32;
    private static final int DIAMONDS_IN_STOCK = 16;
    private static final int DIAMONDS_HERE = 4;
    private static final int IRON_RESERVE = 10;
    private static final int IRON_FOR_RESERVE_TEST = 32;
    /** Eight into the reserve of {@value #IRON_RESERVE}: the click that asks. */
    private static final int ASKED_IRON = 30;
    /** What is left after it, all of it reserved. */
    private static final int REST_OF_THE_RESERVE = IRON_FOR_RESERVE_TEST - ASKED_IRON;
    private static final int PLANKS_IN_STOCK = 32;
    /** One stack of each item type a door is asked for in {@link #doorWaitsForAtMostFourItemTypes}. */
    private static final int OF_EACH_TYPE = 16;
    private static final int PLANKS_TO_THE_DOOR = 4;
    private static final int PLANKS_PER_STICKS = 2;
    /** The stored package of {@link #storedPackageLeavesADoorAsItself}: what it holds and its order data. */
    private static final int BOXED_IRON = 12;
    private static final int BOXED_GOLD = 5;
    private static final int STORED_ORDER_ID = 42;
    private static final int STORED_LINK_INDEX = 1;
    private static final int STORED_FRAGMENT_INDEX = 3;

    /** Two planks one above the other: sticks. */
    private static final CraftGrid<ItemKey> STICK_GRID = CraftGrid.of(PLANK, null, null, PLANK);
    private static final String FAKE_PLAYER_NAME = "wareworks_door";

    private PackageDoorGameTests() {
    }

    // --- ordering to a door -----------------------------------------------------------------------------------------

    /**
     * <b>The whole feature, end to end.</b> A player at the terminal clicks iron with a door chosen: the click goes
     * through the real payload codec, carrying the door's position and nothing else, and the answer echoes that door.
     * The crane carries the iron into the <b>port</b> — never into the terminal, whose buffer is asserted empty on every
     * tick — in two trips, and the request is credited at the first drop into the port. Then the player's redstone fires
     * the Packager, and the box carries the sign's address and <b>no order data at all</b>: Wareworks wrote none and
     * called nothing of Create's that could.
     * <p>
     * A door click is a request like any other in everything else: it counts once towards the player's "most used"
     * order, and an accepted one leaves both the terminal's and the door's remembered refusal untouched.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void doorOrderFromTheTerminal(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK, IRON.toStack(STACK), IRON.toStack(IRON_IN_STOCK - STACK));
        aisle.terminal(TERMINAL_RACK);
        placeDoor(helper, aisle, DOOR_RACK, ADDRESS);
        BlockPos door = aisle.absoluteRackPos(DOOR_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IRON_IN_STOCK);
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, conserved, "while iron is ordered to a door");
            helper.assertFalse(aisle.terminalAt(TERMINAL_RACK).hasBufferedItems(),
                    "nothing ordered to a door ever enters the terminal");
        });

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, 2, IRON, IRON_IN_STOCK))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);

                    TerminalRequestPayload sent = new TerminalRequestPayload(menu.containerId, IRON, ORDERED_IRON,
                            Optional.of(door), RequestAcknowledgement.NONE);
                    TerminalRequestPayload received = roundTrip(helper, sent, TerminalRequestPayload.STREAM_CODEC);
                    helper.assertValueEqual(received, sent, "the click crossed the wire whole");
                    TerminalRequestOutcome outcome = submit(helper, player, received);
                    CustomPacketPayload answer = TerminalRequestAnswers.answer(received, outcome);
                    if (!(answer instanceof TerminalResultPayload result)) {
                        helper.fail("a click with nothing to ask about is answered with its result: " + answer);
                        return;
                    }
                    helper.assertTrue(result.isAccepted(), "the door click is accepted: " + result);
                    helper.assertValueEqual(result.amount(), ORDERED_IRON, "in full");
                    helper.assertValueEqual(result.door(), Optional.of(door), "and the answer names the door");

                    WarehouseControllerBlockEntity controller = aisle.controller();
                    List<RetrievalRequest<ItemKey, BlockPos>> toTheDoor = controller.requestsFor(door);
                    helper.assertValueEqual(toTheDoor.size(), 1, "one request to the door");
                    helper.assertValueEqual(toTheDoor.getFirst().destination(), door, "whose destination is the door");
                    helper.assertTrue(controller.requestsFor(terminal.getBlockPos()).isEmpty(),
                            "and none to the terminal");
                    helper.assertValueEqual(countOf(player, IRON), 1L, "the click counts once, like any other");
                    helper.assertTrue(terminal.lastRejection().isEmpty(), "the terminal remembers nothing");
                    helper.assertTrue(aisle.outputAt(DOOR_RACK).lastRejection().isEmpty(), "nor does the door");
                })
                .thenWaitUntil(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    helper.assertValueEqual(controller.deliveredFor(door), (long) STACK,
                            "the first trip is credited at the drop into the port");
                    helper.assertValueEqual(controller.requestedFor(door), (long) (ORDERED_IRON - STACK),
                            "and the rest is still on its way");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(DOOR_RACK, IRON), (long) ORDERED_IRON,
                            "everything ordered is in the door");
                    helper.assertTrue(aisle.controller().requestsFor(door).isEmpty(), "and the request is done");
                })
                .thenExecute(() -> helper.setBlock(powerPos(aisle, DOOR_RACK), Blocks.REDSTONE_BLOCK))
                .thenWaitUntil(() -> helper.assertFalse(packagerAt(helper, packagerPos(aisle, DOOR_RACK)).heldBox
                        .isEmpty(), "the player's redstone made the Packager box the order"))
                .thenExecute(() -> {
                    ItemStack box = packagerAt(helper, packagerPos(aisle, DOOR_RACK)).heldBox;
                    helper.assertTrue(PackageItem.isPackage(box), "a Create package");
                    helper.assertValueEqual(PackageItem.getAddress(box), ADDRESS, "addressed with the door's sign");
                    helper.assertValueEqual(PackageHandover.addressAt(helper.getLevel(),
                            helper.absolutePos(packagerPos(aisle, DOOR_RACK))), ADDRESS,
                            "which is the address the door reads");
                    helper.assertFalse(box.has(AllDataComponents.PACKAGE_ORDER_DATA), "a fresh box has no order data");
                    helper.assertFalse(box.has(AllDataComponents.PACKAGE_ORDER_CONTEXT),
                            "and no order context either");
                    helper.assertValueEqual(contentsOf(box), ItemCensus.of(IRON, ORDERED_IRON),
                            "and the iron ordered is inside it");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), (long) (IRON_IN_STOCK - ORDERED_IRON),
                            "exactly that left the rack");
                })
                .thenExecuteAfter(SETTLE_TICKS, aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * A stored Create package retrieved to a door leaves <b>as itself</b>: the Packager forwards a box it finds in its
     * inventory unchanged apart from the address ({@code extractedPackageItem.copy()}, then the address is cleared and
     * the sign's written). So a box that came from a Create network order keeps its own order data, which is right —
     * and it is Create's data, not ours: the box that leaves equals the box that was stored in every component but the
     * address.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void storedPackageLeavesADoorAsItself(GameTestHelper helper) {
        ItemStack stored = packageOf(IRON.toStack(BOXED_IRON), GOLD.toStack(BOXED_GOLD));
        PackageItem.addAddress(stored, STORED_ADDRESS);
        PackageItem.setOrder(stored, STORED_ORDER_ID, STORED_LINK_INDEX, true, STORED_FRAGMENT_INDEX, false, null);
        ItemKey storedKey = ItemKey.of(stored);
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK, stored.copy());
        aisle.terminal(TERMINAL_RACK);
        placeDoor(helper, aisle, DOOR_RACK, ADDRESS);
        BlockPos door = aisle.absoluteRackPos(DOOR_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, BOXED_IRON, GOLD, BOXED_GOLD);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a stored package is ordered out"));

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, 2, storedKey, 1))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, aisle.terminalAt(TERMINAL_RACK));
                    RequestResult result = resolved(helper, player, menu, storedKey, 1, RequestAcknowledgement.NONE,
                            Optional.of(door));
                    helper.assertTrue(result.isAccepted(), "the box is ordered to the door: " + result);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(DOOR_RACK, storedKey), 1L,
                        "the crane put the stored box into the door"))
                .thenExecute(() -> helper.setBlock(powerPos(aisle, DOOR_RACK), Blocks.REDSTONE_BLOCK))
                .thenWaitUntil(() -> helper.assertFalse(packagerAt(helper, packagerPos(aisle, DOOR_RACK)).heldBox
                        .isEmpty(), "the Packager took the box"))
                .thenExecute(() -> {
                    ItemStack left = packagerAt(helper, packagerPos(aisle, DOOR_RACK)).heldBox.copy();
                    helper.assertValueEqual(PackageItem.getAddress(left), ADDRESS, "the door's sign readdressed it");
                    helper.assertTrue(PackageItem.hasOrderData(left), "its own order data went with it");
                    helper.assertValueEqual(PackageItem.getOrderId(left), STORED_ORDER_ID, "unchanged");
                    ItemStack withoutAddress = left.copy();
                    withoutAddress.remove(AllDataComponents.PACKAGE_ADDRESS);
                    ItemStack storedWithoutAddress = stored.copy();
                    storedWithoutAddress.remove(AllDataComponents.PACKAGE_ADDRESS);
                    helper.assertTrue(ItemStack.matches(withoutAddress, storedWithoutAddress),
                            "the box that left is the box that was stored, apart from the address: " + left);
                })
                .thenExecuteAfter(SETTLE_TICKS, aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * A door <b>without a sign</b> may be ordered to — belt-only builds and an unnamed Package Port take unaddressed
     * boxes legitimately — and its box leaves with no address at all: Wareworks writes none, and the Packager has none
     * to write. No order data either.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void doorOrderWithoutAnAddressLeavesUnaddressed(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK, IRON.toStack(IRON_IN_STOCK));
        aisle.terminal(TERMINAL_RACK);
        placeDoor(helper, aisle, DOOR_RACK, null);
        BlockPos door = aisle.absoluteRackPos(DOOR_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IRON_IN_STOCK);
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, conserved, "while iron is ordered to a door without a sign");
            helper.assertFalse(aisle.terminalAt(TERMINAL_RACK).hasBufferedItems(),
                    "nothing ordered to a door ever enters the terminal");
        });

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, 2, IRON, IRON_IN_STOCK))
                .thenExecute(() -> {
                    helper.assertValueEqual(PackageDoors.look(helper.getLevel(), aisle.controller(), door).state(),
                            Optional.of(DoorState.NO_ADDRESS), "a door without a sign says so");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    Player player = playerAt(helper, aisle);
                    RequestResult result = resolved(helper, player, openMenu(player, aisle.terminalAt(TERMINAL_RACK)),
                            IRON, SMALL_ORDER, RequestAcknowledgement.NONE, Optional.of(door));
                    helper.assertTrue(result.isAccepted(), "and may be ordered to: " + result);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(DOOR_RACK, IRON), (long) SMALL_ORDER,
                        "the crane put the iron into the door"))
                .thenExecute(() -> helper.setBlock(powerPos(aisle, DOOR_RACK), Blocks.REDSTONE_BLOCK))
                .thenWaitUntil(() -> helper.assertFalse(packagerAt(helper, packagerPos(aisle, DOOR_RACK)).heldBox
                        .isEmpty(), "the player's redstone made the Packager box the order"))
                .thenExecute(() -> {
                    ItemStack box = packagerAt(helper, packagerPos(aisle, DOOR_RACK)).heldBox;
                    helper.assertTrue(PackageItem.isPackage(box), "a Create package");
                    helper.assertValueEqual(PackageItem.getAddress(box), PackagerSignAddress.NONE,
                            "that carries no address");
                    helper.assertValueEqual(PackageHandover.addressAt(helper.getLevel(),
                            helper.absolutePos(packagerPos(aisle, DOOR_RACK))), PackagerSignAddress.NONE,
                            "because the door has none to give it");
                    helper.assertFalse(box.has(AllDataComponents.PACKAGE_ORDER_DATA), "and no order data");
                    helper.assertValueEqual(contentsOf(box), ItemCensus.of(IRON, SMALL_ORDER),
                            "with the iron ordered inside it");
                })
                .thenExecuteAfter(SETTLE_TICKS, aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * A click whose door is the <b>terminal's own position</b> is a click to here (ADR-065 D6): its outcome and its
     * answer name no door, and the crane delivers into the terminal like for any other click to here.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void aDoorAtTheTerminalsOwnPositionIsHere(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK, IRON.toStack(IRON_IN_STOCK));
        aisle.terminal(TERMINAL_RACK);
        placeDoor(helper, aisle, DOOR_RACK, ADDRESS);
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IRON_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a click names the terminal itself"));

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, 2, IRON, IRON_IN_STOCK))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    Player player = playerAt(helper, aisle);
                    TerminalRequestPayload click = new TerminalRequestPayload(openMenu(player, terminal).containerId,
                            IRON, SMALL_ORDER, Optional.of(terminal.getBlockPos()), RequestAcknowledgement.NONE);
                    TerminalRequestOutcome outcome = submit(helper, player, click);
                    helper.assertValueEqual(outcome.door(), Optional.empty(), "the terminal's own position is here");
                    if (!(TerminalRequestAnswers.answer(click, outcome) instanceof TerminalResultPayload answer)) {
                        helper.fail("the click is answered with a result");
                        return;
                    }
                    helper.assertTrue(answer.isAccepted(), "the click is accepted: " + answer);
                    helper.assertValueEqual(answer.door(), Optional.empty(), "and its answer names no door");
                    assertOnlyRequest(helper, aisle.controller(), terminal.getBlockPos(), IRON, SMALL_ORDER,
                            "a click to here");
                    helper.assertTrue(aisle.controller().requestsFor(aisle.absoluteRackPos(DOOR_RACK)).isEmpty(),
                            "and none to the door");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, IRON),
                        (long) SMALL_ORDER, "the iron arrived in the terminal"))
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(DOOR_RACK, IRON), 0L, "and nothing in the door");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    // --- refusals, answered and never remembered ---------------------------------------------------------------------

    /**
     * <b>A door waits for at most {@code maxOpenRequestsPerOutput} item types</b>, a cap it shares with its own
     * redstone: one type more is refused by the controller with {@code OUTPUT_FULL}, which the screen shows as the
     * door's own sentence — and like every door refusal it is <b>answered and never remembered</b>, neither by the
     * terminal nor by the door. An accepted door click clears nothing the terminal remembers either: a refusal of a click to here stays
     * on the terminal's goggles through every door click, and only an accepted click to here clears it. A click for a
     * type the door already waits for merges and takes no slot (ADR-020).
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void doorWaitsForAtMostFourItemTypes(GameTestHelper helper) {
        int types = Math.max(RequestQueue.MIN_OPEN_REQUESTS, WareworksConfig.maxOpenRequestsPerOutput());
        List<ItemKey> stocked = new ArrayList<>(List.of(IRON, GOLD, DIAMOND, PLANK));
        stocked.addAll(MORE_TYPES);
        if (stocked.size() < types + 1) {
            helper.fail("the test has " + stocked.size() + " item types, a door here waits for " + types);
            return;
        }
        List<ItemKey> used = List.copyOf(stocked.subList(0, types + 1));
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_RACK, used.stream().map(key -> key.toStack(OF_EACH_TYPE)).toArray(ItemStack[]::new));
        aisle.terminal(TERMINAL_RACK);
        placeDoor(helper, aisle, DOOR_RACK, ADDRESS);
        BlockPos door = aisle.absoluteRackPos(DOOR_RACK);
        Map<ItemKey, Long> conserved = new HashMap<>();
        for (ItemKey key : used)
            conserved.put(key, (long) OF_EACH_TYPE);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a door is asked for many types"));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 0, 2);
                    for (ItemKey key : used)
                        helper.assertValueEqual(aisle.controller().countOf(key), (long) OF_EACH_TYPE,
                                "the stock of " + key + " is in");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    Player player = playerAt(helper, aisle);
                    // A refusal of a click to here, which the terminal remembers as always.
                    RequestResult notHere = resolved(helper, player, openMenu(player, terminal), EMERALD, 1,
                            RequestAcknowledgement.NONE, Optional.empty());
                    helper.assertValueEqual(notHere.rejection(), Optional.of(RequestRejection.NOT_IN_STOCK),
                            "the warehouse holds no emeralds");
                    helper.assertValueEqual(terminal.lastRejection(), Optional.of(RequestRejection.NOT_IN_STOCK),
                            "and the terminal remembers the refusal");

                    for (int type = 0; type < types; type++) {
                        RequestResult accepted = resolved(helper, player, openMenu(player, terminal), used.get(type),
                                SMALL_ORDER, RequestAcknowledgement.NONE, Optional.of(door));
                        helper.assertTrue(accepted.isAccepted(), "item type " + (type + 1) + " for the door: "
                                + accepted);
                        helper.assertValueEqual(terminal.lastRejection(), Optional.of(RequestRejection.NOT_IN_STOCK),
                                "an accepted door click clears nothing the terminal remembers");
                        helper.assertTrue(aisle.outputAt(DOOR_RACK).lastRejection().isEmpty(),
                                "and the door remembers nothing");
                    }
                    helper.assertValueEqual(controller.requestsFor(door).size(), types,
                            "the door waits for " + types + " item types");

                    RequestResult merged = resolved(helper, player, openMenu(player, terminal), used.getFirst(),
                            SMALL_ORDER, RequestAcknowledgement.NONE, Optional.of(door));
                    helper.assertTrue(merged.isAccepted(), "a type the door already waits for merges: " + merged);
                    helper.assertValueEqual(controller.requestsFor(door).size(), types, "and takes no slot");

                    // One type more: the controller's refusal, answered with the door and remembered nowhere.
                    assertRefused(helper, aisle, player, used.get(types), door, RequestRejection.OUTPUT_FULL,
                            "one item type more than the door waits for");
                    helper.assertValueEqual(terminal.lastRejection(), Optional.of(RequestRejection.NOT_IN_STOCK),
                            "the terminal still remembers only the click to here");

                    // For contrast: an accepted click to here clears what the terminal remembered.
                    RequestResult toHere = resolved(helper, player, openMenu(player, terminal), used.get(types), 1,
                            RequestAcknowledgement.NONE, Optional.empty());
                    helper.assertTrue(toHere.isAccepted(), "the same type to here is accepted: " + toHere);
                    helper.assertTrue(terminal.lastRejection().isEmpty(),
                            "and an accepted click to here clears the remembered refusal");
                })
                .thenSucceed();
    }

    /**
     * Everything that is no door is refused as one, <b>before</b> the stock is looked at, and nothing about it is
     * remembered: a port without a Packager, a port with a Repackager, a collecting port with a Packager, a terminal
     * with a Packager behind it, the storage interface, and two positions outside the warehouse, one of them far away
     * in a chunk that is not loaded — refused as no rack position of this warehouse, and the look loads nothing. A real
     * door asked for an item the warehouse does not hold says so, and is not remembered either; a click that names the
     * terminal's own position is a click to here, answered with no door and remembered like one; and the plain click
     * to here is remembered, which is the contrast that proves the rule. Nothing is queued anywhere, and the answer
     * echoes the door the click named.
     * <p>
     * A rack position of this warehouse whose chunk is not loaded cannot be built here, because a GameTest area is
     * force-loaded: the loaded check in {@code WarehouseControllerBlockEntity#isRequestDestination}, which stops such a
     * position before a block entity lookup could load its chunk, is not reached by this test.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void doorOrderRefusedForWhatIsNotADoor(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_RACK, IRON.toStack(IRON_IN_STOCK));
        aisle.terminal(TERMINAL_RACK);
        aisle.output(DOOR_RACK);
        aisle.terminal(SECOND_TERMINAL_RACK);
        placePackager(helper, packagerPos(aisle, SECOND_TERMINAL_RACK), aisle.sideDirection(SECOND_TERMINAL_RACK));
        placeDoor(helper, aisle, SECOND_DOOR_RACK, SECOND_ADDRESS);
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IRON_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while door clicks are refused"));

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, 4, IRON, IRON_IN_STOCK))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    Player player = playerAt(helper, aisle);
                    BlockPos plain = aisle.absoluteRackPos(DOOR_RACK);

                    assertRefused(helper, aisle, player, IRON, plain, RequestRejection.NOT_A_DOOR,
                            "a port without a Packager");
                    helper.setBlock(packagerPos(aisle, DOOR_RACK), AllBlocks.REPACKAGER.getDefaultState()
                            .setValue(PackagerBlock.FACING, aisle.sideDirection(DOOR_RACK)));
                    assertRefused(helper, aisle, player, IRON, plain, RequestRejection.NOT_A_DOOR,
                            "a port with a Repackager");
                    placePackager(helper, packagerPos(aisle, DOOR_RACK), aisle.sideDirection(DOOR_RACK));
                    helper.assertTrue(PackageDoors.refusal(helper.getLevel(), aisle.controller(), plain).isEmpty(),
                            "with a plain Packager the port is a door");
                    helper.assertTrue(aisle.outputAt(DOOR_RACK).setPortRank(PortSettings.COLLECT_RANK),
                            "the port collects now");
                    assertRefused(helper, aisle, player, IRON, plain, RequestRejection.NOT_A_DOOR,
                            "a collecting port with a Packager");
                    assertRefused(helper, aisle, player, IRON, aisle.absoluteRackPos(SECOND_TERMINAL_RACK),
                            RequestRejection.NOT_A_DOOR, "a terminal with a Packager behind it");
                    assertRefused(helper, aisle, player, IRON, aisle.absoluteRackPos(STORAGE_RACK),
                            RequestRejection.NOT_A_DOOR, "the storage interface");
                    assertRefused(helper, aisle, player, IRON, helper.absolutePos(aisle.controllerPos().above()),
                            RequestRejection.NOT_A_DOOR, "a position outside the warehouse");
                    BlockPos farAway = terminal.getBlockPos().offset(1_000_000, 0, 1_000_000);
                    helper.assertFalse(helper.getLevel().isLoaded(farAway), "the far position is not loaded");
                    assertRefused(helper, aisle, player, IRON, farAway, RequestRejection.NOT_A_DOOR,
                            "a far position outside the warehouse, not loaded");
                    helper.assertFalse(helper.getLevel().isLoaded(farAway), "and asking about it loaded nothing");

                    // The door is checked before the stock: something that is no door, asked for what the warehouse
                    // does not hold either, is told that it is no door.
                    assertRefused(helper, aisle, player, DIAMOND, aisle.absoluteRackPos(SECOND_TERMINAL_RACK),
                            RequestRejection.NOT_A_DOOR, "no door, asked for diamonds");
                    // A real door, asked for what the warehouse does not hold: the stock's answer, not remembered.
                    BlockPos door = aisle.absoluteRackPos(SECOND_DOOR_RACK);
                    assertRefused(helper, aisle, player, DIAMOND, door, RequestRejection.NOT_IN_STOCK,
                            "a door asked for diamonds");
                    // A door equal to the terminal's own position is here (ADR-065 D6): not refused as no door, as the
                    // other terminal was above, but measured, answered and remembered as a click to here, with no door
                    // in the echo.
                    helper.assertTrue(terminal.lastRejection().isEmpty(), "no door click was remembered so far");
                    TerminalRequestPayload toItself = new TerminalRequestPayload(openMenu(player, terminal).containerId,
                            DIAMOND, 1, Optional.of(terminal.getBlockPos()), RequestAcknowledgement.NONE);
                    TerminalRequestOutcome itself = submit(helper, player, toItself);
                    helper.assertValueEqual(itself.door(), Optional.empty(),
                            "a door at the terminal's own position is here");
                    if (!(TerminalRequestAnswers.answer(toItself, itself) instanceof TerminalResultPayload answer)) {
                        helper.fail("a click to the terminal's own position is answered with a result");
                        return;
                    }
                    helper.assertValueEqual(answer.door(), Optional.empty(), "and its answer names no door");
                    helper.assertValueEqual(answer.rejection(), Optional.of(RequestRejection.NOT_IN_STOCK),
                            "it is told the stock's answer, not that it is no door");
                    helper.assertValueEqual(terminal.lastRejection(), Optional.of(RequestRejection.NOT_IN_STOCK),
                            "and it is remembered like a click to here");
                    // The plain click to here is remembered as well, which is what makes the rule above a rule.
                    RequestResult here = resolved(helper, player, openMenu(player, terminal), DIAMOND, 1,
                            RequestAcknowledgement.NONE, Optional.empty());
                    helper.assertValueEqual(here.rejection(), Optional.of(RequestRejection.NOT_IN_STOCK),
                            "the click to here is refused the same way");
                    helper.assertValueEqual(terminal.lastRejection(), Optional.of(RequestRejection.NOT_IN_STOCK),
                            "and a click to here is remembered");
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 0, "nothing was queued at all");
                })
                .thenSucceed();
    }

    /**
     * An accepting port is no door, even with a Packager at its back: it must never receive items somebody asked for.
     * The click is refused, nothing is queued, neither the terminal nor the port remembers anything — and the very same
     * port turned back to requesting is a door again, so the refusal is about the direction and nothing else.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void doorOrderRefusedForAnAcceptingPort(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_RACK, IRON.toStack(IRON_IN_STOCK));
        aisle.terminal(TERMINAL_RACK);
        placeDoor(helper, aisle, DOOR_RACK, ADDRESS);
        BlockPos door = aisle.absoluteRackPos(DOOR_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IRON_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while an accepting port is clicked"));

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, 2, IRON, IRON_IN_STOCK))
                .thenExecute(() -> {
                    WarehouseOutputBlockEntity port = aisle.outputAt(DOOR_RACK);
                    helper.assertTrue(port.setPortRank(PortSettings.MIN_RANK), "the port accepts now");
                    helper.assertValueEqual(port.portDirection(), PortDirection.ACCEPT, "an accepting port");
                    Player player = playerAt(helper, aisle);
                    assertRefused(helper, aisle, player, IRON, door, RequestRejection.NOT_A_DOOR,
                            "an accepting port with a Packager");
                    helper.assertTrue(aisle.controller().doorDirectory().terminalRows(helper.getLevel().getGameTime())
                            .rows().isEmpty(), "and it is no door row either");

                    helper.assertTrue(port.setPortRank(PortSettings.REQUEST_RANK), "the port requests again");
                    RequestResult accepted = resolved(helper, player, openMenu(player, aisle.terminalAt(TERMINAL_RACK)),
                            IRON, SMALL_ORDER, RequestAcknowledgement.NONE, Optional.of(door));
                    helper.assertTrue(accepted.isAccepted(), "and is a door again: " + accepted);
                    helper.assertValueEqual(aisle.controller().requestsFor(door).size(), 1,
                            "the request goes to it");
                })
                .thenSucceed();
    }

    /**
     * A Packager with a Stock Link ignores redstone, so a door with one would never send what the crane hands it. A
     * click to it is refused with its own reason and remembered nowhere, and its row says why; take the link away and
     * the same door is orderable again. The link is a real block, because Create heals a hand-set {@code LINKED} away in
     * its next lazy tick.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void doorOrderRefusedWhenTheDoorIgnoresRedstone(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_RACK, IRON.toStack(IRON_IN_STOCK));
        aisle.terminal(TERMINAL_RACK);
        placeDoor(helper, aisle, DOOR_RACK, ADDRESS);
        BlockPos door = aisle.absoluteRackPos(DOOR_RACK);
        BlockPos packager = packagerPos(aisle, DOOR_RACK);
        BlockPos link = packager.above();
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IRON_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a linked door is clicked"));

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, 2, IRON, IRON_IN_STOCK))
                .thenExecute(() -> helper.setBlock(link, AllBlocks.STOCK_LINK.getDefaultState()
                        .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR)))
                .thenWaitUntil(() -> helper.assertBlockProperty(packager, PackagerBlock.LINKED, true))
                .thenExecute(() -> {
                    Player player = playerAt(helper, aisle);
                    assertRefused(helper, aisle, player, IRON, door, RequestRejection.DOOR_IGNORES_REDSTONE,
                            "a door whose Packager carries a Stock Link");
                    TerminalDoors rows = aisle.controller().doorDirectory()
                            .terminalRows(helper.getLevel().getGameTime());
                    helper.assertValueEqual(rows.rows().size(), 1, "the door is listed");
                    TerminalDoorRow row = rows.rows().getFirst();
                    helper.assertValueEqual(row.state(), DoorState.IGNORES_REDSTONE, "and says that it ignores redstone");
                    helper.assertFalse(row.state().selectable(), "so nobody can choose it");
                    helper.assertValueEqual(row.label(), ADDRESS, "still under its sign's name");
                    helper.setBlock(link, Blocks.AIR);
                })
                .thenWaitUntil(() -> helper.assertBlockProperty(packager, PackagerBlock.LINKED, false))
                .thenExecute(() -> {
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, aisle.terminalAt(TERMINAL_RACK));
                    RequestResult accepted = resolved(helper, player, menu, IRON, SMALL_ORDER,
                            RequestAcknowledgement.NONE, Optional.of(door));
                    helper.assertTrue(accepted.isAccepted(), "without the link the door is orderable: " + accepted);
                })
                .thenWaitUntil(() -> {
                    TerminalDoors rows = aisle.controller().doorDirectory()
                            .terminalRows(helper.getLevel().getGameTime());
                    helper.assertValueEqual(rows.rows().size(), 1, "the door is listed");
                    helper.assertValueEqual(rows.rows().getFirst().state(), DoorState.READY,
                            "and ready once the directory looks again");
                    helper.assertValueEqual(rows.rows().getFirst().waiting(), (long) SMALL_ORDER,
                            "with the order it waits for");
                })
                .thenSucceed();
    }

    /**
     * A requesting port with a Packager in <b>another</b> warehouse is a door of that warehouse and no door of this one:
     * a terminal only ever delivers inside its own warehouse, and nothing is queued in either.
     */
    @GameTest(template = AISLE_PAIR_16X10X13, timeoutTicks = TIMEOUT_TICKS)
    public static void doorOrderRefusedForAnotherWarehousesPort(GameTestHelper helper) {
        AisleFixture own = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        own.storage(STORAGE_RACK, IRON.toStack(IRON_IN_STOCK));
        own.terminal(TERMINAL_RACK);
        AisleFixture other = new AisleFixture(helper, SECOND_AISLE_Z, RAILS).build(false);
        other.storage(FOREIGN_STORAGE_RACK, IRON.toStack(IRON_IN_STOCK));
        placeDoor(helper, other, FOREIGN_DOOR_RACK, SECOND_ADDRESS);
        BlockPos foreignDoor = other.absoluteRackPos(FOREIGN_DOOR_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, 2 * IRON_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while another warehouse's door is clicked"));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertStocked(helper, own, 1, IRON, IRON_IN_STOCK);
                    assertStocked(helper, other, 1, IRON, IRON_IN_STOCK);
                })
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    helper.assertTrue(PackageDoors.look(level, other.controller(), foreignDoor).isDoor(),
                            "the port is a door of its own warehouse");
                    helper.assertFalse(PackageDoors.look(level, own.controller(), foreignDoor).isDoor(),
                            "and none of this one");
                    Player player = playerAt(helper, own);
                    assertRefused(helper, own, player, IRON, foreignDoor, RequestRejection.NOT_A_DOOR,
                            "another warehouse's door");
                    helper.assertValueEqual(other.controller().openRequestCount(), 0,
                            "and the other warehouse was asked for nothing");
                    helper.assertTrue(other.outputAt(FOREIGN_DOOR_RACK).lastRejection().isEmpty(),
                            "nor does its door remember anything");
                })
                .thenSucceed();
    }

    // --- what is confirmed is where it goes -------------------------------------------------------------------------

    /**
     * A click to a door that reaches into a reserve is asked about, and the question <b>names the door it was measured
     * for</b>, through the real codec. The Yes that carries that door is made at that door. A click to the other door is
     * measured again — it asks a question of its own, naming its own door — and a Yes that names the other door goes
     * to the other door and nowhere else: the server holds no door of its own to fall back on.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void confirmedDoorRequestGoesToTheEchoedDoor(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_RACK, IRON.toStack(IRON_FOR_RESERVE_TEST));
        aisle.terminal(TERMINAL_RACK);
        aisle.stockKeeper(KEEPER_RACK);
        placeDoor(helper, aisle, DOOR_RACK, ADDRESS);
        placeDoor(helper, aisle, SECOND_DOOR_RACK, SECOND_ADDRESS);
        BlockPos door = aisle.absoluteRackPos(DOOR_RACK);
        BlockPos otherDoor = aisle.absoluteRackPos(SECOND_DOOR_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IRON_FOR_RESERVE_TEST);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a door click is asked about"));

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, 3, IRON, IRON_FOR_RESERVE_TEST))
                .thenExecute(() -> {
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, IRON, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_RESERVE, null, (long) IRON_RESERVE);
                })
                .thenExecute(() -> {
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, aisle.terminalAt(TERMINAL_RACK));

                    TerminalRequestPayload click = new TerminalRequestPayload(menu.containerId, IRON, ASKED_IRON,
                            Optional.of(door), RequestAcknowledgement.NONE);
                    TerminalRequestOutcome asked = submit(helper, player, click);
                    helper.assertTrue(asked.isAsking(), "a click into the reserve is asked about");
                    CustomPacketPayload answer = TerminalRequestAnswers.answer(click, asked);
                    if (!(answer instanceof TerminalConfirmPayload question)) {
                        helper.fail("the answer to a click that asks is the question: " + answer);
                        return;
                    }
                    TerminalConfirmPayload received = roundTrip(helper, question, TerminalConfirmPayload.STREAM_CODEC);
                    helper.assertValueEqual(received.door(), Optional.of(door),
                            "the question names the door it was measured for");
                    helper.assertValueEqual(received.scope(), RequestScope.CLICK, "and belongs to the click");
                    helper.assertValueEqual(controller.openRequestCount(), 0, "asking requested nothing");

                    // The screen's Yes: the same click with the consent and the door the question named.
                    RequestConfirmation<ItemKey> confirmation = received.question();
                    RequestResult yes = resolved(helper, player, menu, IRON, ASKED_IRON,
                            confirmation.acknowledgement(), received.door());
                    helper.assertTrue(yes.isAccepted(), "the Yes is made: " + yes);
                    helper.assertValueEqual(controller.requestedFor(door), (long) ASKED_IRON, "at the echoed door");
                    helper.assertTrue(controller.requestsFor(otherDoor).isEmpty(), "and nowhere else");

                    // The other door is a click of its own: measured again, asked about, naming its own door.
                    TerminalRequestPayload elsewhere = new TerminalRequestPayload(menu.containerId, IRON,
                            REST_OF_THE_RESERVE, Optional.of(otherDoor), RequestAcknowledgement.NONE);
                    TerminalRequestOutcome askedAgain = submit(helper, player, elsewhere);
                    helper.assertTrue(askedAgain.isAsking(), "the click to the other door is measured again");
                    if (TerminalRequestAnswers.answer(elsewhere, askedAgain) instanceof TerminalConfirmPayload again)
                        helper.assertValueEqual(again.door(), Optional.of(otherDoor), "and names that door");
                    else
                        helper.fail("the click to the other door is asked about");

                    // A Yes that names the other door goes there, whatever was confirmed before.
                    RequestResult moved = resolved(helper, player, menu, IRON, REST_OF_THE_RESERVE,
                            confirmation.acknowledgement(), Optional.of(otherDoor));
                    helper.assertTrue(moved.isAccepted(), "the Yes naming the other door is made: " + moved);
                    helper.assertValueEqual(controller.requestedFor(otherDoor), (long) REST_OF_THE_RESERVE,
                            "at the door it named");
                    helper.assertValueEqual(controller.requestedFor(door), (long) ASKED_IRON,
                            "and the first door's request is untouched");
                })
                .thenSucceed();
    }

    // --- a door that changes while the crane carries ----------------------------------------------------------------

    /**
     * A door turned out of Request while the crane carries its goods loses its request, and the goods go back into
     * storage — never into the port, which no longer asks for anything.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void doorTurnedMidOrderSendsTheCarryBack(GameTestHelper helper) {
        midOrder(helper, aisle -> helper.assertTrue(aisle.outputAt(DOOR_RACK).setPortRank(PortSettings.MIN_RANK),
                "the door is turned to accepting"), "turned");
    }

    /**
     * A door broken while the crane carries its goods loses its request, and the goods go back into storage. The
     * Packager is left standing; it only ever packs what a port hands it.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void doorBrokenMidOrderSendsTheCarryBack(GameTestHelper helper) {
        midOrder(helper, aisle -> aisle.breakBlock(aisle.rackPos(DOOR_RACK)), "broken");
    }

    /**
     * A save in the middle of a carry to a door: the dock and the controller are replaced by copies loaded from their
     * own saves, as a chunk load builds them, and the order still goes to the door. A door click adds nothing to any
     * save — the destination is the request's own saved position, and the reservations are rebuilt from the crane's job.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void doorOrderSurvivesASave(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK, IRON.toStack(IRON_IN_STOCK));
        aisle.terminal(TERMINAL_RACK);
        placeDoor(helper, aisle, DOOR_RACK, ADDRESS);
        BlockPos door = aisle.absoluteRackPos(DOOR_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IRON_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a door order is saved and loaded"));

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, 2, IRON, IRON_IN_STOCK))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    orderToTheDoor(helper, aisle, CARRIED);
                })
                .thenWaitUntil(() -> assertCarryingToTheDoor(helper, aisle))
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    HolderLookup.Provider registries = level.registryAccess();
                    StackerCraneBlockEntity dock = aisle.dock();
                    WarehouseControllerBlockEntity controller = aisle.controller();
                    CompoundTag dockTag = dock.saveWithFullMetadata(registries);
                    CompoundTag controllerTag = controller.saveWithFullMetadata(registries);
                    level.setBlockEntity(loadCopy(helper, dock, dockTag, StackerCraneBlockEntity.class));
                    level.setBlockEntity(loadCopy(helper, controller, controllerTag,
                            WarehouseControllerBlockEntity.class));
                    helper.assertTrue(aisle.controller() != controller, "the controller was replaced");
                    List<RetrievalRequest<ItemKey, BlockPos>> loaded = aisle.controller().requestsFor(door);
                    helper.assertValueEqual(loaded.size(), 1, "the loaded controller still waits for the door");
                    helper.assertValueEqual(loaded.getFirst().destination(), door, "at the same position");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(DOOR_RACK, IRON), (long) CARRIED,
                            "the order arrived in the door after the load");
                    helper.assertTrue(aisle.controller().requestsFor(door).isEmpty(), "and is done");
                })
                .thenExecute(() -> helper.assertFalse(aisle.terminalAt(TERMINAL_RACK).hasBufferedItems(),
                        "nothing went to the terminal"))
                .thenExecuteAfter(SETTLE_TICKS, aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    // --- several players, and what a screen is told ----------------------------------------------------------------

    /**
     * Two players at one terminal order to two doors and a third order to here: each request keeps the destination its
     * click named, the terminal's saved state is byte-for-byte what it was — the server holds no chosen door anywhere —
     * and both screens are told the same rows. Then the crane serves all three.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void twoPlayersOrderToTwoDoors(GameTestHelper helper) {
        // No motor until the rows are compared, so no delivery changes a count between the two menus' pushes.
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_RACK, IRON.toStack(IRON_IN_STOCK), GOLD.toStack(GOLD_IN_STOCK),
                DIAMOND.toStack(DIAMONDS_IN_STOCK));
        aisle.terminal(TERMINAL_RACK);
        placeDoor(helper, aisle, DOOR_RACK, ADDRESS);
        placeDoor(helper, aisle, SECOND_DOOR_RACK, SECOND_ADDRESS);
        BlockPos door = aisle.absoluteRackPos(DOOR_RACK);
        BlockPos otherDoor = aisle.absoluteRackPos(SECOND_DOOR_RACK);
        FakePlayer first = fakePlayerAt(helper, aisle);
        FakePlayer second = fakePlayerAt(helper, aisle);
        List<WarehouseTerminalMenu> menus = new ArrayList<>();
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IRON_IN_STOCK, GOLD, GOLD_IN_STOCK, DIAMOND,
                DIAMONDS_IN_STOCK);
        helper.onEachTick(() -> {
            // What ServerPlayer#tick does for an open screen; a fake player's own tick does nothing.
            menus.forEach(WarehouseTerminalMenu::broadcastChanges);
            ItemCensus.assertEquals(helper, conserved, "while two players order to two doors", first, second);
        });

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, 3, DIAMOND, DIAMONDS_IN_STOCK))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    menus.add(openMenu(first, terminal));
                    menus.add(openMenu(second, terminal));
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    CompoundTag before = terminal.saveWithFullMetadata(registries);

                    helper.assertTrue(resolved(helper, first, menus.get(0), IRON, SMALL_ORDER,
                            RequestAcknowledgement.NONE, Optional.of(door)).isAccepted(), "the first player's door");
                    helper.assertTrue(resolved(helper, second, menus.get(1), GOLD, SMALL_ORDER,
                            RequestAcknowledgement.NONE, Optional.of(otherDoor)).isAccepted(),
                            "the second player's door");
                    helper.assertTrue(resolved(helper, first, menus.get(0), DIAMOND, DIAMONDS_HERE,
                            RequestAcknowledgement.NONE, Optional.empty()).isAccepted(), "and here");

                    WarehouseControllerBlockEntity controller = aisle.controller();
                    assertOnlyRequest(helper, controller, door, IRON, SMALL_ORDER, "the first door");
                    assertOnlyRequest(helper, controller, otherDoor, GOLD, SMALL_ORDER, "the second door");
                    assertOnlyRequest(helper, controller, terminal.getBlockPos(), DIAMOND, DIAMONDS_HERE, "here");
                    helper.assertValueEqual(terminal.saveWithFullMetadata(registries), before,
                            "no click left anything in the terminal's save: the server holds no chosen door");
                })
                .thenIdle(PUSH_TICKS)
                .thenExecute(() -> {
                    TerminalDoors seen = menus.get(0).lastDoorRows();
                    helper.assertTrue(seen != null, "the first screen was told about the doors");
                    helper.assertValueEqual(menus.get(1).lastDoorRows(), seen, "and both screens were told the same");
                    helper.assertValueEqual(seen.rows().size(), 2, "two doors, in rack order");
                    helper.assertValueEqual(seen.rows().get(0).pos(), door, "the first door first");
                    helper.assertValueEqual(seen.rows().get(0).label(), ADDRESS, "under its sign");
                    helper.assertValueEqual(seen.rows().get(0).waiting(), (long) SMALL_ORDER, "waiting for its order");
                    helper.assertValueEqual(seen.rows().get(1).pos(), otherDoor, "then the second");
                    helper.assertValueEqual(seen.rows().get(1).label(), SECOND_ADDRESS, "under its own sign");
                    helper.assertFalse(seen.rows().get(0).rackAddress().isEmpty(), "with its rack address");
                    aisle.placeMotor();
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(DOOR_RACK, IRON), (long) SMALL_ORDER, "iron at door 1");
                    helper.assertValueEqual(aisle.stationCount(SECOND_DOOR_RACK, GOLD), (long) SMALL_ORDER,
                            "gold at door 2");
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, DIAMOND), (long) DIAMONDS_HERE,
                            "and diamonds here");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(DOOR_RACK, GOLD), 0L, "nothing crossed over");
                    helper.assertValueEqual(aisle.stationCount(SECOND_DOOR_RACK, IRON), 0L, "either way");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    /**
     * <b>The rows are pulled, not scanned.</b> With no terminal screen open nothing is built for 200 ticks. One open
     * screen pushing every ten ticks builds once and then once per maximum age; a second screen shares those builds
     * and adds none. A port change marks the rows dirty, which costs exactly one more build at the next look — and never
     * more than one in a tick, however often it is looked at.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void doorRowsAreBuiltOnlyWhileAScreenIsOpen(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_RACK, IRON.toStack(IRON_IN_STOCK));
        aisle.terminal(TERMINAL_RACK);
        placeDoor(helper, aisle, DOOR_RACK, ADDRESS);
        FakePlayer first = fakePlayerAt(helper, aisle);
        FakePlayer second = fakePlayerAt(helper, aisle);
        List<WarehouseTerminalMenu> menus = new ArrayList<>();
        int[] builds = new int[1];
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IRON_IN_STOCK);
        helper.onEachTick(() -> {
            menus.forEach(WarehouseTerminalMenu::broadcastChanges);
            ItemCensus.assertEquals(helper, conserved, "while the door rows are watched", first, second);
        });

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, 2, IRON, IRON_IN_STOCK))
                .thenIdle(WATCH_TICKS)
                .thenExecute(() -> {
                    helper.assertValueEqual(directory(aisle).builds(), 0, "nobody looked, so nothing was built");
                    menus.add(openMenu(first, aisle.terminalAt(TERMINAL_RACK)));
                })
                .thenIdle(WATCH_TICKS)
                .thenExecute(() -> {
                    int built = directory(aisle).builds();
                    helper.assertTrue(built >= 1 && built <= MAX_BUILDS_PER_WATCH,
                            "one open screen builds at most " + MAX_BUILDS_PER_WATCH + " times in " + WATCH_TICKS
                                    + " ticks, not " + built);
                    TerminalDoors told = menus.getFirst().lastDoorRows();
                    helper.assertTrue(told != null && told.rows().size() == 1, "the screen was told the door: " + told);
                    helper.assertValueEqual(told.rows().getFirst().state(), DoorState.READY, "a ready door");
                    builds[0] = built;
                    menus.add(openMenu(second, aisle.terminalAt(TERMINAL_RACK)));
                })
                .thenIdle(WATCH_TICKS)
                .thenExecute(() -> {
                    int built = directory(aisle).builds() - builds[0];
                    helper.assertTrue(built >= 1 && built <= MAX_BUILDS_PER_WATCH,
                            "two open screens share the builds: at most " + MAX_BUILDS_PER_WATCH + ", not " + built);
                    helper.assertValueEqual(menus.get(1).lastDoorRows(), menus.get(0).lastDoorRows(),
                            "and are told the same rows");
                    // Both screens close: from here on only this test looks.
                    menus.clear();
                    // A change of a setting that keeps the door a door, so the next look builds for certain.
                    helper.assertTrue(aisle.outputAt(DOOR_RACK).setRedstoneMode(PortRedstone.WHILE_POWERED),
                            "the door's redstone behaviour changes");
                })
                .thenIdle(1)
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    int before = directory(aisle).builds();
                    TerminalDoors rows = terminal.doorRows();
                    builds[0] = directory(aisle).builds();
                    helper.assertValueEqual(builds[0], before + 1, "a changed door is built again at the next look");
                    helper.assertValueEqual(rows.rows().size(), 1, "and is still a door");
                    terminal.doorRows();
                    terminal.doorRows();
                    helper.assertValueEqual(directory(aisle).builds(), builds[0], "more looks within a tick build nothing");
                })
                .thenIdle(1)
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    terminal.doorRows();
                    helper.assertValueEqual(directory(aisle).builds(), builds[0],
                            "a fresh build is reused while nothing changed");
                    helper.assertTrue(aisle.outputAt(DOOR_RACK).setPortRank(PortSettings.MIN_RANK),
                            "the door is turned to accepting");
                })
                .thenIdle(1)
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    TerminalDoors after = terminal.doorRows();
                    terminal.doorRows();
                    helper.assertValueEqual(directory(aisle).builds(), builds[0] + 1,
                            "the port change cost exactly one more build");
                    helper.assertTrue(after.isEmpty(), "and the turned port is no door any more: " + after);
                })
                .thenSucceed();
    }

    /**
     * <b>The rows name what a player has to look at, read from real blocks.</b> Three doors: one whose port has no
     * empty slot left, because nothing empties it; one without a sign; and a ready one whose sign is as long as a sign
     * packet allows. A plain port without a Packager and the terminal are no doors. The rows a real open screen is told
     * list the three doors in rack order, the full one as {@link DoorState#PORT_FULL}, the unsigned one as
     * {@link DoorState#NO_ADDRESS} with an empty label, and the long sign cut to
     * {@value PackagerSignAddress#DISPLAY_LENGTH} characters and flagged. Both warnings may still be chosen. Once one
     * stack is taken out of the full port, the next build reads it as ready.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void doorRowsNameTheirState(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_RACK, IRON.toStack(IRON_IN_STOCK));
        aisle.terminal(TERMINAL_RACK);
        placeDoor(helper, aisle, DOOR_RACK, ADDRESS);
        placeDoor(helper, aisle, SECOND_DOOR_RACK, null);
        placeDoor(helper, aisle, THIRD_DOOR_RACK, LONG_ADDRESS);
        aisle.output(PLAIN_PORT_RACK);
        BlockPos full = aisle.absoluteRackPos(DOOR_RACK);
        BlockPos unsigned = aisle.absoluteRackPos(SECOND_DOOR_RACK);
        BlockPos ready = aisle.absoluteRackPos(THIRD_DOOR_RACK);
        FakePlayer player = fakePlayerAt(helper, aisle);
        List<WarehouseTerminalMenu> menus = new ArrayList<>();
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IRON_IN_STOCK);
        helper.onEachTick(() -> {
            // What ServerPlayer#tick does for an open screen; a fake player's own tick does nothing.
            menus.forEach(WarehouseTerminalMenu::broadcastChanges);
            ItemCensus.assertEquals(helper, conserved, "while the door rows are read", player);
        });

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, 5, IRON, IRON_IN_STOCK))
                .thenExecute(() -> {
                    WarehouseOutputBlockEntity port = aisle.outputAt(DOOR_RACK);
                    for (int slot = 0; slot < port.bufferSlots(); slot++) {
                        helper.assertTrue(port.insert(COBBLESTONE.toStack(STACK), false).isEmpty(),
                                "the port took a stack of cobblestone");
                        ItemCensus.change(conserved, COBBLESTONE, STACK);
                    }
                    helper.assertFalse(port.hasEmptySlot(), "and has no empty slot left");
                    menus.add(openMenu(player, aisle.terminalAt(TERMINAL_RACK)));
                })
                // Long enough for a build after the fill, whatever the cache held, and a push of it.
                .thenIdle(DoorDirectory.MAX_AGE_TICKS + PUSH_TICKS)
                .thenExecute(() -> {
                    TerminalDoors told = menus.getFirst().lastDoorRows();
                    helper.assertTrue(told != null, "the screen was told about the doors");
                    helper.assertValueEqual(told, aisle.controller().doorDirectory()
                            .terminalRows(helper.getLevel().getGameTime()), "what the directory holds");
                    helper.assertValueEqual(told.rows().stream().map(TerminalDoorRow::pos).toList(),
                            List.of(full, unsigned, ready),
                            "the three doors in rack order, and neither the plain port nor the terminal");
                    helper.assertFalse(told.unchecked(), "with every output checked");
                    TerminalDoorRow fullRow = told.rows().get(0);
                    helper.assertValueEqual(fullRow.state(), DoorState.PORT_FULL, "a port nothing empties is full");
                    helper.assertValueEqual(fullRow.label(), ADDRESS, "under its sign");
                    TerminalDoorRow unsignedRow = told.rows().get(1);
                    helper.assertValueEqual(unsignedRow.state(), DoorState.NO_ADDRESS, "a door without a sign says so");
                    helper.assertValueEqual(unsignedRow.label(), PackagerSignAddress.NONE, "and has no label");
                    helper.assertFalse(unsignedRow.rackAddress().isEmpty(), "but its rack address");
                    TerminalDoorRow readyRow = told.rows().get(2);
                    helper.assertValueEqual(readyRow.state(), DoorState.READY, "the third door is ready");
                    helper.assertValueEqual(readyRow.label().length(), PackagerSignAddress.DISPLAY_LENGTH,
                            "its long sign cut for the row");
                    helper.assertTrue(readyRow.labelCut(), "and flagged as cut");
                    ServerLevel level = helper.getLevel();
                    helper.assertTrue(PackageDoors.refusal(level, aisle.controller(), full).isEmpty(),
                            "a full door may still be chosen");
                    helper.assertTrue(PackageDoors.refusal(level, aisle.controller(), unsigned).isEmpty(),
                            "and so may one without a sign");
                    takeOneStack(aisle, conserved);
                })
                .thenIdle(DoorDirectory.MAX_AGE_TICKS + PUSH_TICKS)
                .thenExecute(() -> helper.assertValueEqual(menus.getFirst().lastDoorRows().rows().getFirst().state(),
                        DoorState.READY, "a port with an empty slot again is ready"))
                .thenSucceed();
    }

    // --- a box nothing takes away (ADR-065 D14) ---------------------------------------------------------------------

    /**
     * <b>A box that waits is reported.</b> The door's Packager boxes what its port holds, and nothing takes the box —
     * the funnel was forgotten. The rows a real open screen is told turn to {@link DoorState#BOX_WAITING}, and the
     * port's goggles say so as well ({@link WarehouseOutputBlockEntity#boxWaitingShown}, the decision the gold row is
     * drawn from).
     * <p>
     * <b>Never early.</b> On every tick the rows the screen was told are held against the box as this test sees it,
     * and they may say so only once the box has rested at the Packager for {@value BoxWait#REPORT_AFTER_TICKS} ticks.
     * That takes two builds at least: one whose look starts the wait, and a later one that still sees the same box.
     * Nothing but the screen's pushes looks at the door here, so the port's memory of the box is the rows' own.
     * <p>
     * The door is still a door that may be chosen: an order to it waits like one to a full port, and the crane stays
     * free.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void doorRowSaysNothingTakesTheBox(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_RACK, IRON.toStack(IRON_IN_STOCK));
        aisle.terminal(TERMINAL_RACK);
        placeDoor(helper, aisle, DOOR_RACK, ADDRESS);
        BlockPos door = aisle.absoluteRackPos(DOOR_RACK);
        FakePlayer player = fakePlayerAt(helper, aisle);
        List<WarehouseTerminalMenu> menus = new ArrayList<>();
        long[] restingSince = {NOT_RESTING};
        int[] buildsBefore = new int[1];
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IRON_IN_STOCK);
        helper.onEachTick(() -> {
            // What ServerPlayer#tick does for an open screen; a fake player's own tick does nothing.
            menus.forEach(WarehouseTerminalMenu::broadcastChanges);
            long now = helper.getLevel().getGameTime();
            restingSince[0] = restingSince(helper, aisle, restingSince[0], now);
            if (told(menus, DoorState.BOX_WAITING))
                helper.assertTrue(restingSince[0] != NOT_RESTING
                        && now - restingSince[0] >= BoxWait.REPORT_AFTER_TICKS,
                        "a box is only reported once it has rested " + BoxWait.REPORT_AFTER_TICKS + " ticks");
            ItemCensus.assertEquals(helper, conserved, "while a box waits at a door", player);
        });

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, 2, IRON, IRON_IN_STOCK))
                .thenExecute(() -> {
                    fillThePort(helper, aisle, conserved);
                    menus.add(openMenu(player, aisle.terminalAt(TERMINAL_RACK)));
                })
                .thenIdle(PUSH_TICKS)
                .thenExecute(() -> {
                    helper.assertValueEqual(stateTold(menus), Optional.of(DoorState.READY),
                            "a door with goods in its port and an idle Packager is ready");
                    buildsBefore[0] = directory(aisle).builds();
                    helper.setBlock(powerPos(aisle, DOOR_RACK), Blocks.REDSTONE_BLOCK);
                })
                .thenWaitUntil(() -> helper.assertTrue(restingSince[0] != NOT_RESTING,
                        "the Packager boxed the port, and the box rests in its tray"))
                .thenWaitUntil(() -> helper.assertValueEqual(stateTold(menus), Optional.of(DoorState.BOX_WAITING),
                        "the screen is told that nothing takes the box away"))
                .thenExecute(() -> {
                    helper.assertTrue(directory(aisle).builds() >= buildsBefore[0] + 2,
                            "two builds at least: one starts the wait, a later one sees the same box");
                    TerminalDoorRow row = menus.getFirst().lastDoorRows().rows().getFirst();
                    helper.assertValueEqual(row.pos(), door, "the row is the door's");
                    helper.assertValueEqual(row.label(), ADDRESS, "under its sign");
                    WarehouseOutputBlockEntity port = aisle.outputAt(DOOR_RACK);
                    helper.assertTrue(port.boxWaitingShown(helper.getLevel().getGameTime()),
                            "and the port's goggles say it too");
                    helper.assertValueEqual(aisle.stationCount(DOOR_RACK, IRON), 0L, "the port is empty");
                    helper.assertValueEqual(contentsOf(packagerAt(helper, packagerPos(aisle, DOOR_RACK)).heldBox),
                            ItemCensus.of(IRON, SMALL_ORDER), "everything it held waits in the box");
                    helper.assertTrue(PackageDoors.refusal(helper.getLevel(), aisle.controller(), door).isEmpty(),
                            "and the door may still be ordered to: the order waits, the crane stays free");
                })
                .thenSucceed();
    }

    /**
     * <b>A box taken away clears it</b>, and a door whose taker works is never reported. The goggles' decision is asked
     * on every tick, as a goggle tooltip asks it in every frame, and must equal the truth as this test sees it on every
     * tick: a box at rest in the Packager for {@value BoxWait#REPORT_AFTER_TICKS} ticks or more.
     * <p>
     * First nothing takes the box, and both the goggles and the rows a real open screen is told say so. Then a hopper
     * is put under the Packager: it takes the box, the goggles stop saying so in the same tick and the rows turn back
     * to ready at the next build. Last, the port is filled once more: the Packager — still powered — boxes it, the
     * hopper takes the new box as soon as the tray is at rest, and neither the goggles nor the rows ever report it.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void doorRowClearsOnceTheBoxIsTaken(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_RACK, IRON.toStack(IRON_IN_STOCK));
        aisle.terminal(TERMINAL_RACK);
        placeDoor(helper, aisle, DOOR_RACK, ADDRESS);
        BlockPos hopper = packagerPos(aisle, DOOR_RACK).below();
        FakePlayer player = fakePlayerAt(helper, aisle);
        List<WarehouseTerminalMenu> menus = new ArrayList<>();
        long[] restingSince = {NOT_RESTING};
        boolean[] cleared = {false};
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IRON_IN_STOCK);
        helper.onEachTick(() -> {
            menus.forEach(WarehouseTerminalMenu::broadcastChanges);
            long now = helper.getLevel().getGameTime();
            restingSince[0] = restingSince(helper, aisle, restingSince[0], now);
            boolean truth = restingSince[0] != NOT_RESTING && now - restingSince[0] >= BoxWait.REPORT_AFTER_TICKS;
            helper.assertValueEqual(aisle.outputAt(DOOR_RACK).boxWaitingShown(now), truth,
                    "the goggles say a box waits exactly when it has rested " + BoxWait.REPORT_AFTER_TICKS + " ticks");
            if (cleared[0])
                helper.assertFalse(told(menus, DoorState.BOX_WAITING), "a box the hopper takes is never reported");
            ItemCensus.assertEquals(helper, conserved, "while a door's boxes are taken away", player);
        });

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, 2, IRON, IRON_IN_STOCK))
                .thenExecute(() -> {
                    fillThePort(helper, aisle, conserved);
                    menus.add(openMenu(player, aisle.terminalAt(TERMINAL_RACK)));
                    helper.setBlock(powerPos(aisle, DOOR_RACK), Blocks.REDSTONE_BLOCK);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(aisle.outputAt(DOOR_RACK).boxWaitingShown(helper.getLevel().getGameTime()),
                            "the goggles say that nothing takes the box away");
                    helper.assertValueEqual(stateTold(menus), Optional.of(DoorState.BOX_WAITING),
                            "and so does the screen");
                })
                .thenExecute(() -> helper.setBlock(hopper, AisleFixture.hopperState(AisleFixture.AISLE)))
                .thenWaitUntil(() -> {
                    helper.assertTrue(packagerAt(helper, packagerPos(aisle, DOOR_RACK)).heldBox.isEmpty(),
                            "the hopper took the box");
                    helper.assertValueEqual(packagesIn(aisle, hopper), 1, "into its slots");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(stateTold(menus), Optional.of(DoorState.READY),
                        "the screen is told the door is ready again"))
                .thenExecute(() -> {
                    cleared[0] = true;
                    fillThePort(helper, aisle, conserved);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(packagesIn(aisle, hopper), 2,
                            "the still powered Packager boxed the port again, and the hopper took that box too");
                    helper.assertValueEqual(aisle.stationCount(DOOR_RACK, IRON), 0L, "the port is empty");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(stateTold(menus), Optional.of(DoorState.READY), "the door is ready");
                    helper.assertValueEqual(ironInPackages(aisle, hopper), (long) (2 * SMALL_ORDER),
                            "and the two boxes hold what the port held");
                })
                .thenSucceed();
    }

    // --- what never goes to a door ----------------------------------------------------------------------------------

    /**
     * <b>The Fetch always comes here.</b> With a click to a door still open, the crafting panel's Fetch starts a craft
     * order whose request goes to the terminal: the two requests keep their own destinations and do not merge, and once
     * the crane runs the sticks' planks arrive in the terminal and the clicked planks in the door.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void fetchNeverGoesToADoor(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_RACK, PLANK.toStack(PLANKS_IN_STOCK));
        aisle.terminal(TERMINAL_RACK);
        placeDoor(helper, aisle, DOOR_RACK, ADDRESS);
        BlockPos door = aisle.absoluteRackPos(DOOR_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(PLANK, PLANKS_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a Fetch runs beside a door order"));

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, 2, PLANK, PLANKS_IN_STOCK))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    Player player = playerAt(helper, aisle);
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    helper.assertTrue(resolved(helper, player, menu, PLANK, PLANKS_TO_THE_DOOR,
                            RequestAcknowledgement.NONE, Optional.of(door)).isAccepted(), "the click to the door");
                    TerminalCraftOutcome fetched = menu.craftOrderAction(player, TerminalCraftOrderPayload.fetch(
                            menu.containerId, STICK_GRID, Optional.empty(), 1, 0L, false));
                    helper.assertValueEqual(fetched.result(), TerminalCraftResult.STARTED, "the Fetch starts");
                })
                // The craft order asks for its first portion on the controller's next pass over its list terminals.
                .thenWaitUntil(() -> assertOnlyRequest(helper, aisle.controller(),
                        aisle.absoluteRackPos(TERMINAL_RACK), PLANK, PLANKS_PER_STICKS, "the Fetch's planks"))
                .thenExecute(() -> {
                    assertOnlyRequest(helper, aisle.controller(), door, PLANK, PLANKS_TO_THE_DOOR,
                            "the clicked planks, apart from them");
                    aisle.placeMotor();
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK), (long) PLANKS_PER_STICKS,
                            "the Fetch's planks arrived here");
                    helper.assertValueEqual(aisle.stationCount(DOOR_RACK, PLANK), (long) PLANKS_TO_THE_DOOR,
                            "and the clicked planks in the door");
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, PLANK), (long) PLANKS_PER_STICKS,
                            "and no more of them");
                    aisle.assertIdleAndEmpty();
                })
                .thenSucceed();
    }

    // --- shared sequences -------------------------------------------------------------------------------------------

    /** What a door does to an order in flight: {@code change} while the crane carries, then the carry goes back. */
    private interface DoorChange {
        void apply(AisleFixture aisle);
    }

    private static void midOrder(GameTestHelper helper, DoorChange change, String what) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK, IRON.toStack(IRON_IN_STOCK));
        aisle.terminal(TERMINAL_RACK);
        placeDoor(helper, aisle, DOOR_RACK, ADDRESS);
        BlockPos door = aisle.absoluteRackPos(DOOR_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(IRON, IRON_IN_STOCK);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a door is " + what + " mid-order"));

        helper.startSequence()
                .thenWaitUntil(() -> assertStocked(helper, aisle, 2, IRON, IRON_IN_STOCK))
                .thenExecute(() -> {
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                    orderToTheDoor(helper, aisle, CARRIED);
                })
                .thenWaitUntil(() -> assertCarryingToTheDoor(helper, aisle))
                .thenExecute(() -> change.apply(aisle))
                .thenWaitUntil(() -> helper.assertTrue(aisle.controller().requestsFor(door).isEmpty(),
                        "the door that was " + what + " lost its request"))
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, IRON), (long) IRON_IN_STOCK,
                            "the iron went back into storage");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecuteAfter(SETTLE_TICKS, () -> {
                    helper.assertValueEqual(aisle.stationCount(DOOR_RACK, IRON), 0L, "nothing reached the door");
                    helper.assertFalse(aisle.terminalAt(TERMINAL_RACK).hasBufferedItems(), "nor the terminal");
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 0, "and no request came back");
                })
                .thenSucceed();
    }

    /** A player's click of {@code amount} iron to the door at {@link #DOOR_RACK}; fails unless granted in full. */
    private static void orderToTheDoor(GameTestHelper helper, AisleFixture aisle, int amount) {
        Player player = playerAt(helper, aisle);
        WarehouseTerminalMenu menu = openMenu(player, aisle.terminalAt(TERMINAL_RACK));
        RequestResult result = resolved(helper, player, menu, IRON, amount, RequestAcknowledgement.NONE,
                Optional.of(aisle.absoluteRackPos(DOOR_RACK)));
        helper.assertTrue(result.isAccepted(), "the door click is accepted: " + result);
        helper.assertValueEqual(result.granted(), amount, "in full");
    }

    /** The crane travels to the door with the whole order in its head. */
    private static void assertCarryingToTheDoor(GameTestHelper helper, AisleFixture aisle) {
        aisle.assertCarrying(CranePhase.TRAVEL_TO_TARGET, IRON, CARRIED);
        Optional<TransportJob<ItemKey, RackPosition>> job = aisle.dock().currentJob();
        helper.assertTrue(job.isPresent(), "the crane has a job");
        helper.assertValueEqual(job.get().target(), DOOR_RACK, "to the door");
    }

    // --- a box at the Packager --------------------------------------------------------------------------------------

    /**
     * The game time from which a box has rested in the tray of the door's Packager, as the test sees it in every tick:
     * {@code before} while it still rests, {@code now} when it has just come to rest, and {@link #NOT_RESTING} while
     * the Packager holds no box or its tray moves.
     */
    private static long restingSince(GameTestHelper helper, AisleFixture aisle, long before, long now) {
        PackagerBlockEntity packager = packagerAt(helper, packagerPos(aisle, DOOR_RACK));
        if (packager.heldBox.isEmpty() || packager.animationTicks != 0)
            return NOT_RESTING;
        return before == NOT_RESTING ? now : before;
    }

    /** The state of the one door the first open screen was last told about; empty before a push or without a door. */
    private static Optional<DoorState> stateTold(List<WarehouseTerminalMenu> menus) {
        if (menus.isEmpty())
            return Optional.empty();
        TerminalDoors told = menus.getFirst().lastDoorRows();
        if (told == null || told.rows().size() != 1)
            return Optional.empty();
        return Optional.of(told.rows().getFirst().state());
    }

    /** Whether the first open screen was last told that its one door stands in {@code state}. */
    private static boolean told(List<WarehouseTerminalMenu> menus, DoorState state) {
        return stateTold(menus).filter(state::equals).isPresent();
    }

    /** Puts {@value #SMALL_ORDER} iron into the door's port, as a crane's drop would, and keeps the census in step. */
    private static void fillThePort(GameTestHelper helper, AisleFixture aisle, Map<ItemKey, Long> conserved) {
        helper.assertTrue(aisle.outputAt(DOOR_RACK).insert(IRON.toStack(SMALL_ORDER), false).isEmpty(),
                "the port took the iron");
        ItemCensus.change(conserved, IRON, SMALL_ORDER);
    }

    /**
     * Takes one stack out of the door's port through its extract-only capability, the side its Packager, a funnel or a
     * hopper uses, and keeps the census in step.
     */
    private static void takeOneStack(AisleFixture aisle, Map<ItemKey, Long> conserved) {
        IItemHandler pull = aisle.handlerAt(aisle.rackPos(DOOR_RACK));
        for (int slot = 0; slot < pull.getSlots(); slot++) {
            ItemStack taken = pull.extractItem(slot, STACK, false);
            if (!taken.isEmpty()) {
                ItemCensus.change(conserved, ItemKey.of(taken), -taken.getCount());
                return;
            }
        }
    }

    /** Create packages in the inventory at a test-relative position, one per box. */
    private static int packagesIn(AisleFixture aisle, BlockPos pos) {
        IItemHandler handler = aisle.handlerAt(pos);
        int boxes = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (PackageItem.isPackage(stack))
                boxes += stack.getCount();
        }
        return boxes;
    }

    /** The iron inside the Create packages in the inventory at a test-relative position. */
    private static long ironInPackages(AisleFixture aisle, BlockPos pos) {
        IItemHandler handler = aisle.handlerAt(pos);
        long iron = 0L;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (PackageItem.isPackage(stack))
                iron += contentsOf(stack).getOrDefault(IRON, 0L) * stack.getCount();
        }
        return iron;
    }

    // --- building ----------------------------------------------------------------------------------------------------

    /**
     * A package door at {@code rack}: a requesting warehouse port, a Create Packager one block behind it with its back to
     * it, and — unless {@code address} is {@code null} — a sign east of the Packager.
     */
    private static void placeDoor(GameTestHelper helper, AisleFixture aisle, RackPosition rack,
            @Nullable String address) {
        aisle.output(rack);
        placePackager(helper, packagerPos(aisle, rack), aisle.sideDirection(rack));
        if (address != null)
            writeSign(helper, signPos(aisle, rack), true, address);
    }

    /** Where the Packager of the door at {@code rack} stands: one block behind the port. */
    private static BlockPos packagerPos(AisleFixture aisle, RackPosition rack) {
        return aisle.inventoryPos(rack);
    }

    /** Where the sign that names the door at {@code rack} stands: east of its Packager. */
    private static BlockPos signPos(AisleFixture aisle, RackPosition rack) {
        return packagerPos(aisle, rack).relative(AisleFixture.AISLE);
    }

    /** The redstone block that fires the Packager of the door at {@code rack}: adjacent to it, never to the port. */
    private static BlockPos powerPos(AisleFixture aisle, RackPosition rack) {
        return packagerPos(aisle, rack).relative(aisle.sideDirection(rack));
    }

    // --- clicking ----------------------------------------------------------------------------------------------------

    /** A click through the payload entry point, as the network handler makes it; fails when it was dropped. */
    private static TerminalRequestOutcome submit(GameTestHelper helper, Player player, TerminalRequestPayload payload) {
        Optional<TerminalRequestOutcome> outcome = WarehouseTerminalMenu.submitRequest(player, payload.containerId(),
                payload.key(), payload.amount(), payload.acknowledged(), payload.door());
        if (outcome.isEmpty()) {
            helper.fail("the click was dropped instead of answered");
            throw new IllegalStateException("unreachable");
        }
        return outcome.get();
    }

    /** A click that must be resolved rather than asked about; fails the test otherwise. */
    private static RequestResult resolved(GameTestHelper helper, Player player, WarehouseTerminalMenu menu, ItemKey key,
            int amount, RequestAcknowledgement acknowledged, Optional<BlockPos> door) {
        TerminalRequestOutcome outcome = submit(helper, player,
                new TerminalRequestPayload(menu.containerId, key, amount, door, acknowledged));
        if (outcome.result().isEmpty()) {
            helper.fail("the terminal asked about a click that needed no question: " + outcome.question().get());
            throw new IllegalStateException("unreachable");
        }
        helper.assertValueEqual(outcome.door(), door, "the outcome names the door the click named");
        return outcome.result().get();
    }

    /**
     * A door click that is refused with {@code expected}: answered with that reason and the door it named, nothing
     * queued anywhere, and nothing remembered by the terminal or by the port at the door. Each click opens a menu of its
     * own for {@code player}, which is left as the player's open one.
     */
    private static void assertRefused(GameTestHelper helper, AisleFixture aisle, Player player, ItemKey key,
            BlockPos door, RequestRejection expected, String what) {
        WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
        // A screen of its own for every click: one menu answers at most MAX_REQUESTS_PER_TICK clicks in a tick.
        WarehouseTerminalMenu menu = openMenu(player, terminal);
        Optional<RequestRejection> rememberedBefore = terminal.lastRejection();
        int openBefore = aisle.controller().openRequestCount();
        TerminalRequestPayload click = new TerminalRequestPayload(menu.containerId, key, SMALL_ORDER,
                Optional.of(door), RequestAcknowledgement.NONE);
        TerminalRequestOutcome outcome = submit(helper, player, click);
        if (!(TerminalRequestAnswers.answer(click, outcome) instanceof TerminalResultPayload answer)) {
            helper.fail(what + ": a refusal is answered with a result");
            return;
        }
        helper.assertValueEqual(answer.rejection(), Optional.of(expected), what + ": the reason");
        helper.assertValueEqual(answer.door(), Optional.of(door), what + ": the answer names the door it is about");
        helper.assertValueEqual(aisle.controller().openRequestCount(), openBefore, what + ": nothing is queued");
        helper.assertValueEqual(terminal.lastRejection(), rememberedBefore,
                what + ": the terminal does not remember a door's refusal");
        if (helper.getLevel().isLoaded(door)
                && helper.getLevel().getBlockEntity(door) instanceof WarehouseOutputBlockEntity port)
            helper.assertTrue(port.lastRejection().isEmpty(), what + ": nor does the door");
    }

    /** Fails unless the only open request to {@code destination} is for {@code amount} of {@code key}. */
    private static void assertOnlyRequest(GameTestHelper helper, WarehouseControllerBlockEntity controller,
            BlockPos destination, ItemKey key, int amount, String what) {
        List<RetrievalRequest<ItemKey, BlockPos>> requests = controller.requestsFor(destination);
        helper.assertValueEqual(requests.size(), 1, what + ": one request");
        helper.assertValueEqual(requests.getFirst().key(), key, what + ": for its item");
        helper.assertValueEqual(requests.getFirst().remaining(), amount, what + ": and its amount");
        helper.assertValueEqual(requests.getFirst().destination(), destination, what + ": to where it was clicked");
    }

    private static void assertStocked(GameTestHelper helper, AisleFixture aisle, int outputs, ItemKey key,
            int amount) {
        aisle.assertReady(1, 0, outputs);
        helper.assertValueEqual(aisle.controller().countOf(key), (long) amount, "the stock is in");
    }

    private static DoorDirectory directory(AisleFixture aisle) {
        return aisle.controller().doorDirectory();
    }

    private static long countOf(Player player, ItemKey key) {
        return TerminalPreferences.existing(player).map(preferences -> preferences.usage().countFor(key)).orElse(0L);
    }

    /** The menu a player has open, as {@code openScreen} would build it on the server. */
    private static WarehouseTerminalMenu openMenu(Player player, WarehouseTerminalBlockEntity terminal) {
        WarehouseTerminalMenu menu = WarehouseTerminalMenu.create(player.containerMenu.containerId + 1,
                player.getInventory(), terminal);
        player.containerMenu = menu;
        return menu;
    }

    /** A survival player standing at the terminal, so the vanilla container reach check passes. */
    private static Player playerAt(GameTestHelper helper, AisleFixture aisle) {
        return standAt(helper, helper.makeMockPlayer(GameType.SURVIVAL), aisle.rackPos(TERMINAL_RACK));
    }

    /**
     * A fake server player standing at the terminal, with a fresh profile so that no fake player cached by an earlier
     * run is handed back.
     */
    private static FakePlayer fakePlayerAt(GameTestHelper helper, AisleFixture aisle) {
        return standAt(helper, FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), FAKE_PLAYER_NAME)), aisle.rackPos(TERMINAL_RACK));
    }

    private static <P extends Player> P standAt(GameTestHelper helper, P player, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(pos));
        player.moveTo(center.x, center.y, center.z);
        return player;
    }

    private static <T> T roundTrip(GameTestHelper helper, T payload, StreamCodec<RegistryFriendlyByteBuf, T> codec) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
        codec.encode(buffer, payload);
        T decoded = codec.decode(buffer);
        helper.assertValueEqual(buffer.readableBytes(), 0, "the codec read everything it wrote");
        return decoded;
    }
}
