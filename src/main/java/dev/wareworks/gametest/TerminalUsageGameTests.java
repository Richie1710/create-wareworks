package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardEntry;
import com.simibubi.create.content.equipment.clipboard.ClipboardOverrides.ClipboardType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.TerminalListResult;
import dev.wareworks.content.station.TerminalPreferences;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.terminal.TerminalSort;
import dev.wareworks.core.terminal.TerminalUsage;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.network.TerminalUsagePayload;
import dev.wareworks.registry.WareworksAttachments;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;

/**
 * GameTests of the terminal's <b>"most used" order as the server owns it</b> ({@code docs/warehouse-system.md} §3.4.2,
 * M24, issue #17, ADR-037): what counts, what does not, what a whole clipboard order counts, the two bounds (how many
 * item types and how large one may be), the eviction, the saved shape a reload reads back, the copy a death makes, two
 * players on one world, and the wire.
 * <p>
 * The counting rules themselves are unit tested without a game ({@code core.terminal.TerminalUsageTest},
 * {@code TerminalSortTest}). What needs a world is everything these tests do: that a <b>player's</b> request reaches
 * the store and a <b>redstone</b> request reaches nothing, that two players on one server are two stores, that the
 * attachment's own save and load survive a round trip with real item components, and that the payload reads back
 * exactly what it wrote.
 * <p>
 * The aisle is the {@link AisleFixture} on {@code aisle_16x10x7}: a chest with diamonds and emeralds behind an
 * interface at rack position 1 left, a terminal at 0 right and a requesting port at 2 right.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class TerminalUsageGameTests {
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final RackPosition TERMINAL_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition STORAGE_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final RackPosition PORT_RACK = new RackPosition(2, 0, Side.RIGHT);

    private static final int DIAMONDS_IN_STOCK = 40;
    private static final int EMERALDS_IN_STOCK = 40;
    private static final int REQUESTED = 4;
    /** How many of each item type the clipboard order of this holder asks for; well inside what the racks hold. */
    private static final int LISTED = 4;
    private static final int TIMEOUT_TICKS = 1200;

    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final ItemKey EMERALD = ItemKey.of(Items.EMERALD);

    private TerminalUsageGameTests() {
    }

    // --- what counts -----------------------------------------------------------------------------------------------

    /**
     * A player's accepted terminal request counts once, whatever amount it asked for; a refusal counts nothing; and a
     * <b>redstone</b> request at a port counts nothing for anybody — a port is not a player.
     * <p>
     * The counts are the player's own and come straight off the player, not off the terminal, which is the whole point
     * of where this state lives: the same numbers would answer at any other terminal of the world.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void terminalrequestscountredstonerequestsdonot(GameTestHelper helper) {
        AisleFixture aisle = buildAisle(helper);

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().countOf(DIAMOND),
                        (long) DIAMONDS_IN_STOCK, "the diamonds are indexed"))
                .thenWaitUntil(() -> helper.assertTrue(
                        aisle.controller().locationAt(aisle.absoluteRackPos(PORT_RACK))
                                .filter(record -> record.kind() == LocationKind.OUTPUT).isPresent(),
                        "the requesting port is a member of the aisle"))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(helper, aisle);
                    Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
                    helper.assertTrue(TerminalPreferences.existing(player).isEmpty(),
                            "a player who has used no terminal has no preferences at all");

                    // One accepted click: one count, and nothing else learned.
                    RequestResult first = terminal.requestFromTerminal(player, DIAMOND, REQUESTED);
                    helper.assertTrue(first.isAccepted(), "the first request is accepted");
                    helper.assertValueEqual(countOf(player, DIAMOND), 1L, "one click counts once");
                    helper.assertValueEqual(countOf(player, EMERALD), 0L, "nothing else was asked for");

                    // A second click for the same item, asking for far more, still counts exactly one: the order
                    // answers "what does this player keep fetching", not "what moved the most items".
                    helper.assertTrue(terminal.requestFromTerminal(player, DIAMOND, DIAMONDS_IN_STOCK).isAccepted(),
                            "a bigger second request is accepted");
                    helper.assertValueEqual(countOf(player, DIAMOND), 2L, "the second click counts one more, not more");

                    // A refusal is not a habit: an item the aisle does not hold counts nothing.
                    ItemKey unstocked = ItemKey.of(Items.NETHERITE_INGOT);
                    helper.assertFalse(terminal.requestFromTerminal(player, unstocked, 1).isAccepted(),
                            "an unstocked item is refused");
                    helper.assertValueEqual(countOf(player, unstocked), 0L, "a refused request counts nothing");

                    // The redstone path: the very call a rising edge makes (WarehouseOutputBlockEntity#submitRequest),
                    // with no player anywhere in it.
                    WarehouseOutputBlockEntity port = port(helper, aisle);
                    FilteringBehaviour filter = BlockEntityBehaviour.get(port, FilteringBehaviour.TYPE);
                    if (filter == null)
                        helper.fail("the port has no request filter", aisle.rackPos(PORT_RACK));
                    filter.setFilter(EMERALD.toStack());
                    filter.count = REQUESTED;
                    helper.assertTrue(port.submitRequest().isAccepted(), "the port's request is accepted");
                    helper.assertValueEqual(countOf(player, EMERALD), 0L,
                            "a redstone request teaches the player's terminal nothing");
                    helper.assertValueEqual(countOf(player, DIAMOND), 2L, "and changes no other count");

                    // The whole store is still just the one item type the player really asked for.
                    helper.assertValueEqual(TerminalPreferences.of(player).counts().size(), 1,
                            "one item type remembered");
                })
                .thenSucceed();
    }

    // --- a clipboard order, which is one action that names many item types -----------------------------------------

    /**
     * A clipboard order counts <b>once per item type</b> on the list, and the <b>same list ordered again raises those
     * counts</b> (M24 review fix, {@code docs/warehouse-system.md} §3.4.2).
     * <p>
     * The second half is what the review found: the keys were counted one by one, so on a list with more item types
     * than the store has room for the later keys evicted the earlier ones — a brand new entry is the weakest entry
     * there is — and a repeated identical order was a fixed point at count 1 that could never teach the terminal
     * anything. Ordering the same list again is the most repeated action a building player has
     * ({@link TerminalUsage#recordAll}).
     * <p>
     * It runs on the real path a player uses ({@code WarehouseTerminalBlockEntity#fetchList} with a written clipboard
     * in the list slot, exactly as {@code TerminalListGameTests} writes one) and the store is read off the live
     * player. The first order is given up before the second one is placed, because a terminal runs one list at a time
     * and what is being tested is the counting, not the delivery.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void aclipboardordercountsonceperitemtype(GameTestHelper helper) {
        AisleFixture aisle = buildAisle(helper);

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().countOf(DIAMOND),
                        (long) DIAMONDS_IN_STOCK, "the diamonds are indexed"))
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().countOf(EMERALD),
                        (long) EMERALDS_IN_STOCK, "and the emeralds"))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(helper, aisle);
                    Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
                    // Diamonds on two lines, emeralds on one: two item types, three lines.
                    ItemStack clipboard = clipboard(entry(DIAMOND, LISTED), entry(EMERALD, LISTED),
                            entry(DIAMOND, LISTED));
                    ItemStack rest = terminal.listSlot().insertItem(WarehouseTerminalBlockEntity.LIST_SLOT,
                            clipboard.copy(), false);
                    helper.assertTrue(rest.isEmpty(), "the list slot took the clipboard");

                    helper.assertValueEqual(terminal.fetchList(player, 0L, 0L, 0L).result(),
                            TerminalListResult.STARTED, "a list the warehouse covers starts without a question");
                    helper.assertValueEqual(countOf(player, DIAMOND), 1L,
                            "the item type named on two lines counts once");
                    helper.assertValueEqual(countOf(player, EMERALD), 1L, "and so does the one named once");
                    helper.assertValueEqual(TerminalPreferences.of(player).counts().size(), 2,
                            "two item types remembered, one per item type the list names");

                    // The same list again: the counts it came to raise are raised, not displaced by its own keys.
                    helper.assertTrue(terminal.cancelListOrder(), "the first order is given up");
                    helper.assertValueEqual(terminal.fetchList(player, 0L, 0L, 0L).result(),
                            TerminalListResult.STARTED, "the same list is ordered a second time");
                    helper.assertValueEqual(countOf(player, DIAMOND), 2L, "ordering it again raises the count");
                    helper.assertValueEqual(countOf(player, EMERALD), 2L, "for every item type of the list");
                    helper.assertValueEqual(TerminalPreferences.of(player).counts().size(), 2,
                            "and nothing else was learned");
                    terminal.cancelListOrder();
                })
                .thenSucceed();
    }

    /**
     * An item type whose key is too large to remember is <b>not counted</b>, and the request itself is unaffected
     * ({@link TerminalPreferences#MAX_KEY_SIZE}, M24 review fix).
     * <p>
     * An {@code ItemKey} carries the item's whole data-component patch, so one key can be a shulker box with a
     * container component of 27 stacks in it, or a written book — kilobytes each, in save data written for every player
     * of the world. The entry count alone would happily bound 64 of them, which is why the size bound exists; a
     * clipboard order is where it matters most, because a clipboard entry's icon is an arbitrary stack a player wrote
     * and need not be anything the warehouse ever held.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void anoversizeditemkeyisnotremembered(GameTestHelper helper) {
        AisleFixture aisle = buildAisle(helper);

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().countOf(DIAMOND),
                        (long) DIAMONDS_IN_STOCK, "the diamonds are indexed"))
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    WarehouseTerminalBlockEntity terminal = terminal(helper, aisle);
                    Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));

                    // A plain key is far below the bound; a shulker box carrying 27 stacks is far above it.
                    helper.assertTrue(TerminalPreferences.worthRemembering(DIAMOND, registries),
                            "an ordinary item is remembered");
                    ItemKey huge = containerKey();
                    helper.assertFalse(TerminalPreferences.worthRemembering(huge, registries),
                            "a key of kilobytes is not");

                    // A real accepted request for an ordinary item counts; the oversized key counts nothing, through
                    // both entry points, and neither call refuses anything to the player.
                    helper.assertTrue(terminal.requestFromTerminal(player, DIAMOND, REQUESTED).isAccepted(), "stock");
                    helper.assertTrue(TerminalPreferences.countRequest(player, DIAMOND), "the ordinary key counts");
                    helper.assertFalse(TerminalPreferences.countRequest(player, huge), "the oversized key does not");
                    helper.assertValueEqual(TerminalPreferences.countListOrder(player, List.of(huge, EMERALD)), 1,
                            "a list counts the item types it may remember and skips the rest");
                    helper.assertValueEqual(countOf(player, huge), 0L, "nothing was counted for it");
                    helper.assertValueEqual(countOf(player, EMERALD), 1L, "and its neighbour on the list still was");
                    helper.assertValueEqual(TerminalPreferences.of(player).counts().size(), 2,
                            "two item types remembered, neither of them the oversized one");

                    // And what is not counted cannot be saved either: the shape has no entry for it.
                    TerminalPreferences crafted = new TerminalPreferences();
                    crafted.usage().record(huge);
                    crafted.usage().record(DIAMOND);
                    CompoundTag tag = crafted.save(registries);
                    helper.assertTrue(tag != null, "there is something to save");
                    helper.assertValueEqual(tag.getList(TerminalPreferences.USED_TAG, Tag.TAG_COMPOUND).size(), 1,
                            "a save drops an oversized entry an older build could have written");
                })
                .thenSucceed();
    }

    // --- the cap ---------------------------------------------------------------------------------------------------

    /**
     * The store never grows past its capacity, and a new item type a player asks for displaces the <b>weakest</b>
     * entry — the lowest count, among equal counts the one asked for longest ago — never a favourite.
     * <p>
     * The store is filled to its real, configured capacity first (which is cheap: a count is two numbers), and the
     * entry that then has to go is made by a <b>real terminal request</b>, so what is exercised is the live store on
     * the live player and not a unit-test copy of it.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void theusagecapevictstheweakestentry(GameTestHelper helper) {
        AisleFixture aisle = buildAisle(helper);

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().countOf(DIAMOND),
                        (long) DIAMONDS_IN_STOCK, "the diamonds are indexed"))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(helper, aisle);
                    Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
                    TerminalPreferences preferences = TerminalPreferences.of(player);
                    TerminalUsage<ItemKey> usage = preferences.usage();
                    int capacity = preferences.capacity();
                    helper.assertTrue(capacity >= 2 && capacity <= TerminalUsage.MAX_CAPACITY,
                            "the configured capacity is inside the hard bounds: " + capacity);

                    // Fill it exactly to the cap with distinct item types. The first one is asked for twice, so it is
                    // a favourite; the second is the oldest of the ones with a single count and therefore the weakest.
                    List<ItemKey> filler = distinctKeys(capacity);
                    for (ItemKey key : filler)
                        usage.record(key);
                    ItemKey favourite = filler.getFirst();
                    ItemKey weakest = filler.get(1);
                    usage.record(favourite);
                    helper.assertValueEqual(usage.size(), capacity, "the store is full");
                    helper.assertValueEqual(usage.countFor(favourite), 2L, "the favourite was asked for twice");

                    // A real request for an item type the store does not know yet: it is learned, and the weakest of
                    // the equal-count entries is the one that goes.
                    helper.assertTrue(terminal.requestFromTerminal(player, DIAMOND, REQUESTED).isAccepted(),
                            "the request for the new item type is accepted");
                    helper.assertValueEqual(usage.size(), capacity, "the store did not grow past its capacity");
                    helper.assertValueEqual(usage.countFor(DIAMOND), 1L, "the new item type was learned");
                    helper.assertValueEqual(usage.countFor(weakest), 0L, "the weakest entry was dropped");
                    helper.assertValueEqual(usage.countFor(favourite), 2L, "a favourite is not pushed out");
                    helper.assertValueEqual(usage.countFor(filler.get(2)), 1L,
                            "the entry after the weakest one stayed");
                })
                .thenSucceed();
    }

    // --- surviving a reload ----------------------------------------------------------------------------------------

    /**
     * The chosen order and the counts survive being written and read again: the exact path a world load takes.
     * <p>
     * {@link TerminalPreferences#save} and {@link TerminalPreferences#load} <b>are</b> that path — NeoForge's
     * attachment serializer does nothing but call them ({@code WareworksAttachments}) — so round-tripping them here is
     * round-tripping a reload, with real item components in the keys and with the recency stamps that eviction needs
     * afterwards. (A real save, quit and rejoin of a whole world is checked by the {@code robustness} scenario of the
     * dev harness, which a GameTest server cannot do.)
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void preferencessurviveasaveandload(GameTestHelper helper) {
        AisleFixture aisle = buildAisle(helper);

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().countOf(DIAMOND),
                        (long) DIAMONDS_IN_STOCK, "the diamonds are indexed"))
                .thenExecute(() -> {
                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    WarehouseTerminalBlockEntity terminal = terminal(helper, aisle);
                    Player player = playerAt(helper, aisle.rackPos(TERMINAL_RACK));

                    // A player who chose nothing and asked for nothing writes no tag at all.
                    TerminalPreferences fresh = new TerminalPreferences();
                    helper.assertTrue(fresh.isDefault(), "a fresh store has nothing to save");
                    helper.assertTrue(fresh.save(registries) == null, "and therefore writes no tag");

                    // A named sword, so the round trip has to carry data components and not only an item id.
                    ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
                    sword.set(DataComponents.CUSTOM_NAME, Component.literal("Wareworks favourite"));
                    ItemKey named = ItemKey.of(sword);

                    TerminalPreferences preferences = TerminalPreferences.of(player);
                    helper.assertValueEqual(preferences.sort(), TerminalSort.DEFAULT, "the order starts at the default");
                    helper.assertTrue(preferences.setSort(TerminalSort.USED), "the player chooses \"most used\"");
                    helper.assertTrue(terminal.requestFromTerminal(player, DIAMOND, REQUESTED).isAccepted(), "diamonds");
                    helper.assertTrue(terminal.requestFromTerminal(player, DIAMOND, REQUESTED).isAccepted(), "again");
                    helper.assertTrue(terminal.requestFromTerminal(player, EMERALD, REQUESTED).isAccepted(), "emeralds");
                    preferences.usage().record(named);

                    CompoundTag saved = preferences.save(registries);
                    helper.assertFalse(saved == null, "there is something to save now");
                    helper.assertValueEqual(saved.getInt(TerminalPreferences.VERSION_TAG), TerminalPreferences.VERSION,
                            "the saved shape is versioned");

                    TerminalPreferences loaded = new TerminalPreferences();
                    loaded.load(registries, saved);
                    helper.assertValueEqual(loaded.sort(), TerminalSort.USED, "the chosen order came back");
                    helper.assertValueEqual(loaded.usage().countFor(DIAMOND), 2L, "the diamond count came back");
                    helper.assertValueEqual(loaded.usage().countFor(EMERALD), 1L, "the emerald count came back");
                    helper.assertValueEqual(loaded.usage().countFor(named), 1L,
                            "an item with data components came back");
                    // Every entry, in the same strongest-first order, with the same count. The recency stamps are
                    // renumbered from 1 on a load (TerminalUsage#replaceAll) — their order is what eviction needs, not
                    // their values — so they are compared as an order and not one by one.
                    helper.assertValueEqual(keysAndCounts(loaded), keysAndCounts(preferences),
                            "every entry, in the same order, with the same count");
                    helper.assertValueEqual(stamps(loaded), List.of(1, 3, 2),
                            "the stamps are renumbered from 1 and keep the recency order they were saved in");

                    // The stamps survived, so eviction still has its tie-break after a load: of the two entries with a
                    // single count the emerald was asked for first, so it is the one that goes.
                    TerminalPreferences small = new TerminalPreferences(2);
                    small.load(registries, saved);
                    helper.assertValueEqual(small.usage().size(), 2, "a lowered cap is applied on load");
                    helper.assertValueEqual(small.usage().countFor(DIAMOND), 2L, "and keeps the strongest entry");

                    // Nothing crafted can throw or grow the store, and an unknown order reads as the default.
                    CompoundTag hostile = saved.copy();
                    hostile.putString(TerminalPreferences.SORT_TAG, "NOT_AN_ORDER");
                    TerminalPreferences tolerant = new TerminalPreferences();
                    tolerant.load(registries, hostile);
                    helper.assertValueEqual(tolerant.sort(), TerminalSort.DEFAULT, "an unknown order is the default");
                    tolerant.load(registries, new CompoundTag());
                    helper.assertTrue(tolerant.isDefault(), "an empty tag is a fresh store");
                    tolerant.load(registries, null);
                    helper.assertTrue(tolerant.isDefault(), "and so is no tag at all");
                })
                .thenSucceed();
    }

    // --- two players -----------------------------------------------------------------------------------------------

    /**
     * Two players at the same terminal keep separate counts <b>and</b> separate orders: the state hangs on the player,
     * so one player's habits can never reorder another's list.
     * <p>
     * This is what the static field on the client could not do and what the whole milestone is about on a server.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void twoplayerskeepseparatecountsandorders(GameTestHelper helper) {
        AisleFixture aisle = buildAisle(helper);

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.controller().countOf(EMERALD),
                        (long) EMERALDS_IN_STOCK, "the emeralds are indexed"))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = terminal(helper, aisle);
                    Player one = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
                    Player two = playerAt(helper, aisle.rackPos(TERMINAL_RACK));

                    helper.assertTrue(terminal.requestFromTerminal(one, DIAMOND, REQUESTED).isAccepted(), "one asks");
                    helper.assertTrue(terminal.requestFromTerminal(one, DIAMOND, REQUESTED).isAccepted(), "and again");
                    helper.assertTrue(terminal.requestFromTerminal(two, EMERALD, REQUESTED).isAccepted(), "two asks");

                    helper.assertValueEqual(countOf(one, DIAMOND), 2L, "player one's diamonds");
                    helper.assertValueEqual(countOf(one, EMERALD), 0L, "player one never asked for emeralds");
                    helper.assertValueEqual(countOf(two, EMERALD), 1L, "player two's emeralds");
                    helper.assertValueEqual(countOf(two, DIAMOND), 0L, "player two never asked for diamonds");

                    TerminalPreferences.of(one).setSort(TerminalSort.USED);
                    TerminalPreferences.of(two).setSort(TerminalSort.NAME);
                    helper.assertValueEqual(TerminalPreferences.of(one).sort(), TerminalSort.USED, "one's order");
                    helper.assertValueEqual(TerminalPreferences.of(two).sort(), TerminalSort.NAME, "two's order");

                    // A third player who has done nothing sees the default, with no history and nothing saved.
                    Player three = playerAt(helper, aisle.rackPos(TERMINAL_RACK));
                    helper.assertTrue(TerminalPreferences.existing(three).isEmpty(), "a bystander has no preferences");
                    helper.assertValueEqual(TerminalPreferences.of(three).sort(), TerminalSort.DEFAULT,
                            "and would see the default order");
                    helper.assertTrue(TerminalPreferences.of(three).usage().isEmpty(), "with no history");
                })
                .thenSucceed();
    }

    // --- the wire --------------------------------------------------------------------------------------------------

    /**
     * {@code TerminalUsagePayload} reads back exactly what it wrote, including an item with data components, and reads
     * no more bytes than it wrote; and it is bounded by the store's own hard cap whatever it is handed.
     * <p>
     * The counts are what the client is sent <b>instead of</b> a sorted list: both sides then sort with the same pure
     * comparator ({@code core.terminal.TerminalSort}), while the numbers stay the server's
     * ({@code docs/warehouse-system.md} §3.4.2).
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void theusagepayloadroundtrips(GameTestHelper helper) {
        helper.startSequence()
                .thenIdle(1)
                .thenExecute(() -> {
                    ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
                    sword.set(DataComponents.CUSTOM_NAME, Component.literal("Wareworks favourite"));
                    ItemKey named = ItemKey.of(sword);

                    TerminalUsage<ItemKey> usage = new TerminalUsage<>(TerminalUsage.MAX_CAPACITY);
                    usage.record(DIAMOND);
                    usage.record(DIAMOND);
                    usage.record(named);
                    TerminalUsagePayload payload = TerminalUsagePayload.of(7, TerminalSort.USED, usage.entries());
                    helper.assertValueEqual(payload.entries().size(), 2, "both item types travel");

                    TerminalUsagePayload decoded = roundTrip(helper, payload, TerminalUsagePayload.STREAM_CODEC);
                    helper.assertValueEqual(decoded.containerId(), 7, "the menu id");
                    helper.assertValueEqual(decoded.sort(), TerminalSort.USED, "the chosen order");
                    helper.assertValueEqual(decoded.entries(), payload.entries(), "every entry with its count");
                    helper.assertValueEqual(decoded.toUsage().countFor(DIAMOND), 2L, "the counts a screen sorts with");
                    helper.assertValueEqual(decoded.toUsage().countFor(named), 1L, "including components");

                    // An order name this build does not know reads as the default rather than as a shifted constant.
                    helper.assertValueEqual(new TerminalUsagePayload(1, null, List.of()).sort(), TerminalSort.DEFAULT,
                            "no order means the default");

                    // More entries than the store can ever hold are cut, so a crafted payload cannot grow a screen.
                    List<TerminalUsagePayload.Entry> many = new ArrayList<>();
                    for (int i = 0; i < TerminalUsagePayload.MAX_ENTRIES + 16; i++)
                        many.add(new TerminalUsagePayload.Entry(distinctKeys(1, i).getFirst(), 1));
                    helper.assertValueEqual(new TerminalUsagePayload(1, TerminalSort.USED, many).entries().size(),
                            TerminalUsagePayload.MAX_ENTRIES, "the payload is bounded by the store's cap");
                })
                .thenSucceed();
    }

    // --- the attachment --------------------------------------------------------------------------------------------

    /**
     * The attachment type is registered, serializable and copied on death, and reading a player's preferences stores
     * them on that player — which is what makes a change part of the next save.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = TIMEOUT_TICKS)
    public static void thepreferencesattachmentisregistered(GameTestHelper helper) {
        helper.startSequence()
                .thenIdle(1)
                .thenExecute(() -> {
                    helper.assertTrue(WareworksAttachments.TERMINAL_PREFERENCES.isBound(),
                            "the terminal preferences attachment type is registered");
                    Player player = playerAt(helper, new BlockPos(3, BASE_Y, 3));
                    helper.assertFalse(player.hasData(WareworksAttachments.TERMINAL_PREFERENCES),
                            "an untouched player holds none");
                    TerminalPreferences preferences = TerminalPreferences.of(player);
                    helper.assertTrue(player.hasData(WareworksAttachments.TERMINAL_PREFERENCES),
                            "reading them stores them, so a change is saved with the player");
                    helper.assertTrue(TerminalPreferences.of(player) == preferences,
                            "and the same instance answers every time, so counting in place is enough");
                    helper.assertValueEqual(TerminalPreferences.existing(player), Optional.of(preferences),
                            "existing() now finds them");

                    // The copy on death and on returning from the end, through the path the game really takes: the
                    // type's own copy handler, reached by the one public API that runs it
                    // (IEntityExtension#copyAttachmentsFrom, which is what PlayerEvent.Clone does). Asserting
                    // TerminalPreferences#copy() directly proved nothing about the attachment, because a type built
                    // without a copyHandler is given one that writes and reads the attachment instead (M24 review fix).
                    preferences.setSort(TerminalSort.NAME);
                    preferences.usage().record(DIAMOND);
                    Player respawned = playerAt(helper, new BlockPos(3, BASE_Y, 3));
                    respawned.copyAttachmentsFrom(player, true);
                    helper.assertTrue(respawned.hasData(WareworksAttachments.TERMINAL_PREFERENCES),
                            "the respawned player has the preferences, so dying costs nothing");
                    TerminalPreferences copy = TerminalPreferences.of(respawned);
                    helper.assertTrue(copy != preferences, "and they are a copy, not the dead player's own object");
                    helper.assertValueEqual(copy.sort(), TerminalSort.NAME, "the copy keeps the order");
                    helper.assertValueEqual(copy.counts(), preferences.counts(), "and every count");
                    helper.assertValueEqual(copy.capacity(), preferences.capacity(), "and the capacity");
                })
                .thenSucceed();
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private static AisleFixture buildAisle(GameTestHelper helper) {
        AisleFixture fixture = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        fixture.storage(STORAGE_RACK, DIAMOND.toStack(DIAMONDS_IN_STOCK), EMERALD.toStack(EMERALDS_IN_STOCK));
        fixture.terminal(TERMINAL_RACK);
        fixture.portInventory(PORT_RACK);
        return fixture;
    }

    /** The entries of {@code preferences} as "item and count" pairs, which a save has to carry unchanged. */
    private static List<String> keysAndCounts(TerminalPreferences preferences) {
        List<String> pairs = new ArrayList<>();
        for (TerminalUsage.Entry<ItemKey> entry : preferences.counts())
            pairs.add(entry.key() + " x" + entry.count());
        return pairs;
    }

    /** The recency stamps of {@code preferences}, in the order {@link TerminalPreferences#counts()} lists them. */
    private static List<Integer> stamps(TerminalPreferences preferences) {
        List<Integer> list = new ArrayList<>();
        for (TerminalUsage.Entry<ItemKey> entry : preferences.counts())
            list.add(entry.stamp());
        return list;
    }

    /** How often {@code player} has asked for {@code key}, read from the player and never from a terminal. */
    private static long countOf(Player player, ItemKey key) {
        return TerminalPreferences.existing(player).map(preferences -> preferences.usage().countFor(key)).orElse(0L);
    }

    /** One clipboard line, written exactly as {@code TerminalListGameTests} writes one. */
    private static ClipboardEntry entry(ItemKey key, int amount) {
        return new ClipboardEntry(false, Component.literal(key.toStack().getHoverName().getString()))
                .displayItem(key.toStack(), amount);
    }

    /**
     * A clipboard carrying {@code entries} on one page, as the Schematicannon writes its material checklist
     * ({@code ClipboardType.WRITTEN} and read-only; see {@code TerminalListGameTests}).
     */
    private static ItemStack clipboard(ClipboardEntry... entries) {
        ItemStack stack = AllBlocks.CLIPBOARD.asStack();
        stack.set(AllDataComponents.CLIPBOARD_CONTENT,
                new ClipboardContent(ClipboardType.WRITTEN, List.of(List.of(entries)), true));
        return stack;
    }

    /**
     * One key of the kind {@link TerminalPreferences#MAX_KEY_SIZE} exists for: a shulker box whose {@code container}
     * component holds 27 stacks, i.e. a key of kilobytes rather than of the ~190 a plain item measures.
     */
    private static ItemKey containerKey() {
        List<ItemStack> contents = new ArrayList<>(27);
        for (int slot = 0; slot < 27; slot++)
            contents.add(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 64));
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
        return ItemKey.of(box);
    }

    /**
     * {@code count} distinct item keys: diamonds that differ only in their custom name, so each is its own
     * {@link ItemKey} without needing an item type of its own in the registry.
     */
    private static List<ItemKey> distinctKeys(int count) {
        return distinctKeys(count, 0);
    }

    private static List<ItemKey> distinctKeys(int count, int from) {
        List<ItemKey> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ItemStack stack = new ItemStack(Items.DIAMOND);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("wareworks usage key " + (from + i)));
            keys.add(ItemKey.of(stack));
        }
        return keys;
    }

    private static <T> T roundTrip(GameTestHelper helper, T payload, StreamCodec<RegistryFriendlyByteBuf, T> codec) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
        codec.encode(buffer, payload);
        T decoded = codec.decode(buffer);
        helper.assertValueEqual(buffer.readableBytes(), 0, "the codec read everything it wrote");
        return decoded;
    }

    /** A survival player standing at the test-relative position, so the terminal's reach check passes. */
    private static Player playerAt(GameTestHelper helper, BlockPos pos) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(pos));
        player.moveTo(center.x, center.y, center.z);
        return player;
    }

    private static WarehouseTerminalBlockEntity terminal(GameTestHelper helper, AisleFixture aisle) {
        WarehouseTerminalBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_TERMINAL
                .getNullable(helper.getLevel(), aisle.absoluteRackPos(TERMINAL_RACK));
        if (be == null)
            helper.fail("missing warehouse terminal block entity", aisle.rackPos(TERMINAL_RACK));
        return be;
    }

    private static WarehouseOutputBlockEntity port(GameTestHelper helper, AisleFixture aisle) {
        WarehouseOutputBlockEntity be = WareworksBlockEntityTypes.WAREHOUSE_OUTPUT
                .getNullable(helper.getLevel(), aisle.absoluteRackPos(PORT_RACK));
        if (be == null)
            helper.fail("missing warehouse output block entity", aisle.rackPos(PORT_RACK));
        return be;
    }
}
