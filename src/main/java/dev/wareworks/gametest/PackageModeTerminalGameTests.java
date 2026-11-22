package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.simibubi.create.content.logistics.box.PackageItem;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.packagemode.PackageGroupKey;
import dev.wareworks.content.station.PackageDoors;
import dev.wareworks.content.station.TerminalPackageOrderState;
import dev.wareworks.content.station.TerminalRequestOutcome;
import dev.wareworks.content.station.TerminalStockEntry;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.content.storage.RackBayBlock;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.inventory.KeyCount;
import dev.wareworks.core.job.RetrievalRequest;
import dev.wareworks.core.terminal.PackageGroupSummary;
import dev.wareworks.core.terminal.RequestAcknowledgement;
import dev.wareworks.core.terminal.StockCount;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * GameTests of package mode's terminal (issue #40, slice S4; owner decisions E5 and E11): one row per address before the
 * window is cut, what is inside in its summary, the loose stock from before after it, a computer's per-key view kept,
 * and the <b>address order</b> a click on an address row starts — boxes picked at the click, fetched sealed here or to
 * a package door, a few at a time, saved and cancellable, and a box somebody else took counted as gone.
 * <ul>
 * <li>{@code packagemodeterminalgroupsbyaddress}: three addresses fold into three rows with their content summaries,
 * the loose stock follows, no box key is listed, a computer still sees every box key, an address row is held while a box
 * of it is, and a computer's request for an address row is refused;</li>
 * <li>{@code packagemodeaddressorderfetchessealedboxes}: a click on an address row fetches its boxes sealed into the
 * buffer, never more than {@code terminalListOpenRequests} at a time, a second click for the same place is added to the
 * order, a click for another place is refused with {@code PACKAGE_ORDER_ELSEWHERE}; a census holds on every tick;</li>
 * <li>{@code packagemodeaddressordertoadoor}: the boxes go to a package door and the order learns of each arrival
 * there, not by losing the box;</li>
 * <li>{@code packagemodeaddressordersavesandcancels}: the order round-trips through the terminal's save, and its cancel
 * mark gives back exactly its own requests;</li>
 * <li>{@code packagemodeaddressorderdropsgoneboxes}: a picked box taken out by hand before the crane came for it is
 * counted as gone, and the order ends with the rest;</li>
 * <li>{@code packagemodeterminalcutsloosefirst} (own batch, a window of 16): the address rows survive the cut and the
 * loose rows are what it drops, while the menu still knows the dropped rows are in stock;</li>
 * <li>{@code packagemodeoffterminalisunchanged} (switch off): the screen's list is the per-key snapshot itself, an
 * address key is no item, and nothing about an address order is saved.</li>
 * </ul>
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class PackageModeTerminalGameTests {
    static final String BATCH = "wareworkspackagemodeterminal";
    static final String CUT_BATCH = "wareworkspackagemodeterminalcut";

    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final int TEST_RPM = 128;
    private static final int TIMEOUT_TICKS = 2400;
    private static final int PASS_TICKS = 10;

    private static final RackPosition TERMINAL_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition BOX_RACK = new RackPosition(2, 0, Side.LEFT);
    private static final RackPosition LOOSE_RACK = new RackPosition(3, 0, Side.LEFT);
    private static final RackPosition DOOR_RACK = new RackPosition(2, 0, Side.RIGHT);
    /** An empty-able rack bay without a filter, far from the terminal so a cancel reaches the crane on its way. */
    private static final RackPosition PLAIN_BAY_RACK = new RackPosition(4, 0, Side.LEFT);

    private static final ItemKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final ItemKey COAL = ItemKey.of(Items.COAL);
    private static final ItemKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final ItemKey TORCH = ItemKey.of(Items.TORCH);
    private static final ItemKey DIRT = ItemKey.of(Items.DIRT);
    private static final String BASE_NORTH = "Base North";
    private static final String MINE = "Mine";

    private PackageModeTerminalGameTests() {
    }

    @AfterBatch(batch = BATCH)
    public static void restoreAfterTerminal(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    @AfterBatch(batch = CUT_BATCH)
    public static void restoreAfterCut(ServerLevel level) {
        ConfigOverrides.restoreAll();
    }

    private static void switchOn(GameTestHelper helper) {
        ConfigOverrides.set(helper, WareworksConfig.SERVER.packageModeEnabled, true);
        helper.assertTrue(WareworksConfig.packageModeEnabled(), "package mode is on for this batch");
    }

    /** A sealed box with {@code contents} and {@code address} (none for {@code null}). */
    private static ItemStack box(String address, ItemStack... contents) {
        ItemStack box = PackageDoorFixture.packageOf(contents);
        if (address != null)
            PackageItem.addAddress(box, address);
        return box;
    }

    // --- rows ----------------------------------------------------------------------------------------------------------

    /** Owner decision E5 and the critic's M7: rows per address for a screen, rows per key for a computer. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS, batch = BATCH)
    public static void packagemodeterminalgroupsbyaddress(GameTestHelper helper) {
        switchOn(helper);
        List<ItemStack> boxes = List.of(box(BASE_NORTH, IRON.toStack(12)),
                box(BASE_NORTH, IRON.toStack(4), COAL.toStack(8)), box(BASE_NORTH, GOLD.toStack(2)),
                box(MINE, TORCH.toStack(16)), box(MINE, TORCH.toStack(16)), box(null, DIRT.toStack(3)));
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(BOX_RACK, boxes.stream().map(ItemStack::copy).toArray(ItemStack[]::new));
        aisle.storage(LOOSE_RACK, IRON.toStack(64), DIRT.toStack(5));
        aisle.terminal(TERMINAL_RACK);
        Player player = standAt(helper, helper.makeMockPlayer(GameType.SURVIVAL), aisle.rackPos(TERMINAL_RACK));
        ItemKey north = PackageGroupKey.of(BASE_NORTH);
        ItemKey mine = PackageGroupKey.of(MINE);
        ItemKey blank = PackageGroupKey.of("");

        helper.startSequence()
                .thenWaitUntil(() -> {
                    aisle.assertReady(2, 0, 1);
                    helper.assertValueEqual(aisle.controller().stockIndex().distinctKeys(), 7,
                            "five box keys (two Mine boxes are one key) and two loose items are indexed");
                })
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    List<TerminalStockEntry> screen = terminal.screenStockSnapshot();
                    helper.assertValueEqual(screen.size(), 5, "three address rows and two loose rows: " + screen);
                    // "+N not shown" is measured against the folded rows, not against the seven indexed keys (the
                    // visual run found a screen saying "+5 not shown" with nothing cut).
                    helper.assertValueEqual(terminal.screenRowTypes(), 5,
                            "the screen's row count before the cut is the folded one");
                    helper.assertValueEqual(screen.subList(0, 3).stream().allMatch(TerminalStockEntry::isGroup), true,
                            "the address rows come first");
                    helper.assertValueEqual(Set.copyOf(screen.subList(0, 3).stream().map(TerminalStockEntry::key)
                            .toList()), Set.of(north, mine, blank), "one row per address, the blank one included");
                    helper.assertValueEqual(Set.copyOf(screen.subList(3, 5).stream().map(TerminalStockEntry::key)
                            .toList()), Set.of(IRON, DIRT), "then the loose stock from before");
                    for (TerminalStockEntry entry : screen)
                        helper.assertFalse(entry.key().getItem() instanceof PackageItem && !entry.isGroup(),
                                "no box key is listed on its own: " + entry.key());

                    TerminalStockEntry northRow = row(helper, screen, north);
                    helper.assertValueEqual(northRow.total(), 3L, "Base North holds three boxes");
                    helper.assertValueEqual(northRow.available(), 3L, "all of them free to fetch");
                    PackageGroupSummary<ItemKey> inside = northRow.group().orElseThrow();
                    helper.assertValueEqual(inside.contents(), List.of(new KeyCount<>(IRON, 16L),
                            new KeyCount<>(COAL, 8L), new KeyCount<>(GOLD, 2L)),
                            "what is inside, summed over the boxes and largest first");
                    helper.assertValueEqual(inside.kinds(), 3, "three kinds");
                    helper.assertFalse(inside.networkOrder(), "no box belongs to a network order");
                    helper.assertValueEqual(row(helper, screen, mine).group().orElseThrow().contents(),
                            List.of(new KeyCount<>(TORCH, 32L)), "two identical boxes hold twice one");
                    helper.assertValueEqual(row(helper, screen, mine).total(), 2L, "two Mine boxes");
                    helper.assertValueEqual(row(helper, screen, blank).total(), 1L, "one box without an address");

                    // The menu's conversion keeps the summary, which is what travels to the screen.
                    WarehouseTerminalMenu menu = openMenu(player, terminal);
                    for (StockCount<ItemKey> count : menu.stockCounts())
                        helper.assertValueEqual(count.isGroup(), PackageGroupKey.isGroup(count.key()),
                                "a summary exactly on the address rows: " + count.key());

                    // A computer keeps its per-key view (critic M7): every box key, and no address row.
                    List<TerminalStockEntry> perKey = terminal.stockSnapshot();
                    Set<ItemKey> boxKeys = new HashSet<>();
                    for (ItemStack stack : boxes)
                        boxKeys.add(ItemKey.of(stack));
                    helper.assertTrue(perKey.stream().map(TerminalStockEntry::key).toList().containsAll(boxKeys),
                            "a computer still reads every box key");
                    helper.assertTrue(perKey.stream().noneMatch(TerminalStockEntry::isGroup),
                            "and no address row");
                    helper.assertValueEqual(terminal.requestFromComputer(north, 1, Optional.empty()).rejection(),
                            Optional.of(RequestRejection.NOT_IN_STOCK), "an address row is no item for a computer");

                    helper.assertTrue(terminal.holdsInStock(north), "an address row is held while a box of it is");
                    helper.assertFalse(terminal.holdsInStock(PackageGroupKey.of("Nowhere")),
                            "and gone when no box of it is");
                    helper.assertFalse(PackageGroupKey.isGroup(ItemKey.of(boxes.getFirst())),
                            "a real box is never taken for an address row");
                })
                .thenSucceed();
    }

    // --- address orders ------------------------------------------------------------------------------------------------

    /**
     * Owner decision E11: a click on an address row picks boxes and fetches them sealed, a few at a time; a further
     * click for the same place joins the order, one for another place is refused.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS, batch = BATCH)
    public static void packagemodeaddressorderfetchessealedboxes(GameTestHelper helper) {
        switchOn(helper);
        List<ItemStack> northBoxes = new ArrayList<>();
        for (int i = 1; i <= 4; i++)
            northBoxes.add(box(BASE_NORTH, IRON.toStack(i)));
        ItemStack mineBox = box(MINE, TORCH.toStack(5));
        ItemStack keptBox = box(MINE, COAL.toStack(7));
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        List<ItemStack> stored = new ArrayList<>(northBoxes);
        stored.add(mineBox);
        stored.add(keptBox);
        aisle.storage(BOX_RACK, stored.stream().map(ItemStack::copy).toArray(ItemStack[]::new));
        aisle.terminal(TERMINAL_RACK);
        aisle.output(DOOR_RACK);
        BlockPos door = aisle.absoluteRackPos(DOOR_RACK);
        BlockPos here = aisle.absoluteRackPos(TERMINAL_RACK);
        Player player = standAt(helper, helper.makeMockPlayer(GameType.SURVIVAL), aisle.rackPos(TERMINAL_RACK));
        ItemKey north = PackageGroupKey.of(BASE_NORTH);
        ItemKey mine = PackageGroupKey.of(MINE);
        Map<ItemKey, Long> expected = ItemCensus.of(IRON, 10, TORCH, 5, COAL, 7);
        int openLimit = WareworksConfig.terminalListOpenRequests();
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, expected, "while an address order runs");
            aisle.controllerIfPresent().ifPresent(controller -> helper.assertTrue(
                    controller.requestsFor(here).size() <= openLimit,
                    "never more open requests than one list order may keep: " + controller.requestsFor(here)));
        });
        AtomicReference<WarehouseTerminalBlockEntity> terminal = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 2))
                .thenExecute(() -> {
                    terminal.set(aisle.terminalAt(TERMINAL_RACK));
                    WarehouseTerminalMenu menu = openMenu(player, terminal.get());
                    TerminalRequestOutcome first = menu.request(player, north, 4, RequestAcknowledgement.NONE);
                    RequestResult result = first.result().orElseThrow();
                    helper.assertTrue(result.isAccepted(), "the address row's click is taken: " + result);
                    helper.assertValueEqual(result.granted(), 4, "four boxes of Base North picked");
                    helper.assertValueEqual(result.request().map(RetrievalRequest::key), Optional.of(north),
                            "the answer names the address row");
                    helper.assertFalse(result.merged(), "a new order");
                    TerminalPackageOrderState state = terminal.get().packageOrderState();
                    helper.assertTrue(state.isOpen(), "an address order runs");
                    helper.assertValueEqual(state.wanted(), 4L, "for four boxes");
                    helper.assertValueEqual(state.door(), Optional.empty(), "into this terminal");

                    helper.assertTrue(PackageDoors.refusal(helper.getLevel(), aisle.controller(), door).isEmpty(),
                            "the plain port is a door in package mode");
                    Optional<RequestResult> elsewhere = terminal.get().requestFromTerminal(player, mine, 1,
                            RequestAcknowledgement.NONE, Optional.of(door)).result();
                    helper.assertValueEqual(elsewhere.flatMap(RequestResult::rejection),
                            Optional.of(RequestRejection.PACKAGE_ORDER_ELSEWHERE),
                            "a click for another place waits for the running order");

                    RequestResult joined = terminal.get().requestFromTerminal(player, mine, 1,
                            RequestAcknowledgement.NONE, Optional.empty()).result().orElseThrow();
                    helper.assertTrue(joined.isAccepted() && joined.merged(), "a click for the same place joins it");
                    helper.assertValueEqual(joined.granted(), 1, "one Mine box");
                    helper.assertValueEqual(terminal.get().packageOrderState().wanted(), 5L, "five boxes in all");
                    helper.assertValueEqual(terminal.get().packageOrderState().addresses(),
                            List.of(BASE_NORTH, MINE), "for two addresses");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> helper.assertFalse(terminal.get().hasOpenPackageOrder(),
                        "the order ends once every box arrived"))
                .thenExecute(() -> {
                    helper.assertValueEqual(terminal.get().packageOrderState(), TerminalPackageOrderState.NONE,
                            "and is forgotten");
                    List<ItemStack> arrived = bufferStacks(terminal.get().menuBuffer());
                    helper.assertValueEqual(arrived.size(), 5, "five boxes in the buffer: " + arrived);
                    for (ItemStack box : northBoxes)
                        helper.assertTrue(arrived.stream().anyMatch(stack -> ItemStack.matches(stack, box)),
                                "a box arrived sealed, address and contents intact: " + box);
                    // One of the two Mine boxes was picked, whichever comes first in the retrieval order.
                    long mineArrived = arrived.stream().filter(stack -> ItemStack.matches(stack, mineBox)
                            || ItemStack.matches(stack, keptBox)).count();
                    helper.assertValueEqual(mineArrived, 1L, "exactly one Mine box arrived, sealed");
                    helper.assertValueEqual(aisle.controller().countOf(ItemKey.of(mineBox))
                            + aisle.controller().countOf(ItemKey.of(keptBox)), 1L,
                            "and the other one stays stored");
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /** The boxes of an address order go to a package door, and the order is told of each arrival there. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS, batch = BATCH)
    public static void packagemodeaddressordertoadoor(GameTestHelper helper) {
        switchOn(helper);
        ItemStack one = box(BASE_NORTH, IRON.toStack(3));
        ItemStack two = box(BASE_NORTH, IRON.toStack(5));
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(BOX_RACK, one.copy(), two.copy());
        aisle.terminal(TERMINAL_RACK);
        aisle.output(DOOR_RACK);
        BlockPos door = aisle.absoluteRackPos(DOOR_RACK);
        Player player = standAt(helper, helper.makeMockPlayer(GameType.SURVIVAL), aisle.rackPos(TERMINAL_RACK));
        Map<ItemKey, Long> expected = ItemCensus.of(IRON, 8);
        AtomicReference<WarehouseTerminalBlockEntity> terminal = new AtomicReference<>();
        AtomicLong creditedAtTheDoor = new AtomicLong();
        helper.onEachTick(() -> {
            ItemCensus.assertEquals(helper, expected, "while boxes go to a door");
            if (terminal.get() == null)
                return;
            TerminalPackageOrderState state = terminal.get().packageOrderState();
            helper.assertValueEqual(state.gone(), 0L, "no box ever counts as gone: each is credited at the door");
            creditedAtTheDoor.accumulateAndGet(state.delivered(), Math::max);
        });

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 2))
                .thenExecute(() -> {
                    terminal.set(aisle.terminalAt(TERMINAL_RACK));
                    RequestResult result = terminal.get().requestFromTerminal(player, PackageGroupKey.of(BASE_NORTH),
                            2, RequestAcknowledgement.NONE, Optional.of(door)).result().orElseThrow();
                    helper.assertTrue(result.isAccepted(), "the boxes are ordered to the door: " + result);
                    helper.assertValueEqual(terminal.get().packageOrderState().door(), Optional.of(door),
                            "the order names the door");
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> helper.assertFalse(terminal.get().hasOpenPackageOrder(),
                        "the order ends when both boxes are at the door"))
                .thenExecute(() -> {
                    helper.assertValueEqual(creditedAtTheDoor.get(), 1L,
                            "the first box was credited at the door before the second finished the order");
                    IItemHandler port = aisle.handlerAt(aisle.rackPos(DOOR_RACK));
                    List<ItemStack> sent = bufferStacks(port);
                    helper.assertValueEqual(sent.size(), 2, "both boxes are in the door: " + sent);
                    for (ItemStack box : List.of(one, two))
                        helper.assertTrue(sent.stream().anyMatch(stack -> ItemStack.matches(stack, box)),
                                "sealed, with their address: " + box);
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /** The order survives the terminal's save, and its cancel gives back exactly its own requests. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS, batch = BATCH)
    public static void packagemodeaddressordersavesandcancels(GameTestHelper helper) {
        switchOn(helper);
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(BOX_RACK, box(BASE_NORTH, IRON.toStack(1)), box(BASE_NORTH, IRON.toStack(2)),
                box(BASE_NORTH, IRON.toStack(3)));
        aisle.terminal(TERMINAL_RACK);
        Player player = standAt(helper, helper.makeMockPlayer(GameType.SURVIVAL), aisle.rackPos(TERMINAL_RACK));
        Player other = standAt(helper, helper.makeMockPlayer(GameType.SURVIVAL), aisle.rackPos(TERMINAL_RACK));
        Map<ItemKey, Long> expected = ItemCensus.of(IRON, 6);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while nothing moves"));
        AtomicReference<WarehouseTerminalBlockEntity> terminal = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    terminal.set(aisle.terminalAt(TERMINAL_RACK));
                    helper.assertTrue(terminal.get().requestFromTerminal(player, PackageGroupKey.of(BASE_NORTH), 3,
                            RequestAcknowledgement.NONE, Optional.empty()).result().orElseThrow().isAccepted(),
                            "three boxes ordered");
                })
                .thenIdle(PASS_TICKS)
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity live = terminal.get();
                    TerminalPackageOrderState before = live.packageOrderState();
                    helper.assertTrue(before.inFlight() > 0, "a pass made its first requests: " + before);
                    helper.assertTrue(aisle.controller().openRequestCount() > 0, "they are in the queue");
                    CompoundTag saved = live.saveWithFullMetadata(helper.getLevel().registryAccess());
                    helper.assertTrue(saved.contains("PackageOrder"), "the open order is saved");
                    WarehouseTerminalBlockEntity loaded = PackageDoorFixture.loadCopy(helper, live, saved,
                            WarehouseTerminalBlockEntity.class);
                    helper.assertValueEqual(loaded.packageOrderState(), before,
                            "and loads back as it was: id, boxes, requests, destination and addresses");

                    helper.assertFalse(live.cancelPackageOrder(player, java.util.UUID.randomUUID()),
                            "a wrong id cancels nothing");
                    helper.assertTrue(live.cancelPackageOrder(other, before.id()),
                            "any player at the terminal may cancel it on its line");
                    helper.assertValueEqual(aisle.controller().openRequestCount(), 0,
                            "its requests were wholly its own and are gone");
                    helper.assertValueEqual(live.packageOrderState(), TerminalPackageOrderState.NONE, "no order");
                    helper.assertFalse(live.saveWithoutMetadata(helper.getLevel().registryAccess())
                            .contains("PackageOrder"), "and nothing about one is saved any more");
                })
                .thenSucceed();
    }

    /** A picked box taken out by hand before the crane came for it is counted as gone, and the rest arrive. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS, batch = BATCH)
    public static void packagemodeaddressorderdropsgoneboxes(GameTestHelper helper) {
        switchOn(helper);
        List<ItemStack> boxes = new ArrayList<>();
        for (int i = 1; i <= 4; i++)
            boxes.add(box(BASE_NORTH, GOLD.toStack(i)));
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        BlockPos chest = aisle.storage(BOX_RACK, boxes.stream().map(ItemStack::copy).toArray(ItemStack[]::new));
        aisle.terminal(TERMINAL_RACK);
        BlockPos here = aisle.absoluteRackPos(TERMINAL_RACK);
        Player player = standAt(helper, helper.makeMockPlayer(GameType.SURVIVAL), aisle.rackPos(TERMINAL_RACK));
        Map<ItemKey, Long> expected = ItemCensus.of(GOLD, 10);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, expected, "while an address order runs"));
        AtomicReference<WarehouseTerminalBlockEntity> terminal = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    terminal.set(aisle.terminalAt(TERMINAL_RACK));
                    helper.assertTrue(terminal.get().requestFromTerminal(player, PackageGroupKey.of(BASE_NORTH), 4,
                            RequestAcknowledgement.NONE, Optional.empty()).result().orElseThrow().isAccepted(),
                            "four boxes ordered while the crane stands still");
                })
                .thenIdle(PASS_TICKS)
                .thenExecute(() -> {
                    Set<ItemKey> requested = new HashSet<>();
                    for (RetrievalRequest<ItemKey, BlockPos> request : aisle.controller().requestsFor(here))
                        requested.add(request.key());
                    helper.assertTrue(requested.size() < boxes.size(), "some boxes wait for a request: " + requested);
                    // Take one box nobody has asked the crane for yet out of the chest, as a player's hand would.
                    IItemHandler handler = aisle.handlerAt(chest);
                    ItemStack taken = ItemStack.EMPTY;
                    for (int slot = 0; slot < handler.getSlots() && taken.isEmpty(); slot++) {
                        ItemStack stack = handler.getStackInSlot(slot);
                        if (!stack.isEmpty() && !requested.contains(ItemKey.of(stack)))
                            taken = handler.extractItem(slot, 1, false);
                    }
                    helper.assertTrue(PackageItem.isPackage(taken), "a box was taken out: " + taken);
                    ItemCensus.change(expected, GOLD, -PackageDoorFixture.contentsOf(taken).getOrDefault(GOLD, 0L));
                })
                .thenWaitUntil(() -> {
                    TerminalPackageOrderState state = terminal.get().packageOrderState();
                    helper.assertValueEqual(state.gone(), 1L, "the order sees the box gone: " + state);
                    helper.assertValueEqual(state.wanted(), 3L, "and waits for three");
                })
                .thenExecute(() -> aisle.motor().generatedSpeed.setValue(TEST_RPM))
                .thenWaitUntil(() -> helper.assertFalse(terminal.get().hasOpenPackageOrder(),
                        "the order ends with the other three"))
                .thenExecute(() -> helper.assertValueEqual(bufferStacks(terminal.get().menuBuffer()).size(), 3,
                        "three boxes arrived"))
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /**
     * Review fix (conservation MAJOR 2): a box that was stored in a plain rack bay before the switch, picked for an
     * address order that is then given up while the crane carries it, goes back into the bay it came from, although
     * package mode keeps boxes out of an empty bay without a Package Filter: there is no other place for it, and a
     * reroute that puts back items which already left the warehouse ranks such a bay last instead of dropping it. Without
     * that the crane would hold the box for ever.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS, batch = BATCH)
    public static void packagemodecancelledboxgoesbackintoitsplainbay(GameTestHelper helper) {
        switchOn(helper);
        ItemStack box = box(BASE_NORTH, IRON.toStack(7));
        ItemKey boxKey = ItemKey.of(box);
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        helper.setBlock(aisle.rackPos(PLAIN_BAY_RACK), WareworksBlocks.RACK_BAY_WOOD.getDefaultState()
                .setValue(RackBayBlock.FACING, aisle.sideDirection(PLAIN_BAY_RACK)));
        aisle.terminal(TERMINAL_RACK);
        Player player = standAt(helper, helper.makeMockPlayer(GameType.SURVIVAL), aisle.rackPos(TERMINAL_RACK));
        Map<ItemKey, Long> expected = ItemCensus.of(IRON, 7);
        AtomicReference<WarehouseTerminalBlockEntity> terminal = new AtomicReference<>();

        helper.startSequence()
                .thenExecute(() -> aisle.insertAll(aisle.handlerAt(aisle.rackPos(PLAIN_BAY_RACK)), box.copy()))
                .thenWaitUntil(() -> {
                    aisle.assertReady(1, 0, 1);
                    helper.assertValueEqual(aisle.controller().countOf(boxKey), 1L, "the box is in the plain bay");
                })
                .thenExecute(() -> {
                    ItemCensus.assertEquals(helper, expected, "before the order");
                    terminal.set(aisle.terminalAt(TERMINAL_RACK));
                    RequestResult ordered = terminal.get().requestFromTerminal(player, PackageGroupKey.of(BASE_NORTH),
                            1, RequestAcknowledgement.NONE, Optional.empty()).result().orElseThrow();
                    helper.assertTrue(ordered.isAccepted() && ordered.granted() == 1,
                            "the box is ordered to the terminal: " + ordered);
                    helper.assertTrue(terminal.get().hasOpenPackageOrder(), "an address order runs: "
                            + terminal.get().packageOrderState());
                    aisle.motor().generatedSpeed.setValue(TEST_RPM);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.dock().heldItems().count(boxKey), 1,
                        "the crane carries the box out of the bay (crane " + aisle.dock().craneState().phase() + ", job "
                                + aisle.dock().currentJob() + ", last plan " + aisle.controller().lastPlanReason()
                                + ", requests " + aisle.controller().openRequestCount() + ", order "
                                + terminal.get().packageOrderState() + ", bay "
                                + aisle.inventoryCount(aisle.rackPos(PLAIN_BAY_RACK), boxKey) + ", terminal "
                                + bufferStacks(terminal.get().menuBuffer()) + ")"))
                .thenExecute(() -> {
                    helper.assertValueEqual(aisle.inventoryCount(aisle.rackPos(PLAIN_BAY_RACK), boxKey), 0L,
                            "the bay is empty now");
                    TerminalPackageOrderState state = terminal.get().packageOrderState();
                    helper.assertTrue(terminal.get().cancelPackageOrder(player, state.id()),
                            "the order is given up while the box is on its way");
                })
                .thenWaitUntil(() -> {
                    helper.assertValueEqual(aisle.inventoryCount(aisle.rackPos(PLAIN_BAY_RACK), boxKey), 1L,
                            "the box went back into the plain bay it came from");
                    aisle.assertIdleAndEmpty();
                })
                .thenExecute(() -> {
                    ItemCensus.assertEquals(helper, expected, "nothing lost, nothing opened");
                    helper.assertTrue(bufferStacks(terminal.get().menuBuffer()).isEmpty(), "nothing reached the terminal");
                })
                .thenSucceed();
    }

    // --- the window ----------------------------------------------------------------------------------------------------

    /** Design §6.1: the address rows come before the cut, the loose rows are what it drops. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS, batch = CUT_BATCH)
    public static void packagemodeterminalcutsloosefirst(GameTestHelper helper) {
        switchOn(helper);
        int window = 16;
        ConfigOverrides.set(helper, WareworksConfig.SERVER.maxTerminalStockEntries, window);
        List<Item> loose = List.of(Items.STONE, Items.COBBLESTONE, Items.GRANITE, Items.DIORITE, Items.ANDESITE,
                Items.DIRT, Items.SAND, Items.GRAVEL, Items.CLAY_BALL, Items.FLINT, Items.STICK, Items.OAK_LOG,
                Items.BIRCH_LOG, Items.SPRUCE_LOG, Items.OAK_PLANKS, Items.BIRCH_PLANKS, Items.SPRUCE_PLANKS,
                Items.GLASS);
        List<ItemStack> looseStacks = new ArrayList<>();
        for (int i = 0; i < loose.size(); i++)
            looseStacks.add(new ItemStack(loose.get(i), 1 + i));
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(BOX_RACK, box(BASE_NORTH, IRON.toStack(1)), box(MINE, IRON.toStack(1)), box("Farm",
                IRON.toStack(1)));
        aisle.storage(LOOSE_RACK, looseStacks.toArray(ItemStack[]::new));
        aisle.terminal(TERMINAL_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 1))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    List<TerminalStockEntry> screen = terminal.screenStockSnapshot();
                    helper.assertValueEqual(screen.size(), window, "the window is full");
                    helper.assertValueEqual(screen.stream().filter(TerminalStockEntry::isGroup).count(), 3L,
                            "every address row survives the cut");
                    Set<ItemKey> shown = new HashSet<>();
                    screen.forEach(entry -> shown.add(entry.key()));
                    long cut = loose.stream().map(ItemKey::of).filter(key -> !shown.contains(key)).count();
                    helper.assertValueEqual(cut, (long) (loose.size() + 3 - window), "the cut takes loose rows");
                    helper.assertValueEqual(terminal.screenRowTypes(), loose.size() + 3,
                            "the rows before the cut, so the screen says how many it did not get");
                    for (Item item : loose)
                        helper.assertTrue(terminal.holdsInStock(ItemKey.of(item)),
                                "a row the cut dropped is still in stock, so the menu never reports it gone");
                })
                .thenSucceed();
    }

    // --- off -----------------------------------------------------------------------------------------------------------

    /** With the switch off the terminal's list, its clicks and its save are what they were before package mode. */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void packagemodeoffterminalisunchanged(GameTestHelper helper) {
        helper.assertFalse(WareworksConfig.packageModeEnabled(), "package mode is off by default");
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(false);
        aisle.storage(BOX_RACK, box(BASE_NORTH, IRON.toStack(1)), box(BASE_NORTH, IRON.toStack(2)));
        aisle.storage(LOOSE_RACK, IRON.toStack(9));
        aisle.terminal(TERMINAL_RACK);
        Player player = standAt(helper, helper.makeMockPlayer(GameType.SURVIVAL), aisle.rackPos(TERMINAL_RACK));

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(2, 0, 1))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    helper.assertValueEqual(terminal.screenStockSnapshot(), terminal.stockSnapshot(),
                            "the screen's list is the per-key snapshot");
                    helper.assertTrue(terminal.screenStockSnapshot().stream().noneMatch(TerminalStockEntry::isGroup),
                            "with no address row");
                    Optional<RequestResult> result = terminal.requestFromTerminal(player,
                            PackageGroupKey.of(BASE_NORTH), 1, RequestAcknowledgement.NONE, Optional.empty())
                            .result();
                    helper.assertValueEqual(result.flatMap(RequestResult::rejection),
                            Optional.of(RequestRejection.NOT_IN_STOCK), "an address key is no item with the switch off");
                    helper.assertValueEqual(terminal.packageOrderState(), TerminalPackageOrderState.NONE,
                            "and starts no address order");
                    helper.assertFalse(terminal.saveWithoutMetadata(helper.getLevel().registryAccess())
                            .contains("PackageOrder"), "nothing about one is saved");
                })
                .thenSucceed();
    }

    // --- helpers -------------------------------------------------------------------------------------------------------

    private static TerminalStockEntry row(GameTestHelper helper, List<TerminalStockEntry> rows, ItemKey key) {
        for (TerminalStockEntry entry : rows) {
            if (entry.key().equals(key))
                return entry;
        }
        helper.fail("no row for " + key + " in " + rows);
        throw new IllegalStateException("unreachable");
    }

    private static List<ItemStack> bufferStacks(IItemHandler handler) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty())
                stacks.add(stack.copy());
        }
        return stacks;
    }

    /** The menu a player has open, as {@code openScreen} would build it on the server. */
    private static WarehouseTerminalMenu openMenu(Player player, WarehouseTerminalBlockEntity terminal) {
        WarehouseTerminalMenu menu = WarehouseTerminalMenu.create(player.containerMenu.containerId + 1,
                player.getInventory(), terminal);
        player.containerMenu = menu;
        return menu;
    }

    private static <P extends Player> P standAt(GameTestHelper helper, P player, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(pos));
        player.moveTo(center.x, center.y, center.z);
        return player;
    }
}
