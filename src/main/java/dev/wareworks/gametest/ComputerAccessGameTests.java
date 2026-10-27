package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.computer.ComputerArgumentException;
import dev.wareworks.content.computer.ComputerItemSelector;
import dev.wareworks.content.computer.TerminalComputerAccess;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.StockKeeperRules;
import dev.wareworks.content.station.TerminalStockEntry;
import dev.wareworks.content.station.WarehouseStockKeeperBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.computer.ResultPage;
import dev.wareworks.core.job.RetrievalRequest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of what a computer may do at a warehouse terminal (issue #9, ADR-074, {@code docs/computercraft.md}),
 * driven through {@link TerminalComputerAccess} — the peripheral's logic without one CC: Tweaked type, so these run in
 * every GameTest run, with or without CC. The real peripheral is covered by {@code ComputerCraftPeripheralGameTests},
 * which only runs with {@code -Pwareworks.cc}.
 * <p>
 * Every test builds the {@link AisleFixture} aisle on {@code aisle_16x10x7} (controller x = 0, dock x = 1, z = 3),
 * with a terminal at 0 right and its stock behind an interface at 1 left.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class ComputerAccessGameTests {
    /** The tests that override the server config, restored after the batch even when one of them fails. */
    static final String CONFIG_BATCH = "wareworkscomputerconfig";

    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final RackPosition TERMINAL_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition STORAGE_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition DOOR_RACK = new RackPosition(2, 0, Side.RIGHT);
    private static final RackPosition KEEPER_RACK = new RackPosition(4, 0, Side.RIGHT);
    private static final RackPosition PLAIN_OUTPUT_RACK = new RackPosition(4, 0, Side.LEFT);

    private static final int TIMEOUT_TICKS = 1200;
    private static final int DIAMONDS = 20;
    private static final int IRON = 12;
    private static final int GOLD = 7;
    private static final int REQUESTED = 10;
    private static final int RESERVE = 8;
    private static final int PLAYER_SHARE = 5;
    private static final int MERGE_CAP = 8;
    private static final int NAMED_EMERALDS = 3;
    /** Named diamonds for the queue cap; with the emeralds and the plain diamonds they fit one chest. */
    private static final int MAX_CAP_KEYS = 20;

    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey IRON_INGOT = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey GOLD_INGOT = ItemKey.of(Items.GOLD_INGOT);

    private ComputerAccessGameTests() {
    }

    /**
     * {@code getStatus}, {@code getStock} paging ({@code start}, {@code limit}, {@code nextStart}, {@code name}) against
     * the terminal's own snapshot, {@code getItem}, the argument errors, and the 10-tick stock cache: two reads in one
     * tick build one snapshot.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void computerStockPages(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK, DIAMOND.toStack(DIAMONDS), IRON_INGOT.toStack(IRON), GOLD_INGOT.toStack(GOLD));
        aisle.terminal(TERMINAL_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 0, 1);
                    helper.assertValueEqual(aisle.controller().countOf(GOLD_INGOT), (long) GOLD, "stock is in");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    TerminalComputerAccess access = new TerminalComputerAccess(terminal);

                    Map<String, Object> status = access.status();
                    helper.assertValueEqual(status.get("connected"), true, "connected");
                    helper.assertValueEqual(status.get("status"), "ready", "status word");
                    helper.assertValueEqual(status.get("maxRequest"), WareworksConfig.maxTerminalRequestAmount(),
                            "the request cap");
                    helper.assertValueEqual(status.get("pageSize"), ResultPage.MAX_LIMIT, "page size");

                    List<TerminalStockEntry> snapshot = terminal.stockSnapshot();
                    helper.assertTrue(terminal.computerStockSnapshot() == terminal.computerStockSnapshot(),
                            "two reads in one tick share one snapshot");
                    helper.assertValueEqual(terminal.computerStockSnapshot(), snapshot, "the screen's own list");

                    TerminalComputerAccess.StockPage first = access.stock(Map.of("limit", 2.0));
                    helper.assertValueEqual(first.entries(), snapshot.subList(0, 2), "first page, screen order");
                    helper.assertValueEqual(first.nextStart().orElse(-1), 3, "next start");
                    TerminalComputerAccess.StockPage second = access.stock(Map.of("start", 3.0, "limit", 2.0));
                    helper.assertValueEqual(second.entries(), snapshot.subList(2, 3), "the rest");
                    helper.assertTrue(second.nextStart().isEmpty(), "no page after the last");
                    helper.assertValueEqual(access.stock(null).entries(), snapshot, "defaults: everything");
                    TerminalComputerAccess.StockPage gold = access.stock(Map.of("name", "minecraft:gold_ingot"));
                    helper.assertValueEqual(gold.entries().size(), 1, "filtered to one item");
                    helper.assertValueEqual(gold.entries().getFirst().key(), GOLD_INGOT, "the gold");

                    Map<String, Object> table = access.entryTable(gold.entries().getFirst());
                    helper.assertValueEqual(table.get("name"), "minecraft:gold_ingot", "name");
                    helper.assertValueEqual(table.get("count"), (long) GOLD, "count");
                    helper.assertValueEqual(table.get("available"), (long) GOLD, "available");
                    helper.assertValueEqual(table.get("maxCount"), 64, "stack size");
                    helper.assertValueEqual(table.get("fingerprint"),
                            ComputerItemSelector.fingerprint(GOLD_INGOT, helper.getLevel().registryAccess()),
                            "fingerprint");
                    helper.assertValueEqual(((String) table.get("fingerprint")).length(),
                            ComputerItemSelector.FINGERPRINT_LENGTH, "eight hex digits");
                    helper.assertTrue(!table.containsValue(null), "no nil inside a table");

                    helper.assertValueEqual(access.item("minecraft:iron_ingot").map(TerminalStockEntry::total),
                            Optional.of((long) IRON), "getItem by id");
                    helper.assertValueEqual(access.item("iron_ingot").map(TerminalStockEntry::key),
                            Optional.of(IRON_INGOT), "the minecraft namespace may be left out");
                    helper.assertTrue(access.item("minecraft:emerald").isEmpty(), "not held, not producible: nil");

                    expectError(helper, () -> access.stock(Map.of("start", 0.0)), "start must be at least 1, got 0");
                    expectError(helper, () -> access.stock(Map.of("limit", 300.0)),
                            "limit must be between 1 and 256, got 300");
                    expectError(helper, () -> access.stock(Map.of("limit", 1.5)), "limit must be a whole number");
                    expectError(helper, () -> access.stock(Map.of("name", "nope:nothing")), "unknown item: nope:nothing");
                    expectError(helper, () -> access.item("Not An Id"), "unknown item: Not An Id");
                    expectError(helper, () -> access.item(42.0),
                            "item must be an id string or a table {name=..., fingerprint=...}");
                    expectError(helper, () -> access.openRequest("not-a-uuid"), "not a request id: not-a-uuid");
                })
                .thenSucceed();
    }

    /**
     * The whole loop from a computer: a request is an ordinary request to the terminal, the crane delivers it into the
     * buffer with every item accounted for on every tick, and the request is listed while open and nil afterwards.
     * Nothing is remembered about the computer at the terminal.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void computerRequestDelivers(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK, DIAMOND.toStack(DIAMONDS));
        aisle.terminal(TERMINAL_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, DIAMONDS);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while a computer's request is served"));
        String[] id = new String[1];

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    TerminalComputerAccess access = new TerminalComputerAccess(terminal);
                    List<Object> answer = access.request("minecraft:diamond", REQUESTED, null);
                    helper.assertValueEqual(answer.getFirst(), true, "accepted: " + answer);
                    Map<?, ?> request = (Map<?, ?>) answer.get(1);
                    helper.assertValueEqual(request.get("granted"), REQUESTED, "granted");
                    helper.assertValueEqual(request.get("pending"), REQUESTED, "pending");
                    helper.assertValueEqual(request.get("producing"), 0, "nothing made");
                    helper.assertValueEqual(request.get("merged"), false, "a new request");
                    id[0] = (String) request.get("id");

                    WarehouseControllerBlockEntity controller = aisle.controller();
                    List<RetrievalRequest<ItemKey, BlockPos>> open = controller.requestsFor(terminal.getBlockPos());
                    helper.assertValueEqual(open.size(), 1, "one ordinary request to the terminal");
                    helper.assertValueEqual(open.getFirst().id().toString(), id[0], "with that id");

                    List<Map<String, Object>> rows = access.requests();
                    helper.assertValueEqual(rows.size(), 1, "listed");
                    helper.assertValueEqual(rows.getFirst().get("destination"), TerminalComputerAccess.HERE, "here");
                    helper.assertValueEqual(rows.getFirst().get("requested"), REQUESTED, "requested");
                    helper.assertTrue(access.openRequest(id[0]).isPresent(), "getRequest finds it");
                    helper.assertTrue(terminal.lastRejection().isEmpty(), "nothing remembered");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, DIAMOND),
                        (long) REQUESTED, "delivered into the terminal buffer"))
                .thenExecute(() -> {
                    TerminalComputerAccess access = new TerminalComputerAccess(aisle.terminalAt(TERMINAL_RACK));
                    helper.assertTrue(access.openRequest(id[0]).isEmpty(), "a delivered request is nil");
                    helper.assertTrue(access.requests().isEmpty(), "and no longer listed");
                    helper.assertValueEqual(aisle.storedAt(STORAGE_RACK, DIAMOND), (long) (DIAMONDS - REQUESTED),
                            "the rest is still stored");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * Refusals and errors: amounts outside 1 to {@code maxTerminalRequestAmount} and an ambiguous item are errors; an
     * item not in stock and a position that is no door are refusals with their word, never remembered on the block;
     * named variants are told apart by their fingerprint; the per-station queue cap holds against a computer.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void computerRequestRefusals(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        List<ItemKey> emeralds = namedKeys(Items.EMERALD.getDefaultInstance(), NAMED_EMERALDS);
        // Named diamonds to fill this terminal's share of the queue with, one item type per open request (ADR-020).
        List<ItemKey> capKeys = namedKeys(Items.DIAMOND.getDefaultInstance(), MAX_CAP_KEYS);
        List<ItemStack> stored = new ArrayList<>();
        stored.add(DIAMOND.toStack(DIAMONDS));
        emeralds.forEach(key -> stored.add(key.toStack(1)));
        capKeys.forEach(key -> stored.add(key.toStack(1)));
        aisle.storage(STORAGE_RACK, stored.toArray(ItemStack[]::new));
        aisle.terminal(TERMINAL_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 0, 1);
                    helper.assertValueEqual(aisle.controller().countOf(capKeys.getLast()), 1L, "the variants are in");
                    if (Math.min(WareworksConfig.maxOpenRequestsPerOutput(), WareworksConfig.maxOpenRequests())
                            > MAX_CAP_KEYS + 1)
                        helper.fail("the config needs more item types than the test provides");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    TerminalComputerAccess access = new TerminalComputerAccess(terminal);
                    int max = WareworksConfig.maxTerminalRequestAmount();
                    expectError(helper, () -> access.request("minecraft:diamond", 0, null),
                            "amount must be between 1 and " + max + ", got 0");
                    expectError(helper, () -> access.request("minecraft:diamond", max + 1, null),
                            "amount must be between 1 and " + max + ", got " + (max + 1));
                    // Lua numbers arrive as doubles: a fraction is no amount, and 2^32 + 5 must not wrap to 5.
                    expectError(helper, () -> access.request("minecraft:diamond", 2.5, null),
                            "amount must be a whole number");
                    expectError(helper, () -> access.request("minecraft:diamond", 4294967301.0, null),
                            "amount must be a whole number");
                    expectError(helper, () -> access.request("minecraft:diamond", 2147483648.0, null),
                            "amount must be a whole number");
                    expectError(helper, () -> access.request("minecraft:diamond", "3", null),
                            "amount must be a number");
                    expectError(helper, () -> access.request("minecraft:emerald", 1, null),
                            "ambiguous item minecraft:emerald: " + NAMED_EMERALDS
                                    + " variants, pass {name=..., fingerprint=...}");
                    expectError(helper, () -> access.request("minecraft:diamond", 1, Map.of("x", 1.0)),
                            "door must be a table {x=..., y=..., z=...}");

                    helper.assertValueEqual(access.request("minecraft:gold_ingot", 1, null),
                            List.<Object>of(false, "not_in_stock"), "nothing of it here");
                    BlockPos noDoor = aisle.absoluteRackPos(STORAGE_RACK);
                    helper.assertValueEqual(access.request("minecraft:diamond", 1, door(noDoor)),
                            List.<Object>of(false, "not_a_door"), "an interface is no door");
                    helper.assertTrue(terminal.lastRejection().isEmpty(), "a computer's refusal is never remembered");
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 0, "nothing requested so far");

                    // The fingerprint names one variant; a plain id with only named variants is ambiguous (above).
                    ItemKey wanted = emeralds.get(1);
                    String print = ComputerItemSelector.fingerprint(wanted, helper.getLevel().registryAccess());
                    List<Object> answer = access.request(Map.of("name", "minecraft:emerald", "fingerprint", print), 1,
                            null);
                    helper.assertValueEqual(answer.getFirst(), true, "the variant is requested: " + answer);
                    helper.assertValueEqual(aisle.controller().requestsFor(terminal.getBlockPos()).getFirst().key(),
                            wanted, "exactly that variant");
                    helper.assertValueEqual(access.request(Map.of("name", "minecraft:emerald", "fingerprint",
                            "00000000"), 1, null), List.<Object>of(false, "not_in_stock"), "an unknown fingerprint");

                    // The queue caps bound a computer exactly as they bound a click: the emerald above took one slot,
                    // named diamonds take the others, and a repeat still merges.
                    int perStation = Math.min(WareworksConfig.maxOpenRequestsPerOutput(),
                            WareworksConfig.maxOpenRequests());
                    for (int i = 0; i < perStation - 1; i++) {
                        String other = ComputerItemSelector.fingerprint(capKeys.get(i),
                                helper.getLevel().registryAccess());
                        List<Object> taken = access.request(Map.of("name", "minecraft:diamond", "fingerprint", other),
                                1, null);
                        helper.assertValueEqual(taken.getFirst(), true, "slot " + i + ": " + taken);
                    }
                    helper.assertValueEqual(access.request("minecraft:diamond", 1, null),
                            List.<Object>of(false, perStation < WareworksConfig.maxOpenRequests() ? "output_full"
                                    : "queue_full"), "this terminal is busy");
                    helper.assertValueEqual(aisle.controller().requestsFor(terminal.getBlockPos()).size(), perStation,
                            "capped per station");
                })
                .thenSucceed();
    }

    /**
     * A stock rule's reserve holds items back from a computer like from a redstone request, and the stock table says
     * so ({@code available}, {@code heldBack}); a player's click still takes the reserve.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void computerReserveHeldBack(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_RACK, DIAMOND.toStack(DIAMONDS));
        aisle.terminal(TERMINAL_RACK);
        aisle.stockKeeper(KEEPER_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 0, 1);
                    helper.assertValueEqual(aisle.controller().countOf(DIAMOND), (long) DIAMONDS, "stock");
                })
                .thenExecute(() -> {
                    WarehouseStockKeeperBlockEntity keeper = aisle.stockKeeperAt(KEEPER_RACK);
                    keeper.editRule(0, StockKeeperRules.FIELD_ITEM, DIAMOND, 0L);
                    keeper.editRule(0, StockKeeperRules.FIELD_RESERVE, null, RESERVE);
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    TerminalComputerAccess access = new TerminalComputerAccess(terminal);
                    Map<String, Object> table = access.entryTable(access.item("minecraft:diamond").orElseThrow());
                    helper.assertValueEqual(table.get("available"), (long) (DIAMONDS - RESERVE),
                            "what a computer may have");
                    helper.assertValueEqual(table.get("heldBack"), (long) RESERVE, "what the reserve keeps");

                    List<Object> answer = access.request("minecraft:diamond", DIAMONDS, null);
                    helper.assertValueEqual(answer.getFirst(), true, "accepted: " + answer);
                    helper.assertValueEqual(((Map<?, ?>) answer.get(1)).get("granted"), DIAMONDS - RESERVE,
                            "clamped to the reserve");
                    helper.assertValueEqual(access.request("minecraft:diamond", 1, null),
                            List.<Object>of(false, "reserved"), "then refused with the reserve's word");

                    RequestResult click = terminal.requestFromTerminal(playerAt(helper, aisle), DIAMOND, RESERVE);
                    helper.assertTrue(click.isAccepted() && click.granted() == RESERVE,
                            "a player still takes the reserve: " + click);
                })
                .thenSucceed();
    }

    /**
     * A computer's request for an item a player already asked for here merges into that one request, and
     * {@code maxTerminalRequestAmount} bounds the merged amount ({@code request_full} once it is reached).
     */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void computerRequestMergesWithAClick(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_RACK, DIAMOND.toStack(DIAMONDS));
        aisle.terminal(TERMINAL_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.maxTerminalRequestAmount, MERGE_CAP);
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    RequestResult click = terminal.requestFromTerminal(playerAt(helper, aisle), DIAMOND, PLAYER_SHARE);
                    helper.assertTrue(click.isAccepted(), "the player's click: " + click);
                    String clickId = click.request().orElseThrow().id().toString();

                    TerminalComputerAccess access = new TerminalComputerAccess(terminal);
                    List<Object> answer = access.request("minecraft:diamond", MERGE_CAP, null);
                    helper.assertValueEqual(answer.getFirst(), true, "accepted: " + answer);
                    Map<?, ?> merged = (Map<?, ?>) answer.get(1);
                    helper.assertValueEqual(merged.get("merged"), true, "merged into the click's request");
                    helper.assertValueEqual(merged.get("id"), clickId, "one request, one id");
                    helper.assertValueEqual(merged.get("granted"), MERGE_CAP - PLAYER_SHARE, "up to the cap");
                    helper.assertValueEqual(merged.get("pending"), MERGE_CAP, "the merged amount is the cap");
                    helper.assertValueEqual(access.request("minecraft:diamond", 1, null),
                            List.<Object>of(false, "request_full"), "nothing more fits");
                    ConfigOverrides.restoreAll();
                })
                .thenSucceed();
    }

    /**
     * The access rules: with {@code computerAccess} off every method is an error, and so is every call to a terminal
     * that is gone.
     */
    @GameTest(template = AISLE_16X10X7, batch = CONFIG_BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void computerAccessRules(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_RACK, DIAMOND.toStack(DIAMONDS));
        aisle.terminal(TERMINAL_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    TerminalComputerAccess access = new TerminalComputerAccess(aisle.terminalAt(TERMINAL_RACK));
                    ConfigOverrides.set(helper, WareworksConfig.SERVER.computerAccess, false);
                    String off = "computer access is disabled on this server";
                    expectError(helper, access::status, off);
                    expectError(helper, () -> access.stock(null), off);
                    expectError(helper, () -> access.item("minecraft:diamond"), off);
                    expectError(helper, () -> access.request("minecraft:diamond", 1, null), off);
                    expectError(helper, access::requests, off);
                    expectError(helper, () -> access.openRequest("00000000-0000-0000-0000-000000000000"), off);
                    expectError(helper, access::doors, off);
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 0, "nothing was requested");
                    ConfigOverrides.restoreAll();
                    helper.assertValueEqual(access.status().get("connected"), true, "back on");

                    aisle.breakBlock(aisle.rackPos(TERMINAL_RACK));
                    expectError(helper, access::status, "terminal is gone");
                    expectError(helper, () -> access.request("minecraft:diamond", 1, null), "terminal is gone");
                })
                .thenSucceed();
    }

    /**
     * A request to a package door: {@code getDoors} lists the door by its position, a request naming it is an ordinary
     * request with the door as its destination, and {@code getRequests} shows it with that position. {@code getRequest}
     * finds any open request of the warehouse by its id, also one to a destination the terminal does not list (here a
     * plain output, standing in for a door beyond the screen's rows): a script polling it until nil must not be told
     * "through" while the items are still on their way.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void computerRequestToADoor(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(STORAGE_RACK, DIAMOND.toStack(DIAMONDS));
        aisle.terminal(TERMINAL_RACK);
        aisle.output(DOOR_RACK);
        BlockPos packager = aisle.inventoryPos(DOOR_RACK);
        PackageDoorFixture.placePackager(helper, packager, aisle.sideDirection(DOOR_RACK));
        PackageDoorFixture.writeSign(helper, packager.relative(AisleFixture.AISLE), true, "Computer Door");
        BlockPos door = aisle.absoluteRackPos(DOOR_RACK);
        aisle.output(PLAIN_OUTPUT_RACK);
        BlockPos plainOutput = aisle.absoluteRackPos(PLAIN_OUTPUT_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 3))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    TerminalComputerAccess access = new TerminalComputerAccess(terminal);
                    List<Map<String, Object>> doors = access.doors();
                    helper.assertValueEqual(doors.size(), 1, "one door: " + doors);
                    Map<String, Object> row = doors.getFirst();
                    helper.assertValueEqual(List.of(row.get("x"), row.get("y"), row.get("z")),
                            List.<Object>of(door.getX(), door.getY(), door.getZ()), "its position");
                    helper.assertValueEqual(row.get("label"), "Computer Door", "its sign");
                    helper.assertValueEqual(row.get("state"), "ready", "ready");

                    List<Object> answer = access.request("minecraft:diamond", REQUESTED, door(door));
                    helper.assertValueEqual(answer.getFirst(), true, "accepted: " + answer);
                    String doorId = (String) ((Map<?, ?>) answer.get(1)).get("id");
                    helper.assertTrue(access.openRequest(doorId).isPresent(), "getRequest finds the door's request");
                    helper.assertValueEqual(aisle.controller().requestsFor(door).size(), 1, "to the door");
                    helper.assertTrue(aisle.controller().requestsFor(terminal.getBlockPos()).isEmpty(),
                            "and not to the terminal");
                    List<Map<String, Object>> rows = access.requests();
                    helper.assertValueEqual(rows.size(), 1, "listed");
                    helper.assertValueEqual(rows.getFirst().get("destination"),
                            Map.of("x", door.getX(), "y", door.getY(), "z", door.getZ()), "with the door's position");
                    helper.assertValueEqual(access.request("minecraft:diamond", 1, door(terminal.getBlockPos()))
                            .getFirst(), true, "the terminal's own position means here");
                    helper.assertValueEqual(aisle.controller().requestsFor(terminal.getBlockPos()).size(), 1, "here");

                    RequestResult elsewhere = aisle.controller().request(plainOutput, DIAMOND, 1);
                    helper.assertTrue(elsewhere.isAccepted(), "a request to a destination the terminal does not list: "
                            + elsewhere);
                    String elsewhereId = elsewhere.request().orElseThrow().id().toString();
                    helper.assertTrue(access.requests().stream().noneMatch(r -> elsewhereId.equals(r.get("id"))),
                            "getRequests lists only here and the listed doors");
                    Optional<Map<String, Object>> found = access.openRequest(elsewhereId);
                    helper.assertTrue(found.isPresent(), "getRequest still answers it while it is open");
                    helper.assertValueEqual(found.get().get("destination"),
                            Map.of("x", plainOutput.getX(), "y", plainOutput.getY(), "z", plainOutput.getZ()),
                            "with its destination");
                })
                .thenSucceed();
    }

    /** Restores the config even when a test of {@link #CONFIG_BATCH} fails before its own restore. */
    @AfterBatch(batch = CONFIG_BATCH)
    public static void restoreConfig(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** Fails the test unless {@code call} throws a {@link ComputerArgumentException} with exactly {@code message}. */
    static void expectError(GameTestHelper helper, Runnable call, String message) {
        try {
            call.run();
        } catch (ComputerArgumentException e) {
            helper.assertValueEqual(e.getMessage(), message, "the Lua error");
            return;
        }
        helper.fail("expected the Lua error '" + message + "'");
    }

    /** A Lua door table, as CC hands it over: numbers are doubles. */
    static Map<String, Object> door(BlockPos pos) {
        return Map.of("x", (double) pos.getX(), "y", (double) pos.getY(), "z", (double) pos.getZ());
    }

    /** {@code count} keys of {@code base}'s item that differ only in their custom name. */
    static List<ItemKey> namedKeys(ItemStack base, int count) {
        return IntStream.range(0, count).mapToObj(i -> {
            ItemStack stack = base.copyWithCount(1);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("wareworks computer variant " + i));
            return ItemKey.of(stack);
        }).toList();
    }

    /** A survival player standing at the terminal, so the vanilla container reach check passes. */
    private static Player playerAt(GameTestHelper helper, AisleFixture aisle) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(aisle.rackPos(TERMINAL_RACK)));
        player.moveTo(center.x, center.y, center.z);
        return player;
    }
}
